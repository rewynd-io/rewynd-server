package io.rewynd.common.cache.queue

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines
import io.rewynd.common.KLog
import io.rewynd.common.redis.blpopFlow
import io.rewynd.common.redis.xreadFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RedisJobQueue<Request, Response, ClientEventPayload, WorkerEventPayload>(
    private val id: String,
    private val redis: RedisClient,
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
            redis.connect().use { queueConn ->
                redis.connect()
                    .use { clientEventConn -> // TODO try to reuse this connection instead of spawning a new one
                        queueConn.coroutines().blpopFlow(listId).filterNotNull().collect { job ->

                            try {
                                var clientEventJob: Job? = null
                                val reqWrapper = Json.decodeFromString<RequestWrapper>(job.value)

                                RedisClusterJobQueue.log.info { "Got job $job" }

                                val clientEventPayloads = MutableSharedFlow<ClientEventPayload>()

                                val res: Deferred<Either<Exception, Response>> = async(Dispatchers.IO) {
                                    log.info { "Started Handler job" }
                                    try {
                                        handler(
                                            JobContext(
                                                deserializeRequest(reqWrapper.request), clientEventPayloads, {
                                                    conn.xadd(
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
                                        ).right()
                                    } catch (e: Exception) {
                                        e.left()
                                    }
                                }

                                clientEventJob = launch(Dispatchers.IO) {
                                    try {
                                        log.info { "Started ClientEvent job" }
                                        clientEventConn.coroutines().xreadFlow(clientId(reqWrapper.id)).flatMapConcat {
                                            it.values.asFlow()
                                                .map { event -> Json.decodeFromString<ClientEvent>(event) }
                                        }.transformWhile {
                                            emit(it)
                                            currentCoroutineContext().isActive && it !is ClientEvent.Cancel
                                        }.collect {
                                            log.info { "Received event $it" }
                                            when (it) {
                                                is ClientEvent.Cancel -> {
                                                    RedisClusterJobQueue.log.info { "Received cancel event for ${reqWrapper.id}" }
                                                    if (!res.isCancelled) {
                                                        res.cancel()
                                                    }
                                                    RedisClusterJobQueue.log.info { "Canceled ${reqWrapper.id}" }
                                                }

                                                is ClientEvent.NoOp -> {}
                                                is ClientEvent.Event -> clientEventPayloads.emit(
                                                    deserializeClientEventPayload(
                                                        it.payload
                                                    )
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        log.error(e) { "ClientEvent job failed" }
                                    }
                                }
                                try {

                                    RedisClusterJobQueue.log.info { "Waiting for handler" }
                                    val result = res.await()
                                    result.fold({ throw it }) {
                                        RedisClusterJobQueue.log.info { "Handler completed, emitting success" }
                                        conn.xadd(
                                            workerId(reqWrapper.id), mapOf(
                                                "success" to Json.encodeToString<WorkerEvent>(
                                                    WorkerEvent.Success(
                                                        serializeResponse(it)
                                                    )
                                                )
                                            )
                                        )
                                        RedisClusterJobQueue.log.info { "Emitted success" }
                                    }
                                } catch (e: Exception) {

                                    log.error(e) { "Handler encountered error" }
                                    // Exception could be due to cancellation, which apparently means suspend functions may not work
                                    res.cancel()
                                    log.info { "Emitting Fail event" }
                                    conn.xadd(
                                        workerId(reqWrapper.id),
                                        mapOf(
                                            "fail" to Json.encodeToString<WorkerEvent>(
                                                WorkerEvent.Fail(e.localizedMessage)
                                            )
                                        )
                                    )
                                    RedisClusterJobQueue.log.info { "Emitted Fail event" }
                                } finally {
                                    clientEventJob.cancel()
                                }
                                RedisClusterJobQueue.log.info { "Completed job $job" }
                            } catch (e: Exception) {
                                log.warn(e) { }
                            }
                        }
                    }
            }
        }

    override suspend fun monitor(jobId: JobId): Flow<WorkerEvent> {
        val client = redis.connect()
        val conn = client.coroutines()
        return conn.xreadFlow(workerId(jobId)).flatMapConcat {
            it.values.asFlow().map { event -> Json.decodeFromString<WorkerEvent>(event) }
        }.transformWhile {
            emit(it)
            it !is WorkerEvent.Success && it !is WorkerEvent.Fail
        }.onCompletion { client.close() }
    }

    override suspend fun cancel(jobId: JobId) {
        conn.xadd(clientId(jobId), mapOf("cancel" to Json.encodeToString<ClientEvent>(ClientEvent.Cancel)))
    }

    override suspend fun delete(jobId: JobId) {
        conn.del(workerId(jobId), clientId(jobId))
    }

    override fun close() = runBlocking {
        this@RedisJobQueue.conn.quit()
        Unit
    }

    override suspend fun notify(jobId: JobId, event: ClientEventPayload) {
        conn.xadd(
            clientId(jobId),
            mapOf("event" to Json.encodeToString<ClientEvent>(ClientEvent.Event(serializeClientEventPayload(event))))
        )
    }

    companion object : KLog()
}