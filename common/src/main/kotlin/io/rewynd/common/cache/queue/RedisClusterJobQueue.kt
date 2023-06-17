package io.rewynd.common.cache.queue

import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.coroutines
import io.rewynd.common.KLog
import io.rewynd.common.redis.blpopFlow
import io.rewynd.common.redis.xreadFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

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
    private val syncConn = redis.connect().coroutines()
    private val listId = "JobQueue:$id:List"
    private fun workerId(jobId: JobId) = "JobQueue:$id:${jobId.value}:Worker"
    private fun clientId(jobId: JobId) = "JobQueue:$id:${jobId.value}:Client"

    override suspend fun submit(req: Request): JobId {
        val id = JobId()
        syncConn.lpush(listId, Json.encodeToString(RequestWrapper(serializeRequest(req), id)))
        return id
    }

    override suspend fun register(
        handler: JobHandler<Request, Response, ClientEventPayload, WorkerEventPayload>,
        scope: CoroutineScope
    ): Job =
        scope.launch(Dispatchers.IO) {
            redis.connect().use { clientEventConn ->
                redis.connect().use { queueConn ->
                    queueConn.coroutines().blpopFlow(listId).filterNotNull().collect { job ->
                        var clientEventJob: Job? = null
                        val reqWrapper = Json.decodeFromString<RequestWrapper>(job.value)

                        try {
                            log.info { "Got job $job" }

                            val clientEventPayloads = MutableSharedFlow<ClientEventPayload>()

                            val res = async {
                                handler(
                                    JobContext(
                                        deserializeRequest(reqWrapper.request), clientEventPayloads, {
                                            syncConn.xadd(
                                                workerId(reqWrapper.id), mapOf(
                                                    "event" to Json.encodeToString<WorkerEvent>(
                                                        WorkerEvent.Event(
                                                            serializeWorkerEventPayload(
                                                                it
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        }, reqWrapper.id
                                    )
                                )
                            }

                            clientEventJob = launch(Dispatchers.IO) {
                                clientEventConn.coroutines().xreadFlow(clientId(reqWrapper.id)).flatMapConcat {
                                    it.values.asFlow()
                                        .map { event -> Json.decodeFromString<ClientEvent>(event) }
                                }.collect {
                                    ensureActive()
                                    when (it) {
                                        is ClientEvent.Cancel -> {
                                            log.info { "Received cancel event for ${reqWrapper.id}" }
                                            res.cancel()
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
                            }
                            log.info { "Waiting for handler" }

                            while (isActive && res.isActive) {
                                delay(1.seconds)
                            }
                            ensureActive()
                            val result = res.getCompleted()
                            log.info { "Handler completed, emitting success" }
                            syncConn.xadd(
                                workerId(reqWrapper.id), mapOf(
                                    "success" to Json.encodeToString(
                                        WorkerEvent.Success(
                                            serializeResponse(
                                                result
                                            )
                                        )
                                    )
                                )
                            )
                            log.info { "Emitted success" }
                        } catch (e: Exception) {
                            log.error(e) { "Handler encountered error" }
                            // Exception could be due to cancellation, which apparently means suspend functions may not work
                            runBlocking {
                                syncConn.xadd(
                                    workerId(reqWrapper.id),
                                    mapOf("fail" to Json.encodeToString(WorkerEvent.Fail(e.localizedMessage)))
                                )
                            }
                            log.info { "Emitted Fail event" }
                        } finally {
                            clientEventJob?.cancel()
                        }
                        log.info { "Completed job $job" }
                    }
                }
            }
        }

    override suspend fun monitor(jobId: JobId): Flow<WorkerEvent> = redis.connect().use { conn ->
        conn.coroutines().xreadFlow(clientId(jobId)).flatMapConcat {
            it.values.asFlow().map { event -> Json.decodeFromString<WorkerEvent>(event) }
        }
    }

    override suspend fun cancel(jobId: JobId) {
        syncConn.xadd(clientId(jobId), mapOf("cancel" to Json.encodeToString<ClientEvent>(ClientEvent.Cancel)))
    }

    override suspend fun delete(jobId: JobId) {
        syncConn.del(workerId(jobId), clientId(jobId))
    }

    override fun close() = runBlocking {
        this@RedisClusterJobQueue.syncConn.quit()
        Unit
    }

    override suspend fun notify(jobId: JobId, event: ClientEventPayload) {
        syncConn.xadd(
            clientId(jobId),
            mapOf("event" to Json.encodeToString<ClientEvent>(ClientEvent.Event(serializeClientEventPayload(event))))
        )
    }

    companion object : KLog()
}