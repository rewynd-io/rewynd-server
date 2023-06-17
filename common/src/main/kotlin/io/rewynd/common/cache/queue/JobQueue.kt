package io.rewynd.common.cache.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.*

data class JobContext<Request, Response, ClientEventPayload, WorkerEventPayload>(
    val request: Request,
    val clientEvents: Flow<ClientEventPayload>,
    val workerEventEmitter: suspend (WorkerEventPayload) -> Unit,
    val jobId: JobId
)

typealias JobHandler<Request, Response, ClientEventPayload, WorkerEventPayload> = suspend CoroutineScope.(JobContext<Request, Response, ClientEventPayload, WorkerEventPayload>) -> Response

sealed interface JobQueue<Request, Response, ClientEventPayload, WorkerEventPayload> : AutoCloseable {
    suspend fun submit(req: Request): JobId
    suspend fun register(
        handler: JobHandler<Request, Response, ClientEventPayload, WorkerEventPayload>,
        scope: CoroutineScope
    ): Job

    suspend fun monitor(jobId: JobId): Flow<WorkerEvent>

    suspend fun cancel(jobId: JobId): Unit

    suspend fun notify(jobId: JobId, event: ClientEventPayload)

    suspend fun delete(jobId: JobId)
}

@Serializable
data class JobId(val value: String = UUID.randomUUID().toString())

@Serializable
data class RequestWrapper(val request: String, val id: JobId)


@Serializable
sealed interface WorkerEvent {
    @Serializable
    object NoOp : WorkerEvent

    @Serializable
    data class Fail(val reason: String) : WorkerEvent {
        companion object
    }

    @Serializable
    data class Success(val payload: String) : WorkerEvent {
        companion object
    }

    @Serializable
    data class Event(val payload: String) : WorkerEvent {
        companion object
    }

    fun isTerminal() = when (this) {
        is Fail,
        is Success -> true

        else -> false
    }

    companion object
}


@Serializable
sealed interface ClientEvent {
    @Serializable
    object NoOp : ClientEvent

    @Serializable
    object Cancel : ClientEvent

    @Serializable
    data class Event(val payload: String) : ClientEvent {
        companion object
    }

    companion object
}
