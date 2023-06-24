package io.rewynd.common.cache.queue

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.coroutines
import io.lettuce.core.cluster.api.coroutines.RedisClusterCoroutinesCommands
import io.rewynd.common.KLog
import io.rewynd.common.redis.blpopFlow
import io.rewynd.common.redis.xreadFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.days

class RedisClusterJobQueue<Request, Response, ClientEventPayload, WorkerEventPayload>(
    private val id: String,
    private val redis: RedisClusterClient,
    private val serializeRequest: (Request) -> String,
    private val serializeResponse: (Response) -> String,
    private val serializeClientEventPayload: (ClientEventPayload) -> String,
    private val serializeWorkerEventPayload: (WorkerEventPayload) -> String,
    private val deserializeRequest: (String) -> Request,
    private val deserializeResponse: (String) -> Response,
    private val deserializeClientEventPayload: (String) -> ClientEventPayload,
    private val deserializeWorkerEventPayload: (String) -> WorkerEventPayload,
) : JobQueue<Request, Response, ClientEventPayload, WorkerEventPayload> {
    private val conn = redis.connect().coroutines()
    private val listId = "JobQueue:$id:List"
    private fun workerId(jobId: JobId) = "JobQueue:$id:${jobId.value}:Worker"
    private fun clientId(jobId: JobId) = "JobQueue:$id:${jobId.value}:Client"

    override suspend fun submit(req: Request): JobId {
        val id = JobId()
        conn.lpush(listId, Json.encodeToString(RequestWrapper(serializeRequest(req), id)))
        return id
    }

    override suspend fun register(
        handler: JobHandler<Request, Response, ClientEventPayload, WorkerEventPayload>,
        scope: CoroutineScope
    ): Job =
        scope.launch(Dispatchers.IO) {
            redis.coUse { queueConn ->
                redis.coUse { clientEventConn -> // TODO try to reuse this connection instead of spawning a new one
                    queueConn.blpopFlow(listId).filterNotNull().collect { job ->
                        try {
                            var clientEventJob: Job? = null
                            val reqWrapper = Json.decodeFromString<RequestWrapper>(job.value)
                            log.info { "Got job $job" }
                            val clientEventPayloads = MutableSharedFlow<ClientEventPayload>()
                            val res: Deferred<Either<Exception, Response>> =
                                executeHandler(handler, reqWrapper, clientEventPayloads)

                            clientEventJob = startClientEventJob(
                                clientEventConn,
                                reqWrapper,
                                res,
                                clientEventPayloads
                            )

                            try {
                                log.info { "Waiting for handler" }
                                val result = res.await()
                                result.fold({ throw it }) {
                                    log.info { "Handler completed, emitting success" }
                                    emitWorkerSuccess(reqWrapper, it)
                                    log.info { "Emitted success" }
                                }
                            } catch (e: Exception) {

                                log.error(e) { "Handler encountered error" }
                                // Exception could be due to cancellation, which apparently means suspend functions may not work
                                res.cancel()
                                log.info { "Emitting Fail event" }
                                emitWorkerFailure(reqWrapper, e)
                                log.info { "Emitted Fail event" }
                            } finally {
                                clientEventJob.cancel()
                            }

                            log.info { "Completed job $job" }
                        } catch (e: Exception) {
                            log.warn(e) { "Exception handling job $job" }
                        }
                    }
                }
            }
        }

    private fun CoroutineScope.executeHandler(
        handler: JobHandler<Request, Response, ClientEventPayload, WorkerEventPayload>,
        reqWrapper: RequestWrapper,
        clientEventPayloads: MutableSharedFlow<ClientEventPayload>
    ) = async(Dispatchers.IO) {
        log.info { "Started Handler job" }
        try {
            handler(
                JobContext(
                    deserializeRequest(reqWrapper.request), clientEventPayloads, {
                        emitWorkerEvent(reqWrapper, it)
                    }, reqWrapper.id
                )
            ).right()
        } catch (e: Exception) {
            e.left()
        }
    }

    private fun CoroutineScope.startClientEventJob(
        clientEventConn: RedisClusterCoroutinesCommands<String, String>,
        reqWrapper: RequestWrapper,
        res: Deferred<Either<Exception, Response>>,
        clientEventPayloads: MutableSharedFlow<ClientEventPayload>
    ) = launch(Dispatchers.IO) {
        try {
            log.info { "Started ClientEvent job" }
            clientEventConn.xreadFlow(clientId(reqWrapper.id)).flatMapConcat {
                it.values.asFlow()
                    .map { event -> Json.decodeFromString<ClientEvent>(event) }
            }.transformWhile {
                emit(it)
                currentCoroutineContext().isActive && it !is ClientEvent.Cancel
            }.collect {
                log.info { "Received event $it" }
                handleClientEvent(it, reqWrapper, res, clientEventPayloads)
            }
        } catch (e: Exception) {
            log.error(e) { "ClientEvent job failed" }
        }
    }

    private suspend fun handleClientEvent(
        it: ClientEvent,
        reqWrapper: RequestWrapper,
        res: Deferred<Either<Exception, Response>>,
        clientEventPayloads: MutableSharedFlow<ClientEventPayload>
    ) {
        when (it) {
            is ClientEvent.Cancel -> {
                log.info { "Received cancel event for ${reqWrapper.id}" }
                if (!res.isCancelled) {
                    res.cancel()
                }
                log.info { "Canceled ${reqWrapper.id}" }
            }

            is ClientEvent.NoOp -> {}
            is ClientEvent.Event -> clientEventPayloads.emit(
                deserializeClientEventPayload(
                    it.payload
                )
            )
        }
    }

    private suspend fun emitWorkerFailure(
        reqWrapper: RequestWrapper,
        e: Exception
    ) {
        conn.eval<Int>(
            """
            redis.call("xadd", KEYS[1], '*', ARGV[1], ARGV[2])
            return redis.call("expire", KEYS[1], ARGV[3])
        """.trimIndent(),
            ScriptOutputType.INTEGER,
            arrayOf(workerId(reqWrapper.id)),
            "fail",
            Json.encodeToString<WorkerEvent>(
                WorkerEvent.Fail(
                    e.localizedMessage
                )
            ),
            1.days.inWholeSeconds.toString()
        )
    }

    private suspend fun emitWorkerSuccess(
        reqWrapper: RequestWrapper,
        it: Response
    ) {
        conn.eval<Int>(
            """
            redis.call("xadd", KEYS[1], '*', ARGV[1], ARGV[2])
            return redis.call("expire", KEYS[1], ARGV[3])
        """.trimIndent(),
            ScriptOutputType.INTEGER,
            arrayOf(workerId(reqWrapper.id)),
            "success",
            Json.encodeToString<WorkerEvent>(
                WorkerEvent.Success(
                    serializeResponse(
                        it
                    )
                )
            ),
            1.days.inWholeSeconds.toString()
        )
    }

    private suspend fun emitWorkerEvent(
        reqWrapper: RequestWrapper,
        it: WorkerEventPayload
    ) {
        conn.eval<Int>(
            """
            redis.call("xadd", KEYS[1], '*', ARGV[1], ARGV[2])
            return redis.call("expire", KEYS[1], ARGV[3])
        """.trimIndent(),
            ScriptOutputType.INTEGER,
            arrayOf(workerId(reqWrapper.id)),
            "event",
            Json.encodeToString<WorkerEvent>(
                WorkerEvent.Event(
                    serializeWorkerEventPayload(
                        it
                    )
                )
            ),
            1.days.inWholeSeconds.toString()
        )
    }

    override suspend fun monitor(jobId: JobId): Flow<WorkerEvent> {
        val client = redis.connect()
        val monitorConn = client.coroutines()
        return monitorConn.xreadFlow(workerId(jobId)).flatMapConcat {
            it.values.asFlow().map { event -> Json.decodeFromString<WorkerEvent>(event) }
        }.transformWhile {
            emit(it)
            it !is WorkerEvent.Success && it !is WorkerEvent.Fail
        }.onCompletion { client.close() }
    }

    override suspend fun cancel(jobId: JobId) {
        conn.eval<Int>(
            """
            redis.call("xadd", KEYS[1], '*', ARGV[1], ARGV[2])
            return redis.call("expire", KEYS[1], ARGV[3])
        """.trimIndent(),
            ScriptOutputType.INTEGER,
            arrayOf(clientId(jobId)),
            "cancel",
            Json.encodeToString<ClientEvent>(ClientEvent.Cancel),
            1.days.inWholeSeconds.toString()
        )
    }

    override suspend fun delete(jobId: JobId) {
        conn.del(workerId(jobId), clientId(jobId))
    }

    override fun close() = runBlocking {
        this@RedisClusterJobQueue.conn.quit()
        Unit
    }

    override suspend fun notify(jobId: JobId, event: ClientEventPayload) {
        conn.eval<Int>(
            """
            redis.call("xadd", KEYS[1], '*', ARGV[1], ARGV[2])
            return redis.call("expire", KEYS[1], ARGV[3])
        """.trimIndent(),
            ScriptOutputType.INTEGER,
            arrayOf(clientId(jobId)),
            "event",
            Json.encodeToString<ClientEvent>(ClientEvent.Event(serializeClientEventPayload(event))),
            1.days.inWholeSeconds.toString()
        )
    }

    companion object : KLog()
}


suspend fun <T> RedisClusterClient.coUse(
    f: suspend (RedisClusterCoroutinesCommands<String, String>) -> T
) {
    this.connect().use {
        val conn = it.coroutines()
        f(conn)
    }
}