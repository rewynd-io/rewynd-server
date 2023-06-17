package io.rewynd.common.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.rewynd.common.CacheConfig
import io.rewynd.common.cache.queue.JobQueue
import io.rewynd.common.cache.queue.RedisJobQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import java.time.Instant
import java.util.*
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class RedisCache(
    config: CacheConfig.RedisConfig,
    private val client: RedisClient = RedisClient.create(
        config.uri
    ), private val conn: RedisCoroutinesCommands<String, String> = client.connect().coroutines()
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
    ): JobQueue<Request, Response, ClientEventPayload, WorkerEventPayload> = RedisJobQueue(
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

    override suspend fun tryAcquire(key: String, timeout: Duration): CacheLock? {

        val id = UUID.randomUUID().toString()
        fun mkCacheLock(
            id: String, timeout: Duration, validUntil: kotlinx.datetime.Instant
        ): CacheLock = object : CacheLock {
            override val validUntil: kotlinx.datetime.Instant = validUntil
            override val timeout: Duration = timeout
            override fun release() = runBlocking {
                runBlocking {
                    conn.eval<Long>(
                        """
                    if redis.call("get", KEYS[1]) == ARGV[1] then
                        return redis.call("del", KEYS[1])
                    else
                        return 0
                    end
                """.trimIndent(), ScriptOutputType.INTEGER, arrayOf(key), id
                    )
                }
                Unit
            }

            override fun extend(newTimeout: Duration?): CacheLock? = runBlocking {
                val nonNullTimeout = newTimeout ?: timeout
                val start = Clock.System.now()
                val setCount = (conn.eval<Long>(
                    """
                                if redis.call("get", KEYS[1]) == ARGV[1] then
                                    return redis.call("expireat", KEYS[1], ARGV[2])
                                else
                                    return 0
                                end
                            """.trimIndent(),
                    ScriptOutputType.INTEGER,
                    arrayOf(key),
                    id,
                    (start + nonNullTimeout).epochSeconds.toString(),
                ) ?: 0L).toLong()
                if (setCount > 0L) {
                    val validUntil = start + nonNullTimeout
                    mkCacheLock(id, nonNullTimeout, validUntil)
                } else {
                    release()
                }
                null
            }
        }

        val start = Clock.System.now()
        return if (conn.set(
                key, id, SetArgs.Builder.px(timeout.toJavaDuration()).nx()
            ) == "OK"
        ) {
            mkCacheLock(id, timeout, start + timeout)
        } else {
            conn.eval<String>(
                """
                    if redis.call("get", KEYS[1]) == ARGV[1] then
                        return redis.call("del", KEYS[1])
                    else
                        return 0
                    end
                """.trimIndent(), ScriptOutputType.INTEGER, arrayOf(key), id
            )

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