package io.rewynd.common.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.coroutines
import io.lettuce.core.cluster.api.coroutines.RedisClusterCoroutinesCommands
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands
import io.rewynd.common.CacheConfig
import io.rewynd.common.cache.queue.JobQueue
import io.rewynd.common.cache.queue.RedisClusterJobQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.time.Instant
import java.util.*
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class RedisClusterCache(
    config: CacheConfig.RedisClusterConfig, private val client: RedisClusterClient = RedisClusterClient.create(
        config.uris
    ), private val conn: RedisClusterCoroutinesCommands<String, String> = client.connect().coroutines()
) : Cache {
    override fun <Request, Response, ClientEventPayload, WorkerEventPayload> getJobQueue(
        key: String,
        serializeRequest: (Request) -> String,
        serializeResponse: (Response) -> String,
        serializeClientEventPayload: (ClientEventPayload) -> String,
        serializeWorkerEventPayload: (WorkerEventPayload) -> String,
        deserializeRequest: (String) -> Request,
        deserializeResponse: (String) -> Response,
        deserializeClientEventPayload: (String) -> ClientEventPayload,
        deserializeWorkerEventPayload: (String) -> WorkerEventPayload,
    ): JobQueue<Request, Response, ClientEventPayload, WorkerEventPayload> = RedisClusterJobQueue(
        key,
        this.client,
        serializeRequest,
        serializeResponse,
        serializeClientEventPayload,
        serializeWorkerEventPayload,
        deserializeRequest,
        deserializeResponse,
        deserializeClientEventPayload,
        deserializeWorkerEventPayload
    )

    override suspend fun put(key: String, value: String, expiration: Instant) {
        conn.psetex(key, java.time.Duration.between(Instant.now(), expiration).toMillis(), value)
    }

    override suspend fun get(key: String): String? = conn.get(key)

    override suspend fun del(key: String) {
        conn.del(key)
    }

    override suspend fun expire(key: String, expiration: Instant) {
        conn.expireat(key, expiration)
    }

    // TODO pull this out into a library - There's no other lettuce implementations that I can find
    override suspend fun tryAcquire(key: String, timeout: Duration): CacheLock? {
        val nodes = (client.connect().sync() as RedisAdvancedClusterCommands<*, *>).upstream().asMap()
            .map { entry ->
                val uri = entry.key.uri.apply {
                    this.timeout =
                        timeout.div(1000).inWholeMilliseconds.coerceAtLeast(10).milliseconds.toJavaDuration()
                }
                RedisClient.create(uri).apply {
                }.connect().coroutines()
            }
        val id = UUID.randomUUID().toString()
        fun mkCacheLock(
            nodes: List<RedisCoroutinesCommands<String, String>>,
            id: String,
            timeout: Duration,
            validUntil: kotlinx.datetime.Instant
        ): CacheLock =
            object : CacheLock {
                override val validUntil: kotlinx.datetime.Instant = validUntil
                override val timeout: Duration = timeout
                override fun release() = runBlocking {
                    nodes.forEach {
                        it.eval<String>(
                            """
                    if redis.call("get", KEYS[1]) == ARGV[1] then
                        return redis.call("del", KEYS[1])
                    else
                        return 0
                    end
                """.trimIndent(), ScriptOutputType.INTEGER, arrayOf(key), id
                        )
                    }
                }

                override fun extend(newTimeout: Duration?): CacheLock? = runBlocking {
                    val nonNullTimeout = newTimeout ?: timeout
                    val start = Clock.System.now()
                    val setCount = nodes.sumOf {
                        (it.eval<Long>(
                            """
                    if redis.call("get", KEYS[1]) == ARGV[1] then
                        return redis.call("pexpireat", KEYS[1], ARGV[2])
                    else
                        return 0
                    end
                """.trimIndent(),
                            ScriptOutputType.INTEGER,
                            arrayOf(key),
                            id,
                            (start + nonNullTimeout).toEpochMilliseconds().toString()
                        ) ?: 0L)
                    }
                    if (setCount > floor(nodes.size.toDouble() / 2.0).toLong()) {
                        val validUntil = start + nonNullTimeout
                        mkCacheLock(nodes, id, nonNullTimeout, validUntil)
                    } else {
                        release()
                        null
                    }
                }
            }

        val start = Clock.System.now()
        val setCount = nodes.sumOf {
            (if (it.set(
                    key,
                    id,
                    SetArgs.Builder.px(timeout.toJavaDuration()).nx()
                ) == "OK"
            ) 1 else 0) as Int
        }
        return if (setCount > Math.floor(nodes.size.toDouble() / 2.0)) {
            mkCacheLock(nodes, id, timeout, start + timeout)
        } else {
            nodes.forEach {
                it.eval<String>(
                    """
                    if redis.call("get", KEYS[1]) == ARGV[1] then
                        return redis.call("del", KEYS[1])
                    else
                        return 0
                    end
                """.trimIndent(), ScriptOutputType.INTEGER, arrayOf(key), id
                )
            }
            null
        }
    }

    override suspend fun pub(key: String, value: String) {
        TODO("Not yet implemented")
    }

    override suspend fun sub(key: String): Flow<String> {
        TODO("Not yet implemented")
    }

}