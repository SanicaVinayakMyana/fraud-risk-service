// Dashboard: polls the same public API the ledger uses. No framework, no build step.
const $ = (id) => document.getElementById(id);
let adminToken = null;

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (adminToken) headers['X-Admin-Token'] = adminToken;
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401 && !options.retried) {
    adminToken = window.prompt('Admin token');
    return adminToken ? api(path, { ...options, retried: true }) : response;
  }
  return response;
}

const text = (value) => document.createTextNode(value == null ? '' : String(value));

function cell(row, content, className) {
  const td = row.insertCell();
  if (className) td.className = className;
  if (content instanceof Node) td.appendChild(content); else td.appendChild(text(content));
  return td;
}

function badge(label) {
  const span = document.createElement('span');
  span.className = `badge ${label}`;
  span.textContent = label;
  return span;
}

function reason(r) {
  const wrap = document.createElement('span');
  wrap.appendChild(text(r.code));
  const detail = document.createElement('small');
  detail.textContent = r.detail;
  wrap.appendChild(detail);
  return wrap;
}

async function refreshDecisions() {
  const decisions = await (await api('/v1/decisions?limit=200')).json();
  const counts = { ALLOW: 0, REVIEW: 0, BLOCK: 0 };
  decisions.forEach((d) => { counts[d.decision] += 1; });
  $('n-allow').textContent = counts.ALLOW;
  $('n-review').textContent = counts.REVIEW;
  $('n-block').textContent = counts.BLOCK;
  const latencies = decisions.map((d) => d.latencyMicros).sort((a, b) => a - b);
  $('p99').textContent = latencies.length
    ? `${(latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * 0.99))] / 1000).toFixed(2)} ms` : '–';

  const body = $('decisions');
  body.replaceChildren();
  decisions.slice(0, 60).forEach((d) => {
    const row = body.insertRow();
    cell(row, new Date(d.decidedAt).toLocaleTimeString());
    cell(row, d.accountId);
    cell(row, badge(d.decision));
    cell(row, d.score);
    cell(row, d.mlScore.toFixed(2));
    cell(row, reason(d.reasons[0]), 'reason');
    cell(row, d.shadow && d.shadow.decision !== d.decision ? badge(d.shadow.decision) : '');
    cell(row, d.latencyMicros);
  });
}

async function refreshRules() {
  const rules = await (await api('/v1/rules')).json();
  $('rules-version').textContent = rules.version;
  const body = $('rules');
  body.replaceChildren();
  rules.rules.forEach((r) => {
    const row = body.insertRow();
    const name = document.createElement('span');
    name.title = r.description;
    name.textContent = r.id;
    cell(row, name);
    cell(row, r.action ? `${r.points} · ${r.action}` : r.points);
    cell(row, badge(r.mode));
    const button = document.createElement('button');
    button.className = 'small';
    const target = r.mode === 'ENFORCE' ? 'SHADOW' : 'ENFORCE';
    button.textContent = target === 'ENFORCE' ? 'Promote' : 'Shadow';
    button.onclick = async () => {
      await api(`/v1/rules/${r.id}/mode`, { method: 'PUT', body: JSON.stringify({ mode: target }) });
      refreshRules();
    };
    cell(row, button);
  });
}

async function refreshShadow() {
  const report = await (await api('/v1/shadow/report')).json();
  $('divergent').textContent = report.divergentDecisions;
  const body = $('shadow');
  body.replaceChildren();
  report.rules.forEach((r) => {
    const row = body.insertRow();
    cell(row, r.ruleId);
    cell(row, r.hits);
    cell(row, `${(r.hitRate * 100).toFixed(2)}%`);
    cell(row, Object.entries(r.changes).map(([k, v]) => `${k}: ${v}`).join(', ') || '0');
  });
}

async function init() {
  const health = await (await fetch('/health')).json();
  $('model').textContent = health.model;
  const demo = await (await fetch('/v1/demo')).json();
  $('attacks-panel').hidden = !demo.enabled;
  document.querySelectorAll('[data-attack]').forEach((button) => {
    button.onclick = async () => {
      const response = await api(`/v1/demo/attacks/${button.dataset.attack}`, { method: 'POST' });
      const body = await response.json();
      $('attack-status').textContent = response.ok
        ? `${button.textContent} started on ${body.accountId}. Watch the decisions below.` : body.message;
    };
  });
  const tick = () => Promise.all([refreshDecisions(), refreshShadow()]).catch(console.error);
  tick();
  refreshRules();
  setInterval(tick, 1500);
}

init();
