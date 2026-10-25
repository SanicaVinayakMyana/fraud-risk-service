-- Records one transaction for an account and returns its velocity windows, atomically.
-- Mirrors InMemoryVelocityStore exactly; RedisVelocityStoreSpec checks the two agree.
--
-- KEYS: 1 tx ids (zset, score = ts)   2 "txId|amountUsd" (zset, score = ts)
--       3 counterparties (zset, score = latest ts)   4 devices (set)   5 countries (set)
--       6 running stats (hash: n, mean, m2, last)
-- ARGV: 1 txId  2 ts millis  3 amountUsd  4 counterparty  5 device  6 country ('' when absent)
--       7 TTL in seconds for long-lived history (devices, countries, stats)

local now = tonumber(ARGV[2])
local amount = tonumber(ARGV[3])
local hourAgo = now - 3600000
local minuteAgo = now - 60000
local historyTtl = tonumber(ARGV[7])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', hourAgo))
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', string.format('%.0f', hourAgo))
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', string.format('%.0f', hourAgo))

local stats = redis.call('HMGET', KEYS[6], 'n', 'mean', 'm2', 'last')
local n = tonumber(stats[1]) or 0
local mean = tonumber(stats[2]) or 0
local m2 = tonumber(stats[3]) or 0
local last = tonumber(stats[4])

local duplicate = redis.call('ZSCORE', KEYS[1], ARGV[1]) ~= false
local newDevice = 0
local newCountry = 0

if not duplicate then
  if ARGV[5] ~= '' then
    if n > 0 and redis.call('SISMEMBER', KEYS[4], ARGV[5]) == 0 then newDevice = 1 end
    redis.call('SADD', KEYS[4], ARGV[5])
    redis.call('EXPIRE', KEYS[4], historyTtl)
  end
  if ARGV[6] ~= '' then
    if n > 0 and redis.call('SISMEMBER', KEYS[5], ARGV[6]) == 0 then newCountry = 1 end
    redis.call('SADD', KEYS[5], ARGV[6])
    redis.call('EXPIRE', KEYS[5], historyTtl)
  end
  redis.call('ZADD', KEYS[1], string.format('%.0f', now), ARGV[1])
  redis.call('ZADD', KEYS[2], string.format('%.0f', now), ARGV[1] .. '|' .. ARGV[3])
  if ARGV[4] ~= '' then
    local seen = tonumber(redis.call('ZSCORE', KEYS[3], ARGV[4]))
    if seen == nil or seen < now then
      redis.call('ZADD', KEYS[3], string.format('%.0f', now), ARGV[4])
    end
  end
  local n1 = n + 1
  local delta = amount - mean
  local mean1 = mean + delta / n1
  local m21 = m2 + delta * (amount - mean1)
  local last1 = now
  if last ~= nil and last > now then last1 = last end
  redis.call('HSET', KEYS[6], 'n', string.format('%.0f', n1), 'mean', string.format('%.17g', mean1),
    'm2', string.format('%.17g', m21), 'last', string.format('%.0f', last1))
  redis.call('EXPIRE', KEYS[6], historyTtl)
  for i = 1, 3 do redis.call('EXPIRE', KEYS[i], 3700) end
end

local count60 = redis.call('ZCOUNT', KEYS[1], string.format('(%.0f', minuteAgo), '+inf')
local count1h = redis.call('ZCOUNT', KEYS[1], string.format('(%.0f', hourAgo), '+inf')
local sum = 0
for _, member in ipairs(redis.call('ZRANGEBYSCORE', KEYS[2], string.format('(%.0f', hourAgo), '+inf')) do
  sum = sum + tonumber(string.match(member, '|([^|]+)$'))
end
local counterparties = redis.call('ZCOUNT', KEYS[3], string.format('(%.0f', hourAgo), '+inf')

local std = 0
if n >= 2 then std = math.sqrt(m2 / (n - 1)) end
local since = -1
if last ~= nil then since = math.max(0, now - last) / 1000 end

return {count60, count1h, string.format('%.17g', sum), counterparties, newDevice, newCountry,
  string.format('%.0f', n), string.format('%.17g', mean), string.format('%.17g', std), string.format('%.17g', since)}
