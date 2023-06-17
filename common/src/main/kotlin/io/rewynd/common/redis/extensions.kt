package io.rewynd.common.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.cluster.api.coroutines.RedisClusterCoroutinesCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import mu.KotlinLogging

private val log = KotlinLogging.logger { }

@OptIn(ExperimentalLettuceCoroutinesApi::class)
fun <K : Any, V : Any> RedisCoroutinesCommands<K, V>.blpopFlow(vararg keys: K) = flow {
    while (true) {
        val popped = this@blpopFlow.blpop(0, *keys)
        log.info { "Received Job $popped" }
        emit(popped)
    }
}.flowOn(Dispatchers.IO)

@OptIn(ExperimentalLettuceCoroutinesApi::class)
fun <K : Any, V : Any> RedisClusterCoroutinesCommands<K, V>.blpopFlow(vararg keys: K) = flow {
    while (true) {
        val popped = this@blpopFlow.blpop(0, *keys)
        log.info { "Received Job $popped" }
        emit(popped)
    }
}.flowOn(Dispatchers.IO)

@OptIn(ExperimentalLettuceCoroutinesApi::class)
fun <K : Any, V : Any> RedisCoroutinesCommands<K, V>.xreadFlow(vararg keys: K) = flow {
    val lastMap: MutableMap<K, String?> = keys.associateWith { k -> null }.toMutableMap()
    while (true) {
        val res = this@xreadFlow.xread(
            XReadArgs().block(100),
            *keys.map {
                XReadArgs.StreamOffset.from(
                    it,
                    lastMap[it] ?: "0"
                )
            }.toTypedArray()
        )
        res.collect {
            lastMap[it.stream] = it.id
            emit(it.body)
        }
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
fun <K : Any, V : Any> RedisClusterCoroutinesCommands<K, V>.xreadFlow(vararg keys: K) = flow {
    val lastMap: MutableMap<K, String?> = keys.associateWith { k -> null }.toMutableMap()
    val res = this@xreadFlow.xread(
        XReadArgs().block(0),
        *keys.map {
            XReadArgs.StreamOffset.from(
                it,
                lastMap[it] ?: "0"
            )
        }.toTypedArray()
    )
    res.collect {
        lastMap[it.stream] = it.id
        emit(it.body)
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <K : Any, V : Any> Flow<Map<K, V>>.xwrite(redis: RedisCoroutinesCommands<K, V>, key: K) = this.collect {
    log.info { it }
    redis.xadd(key, it)
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
suspend fun <K : Any, V : Any> Flow<Map<K, V>>.xwrite(redis: RedisClusterCoroutinesCommands<K, V>, key: K) =
    this.collect {
        log.info { it }
        redis.xadd(key, it)
    }