package com.sanica.risk.support

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.JedisPooled
import redis.embedded.RedisServer

/**
 * One Redis for the whole test run: Redis 7 in Docker when available (CI), otherwise an embedded redis-server
 * binary, so the suite runs on a laptop with no setup at all.
 */
class TestRedis {

    private static String url

    static synchronized String url() {
        if (url == null) {
            if (dockerAvailable()) {
                def container = new GenericContainer(DockerImageName.parse('redis:7-alpine')).withExposedPorts(6379)
                container.start()
                url = "redis://${container.host}:${container.getMappedPort(6379)}"
            } else {
                int port = new ServerSocket(0).withCloseable { it.localPort }
                def server = new RedisServer(port)
                server.start()
                Runtime.runtime.addShutdownHook(new Thread({ server.stop() }))
                url = "redis://localhost:${port}"
            }
        }
        url
    }

    static JedisPooled client() {
        new JedisPooled(URI.create(url()))
    }

    private static boolean dockerAvailable() {
        try {
            DockerClientFactory.instance().isDockerAvailable()
        } catch (Throwable ignored) {
            false
        }
    }
}
