package io.rewynd.worker.stream

import io.rewynd.common.cache.Cache
import io.rewynd.common.cache.queue.JobId
import io.rewynd.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class StreamMetadataHelper(
    private val streamProps: StreamProps,
    private val jobId: JobId,
    private val cache: Cache
) {
    private val mutex = Mutex()
    private var subtitles: SubtitleMetadata? = null
    private var segments: List<StreamSegmentMetadata> = emptyList()
    private var mime: Mime? = null
    private var complete: Boolean = false
    private var processed: Duration = Duration.ZERO
    private suspend fun put() {

        mime?.let {
            if(streamProps.subtitleStreamName == null || subtitles != null) {
                cache.putStreamMetadata(
                    streamProps.id,
                    StreamMetadataWrapper(
                        StreamMetadata(
                            streamProps,
                            segments,
                            subtitles,
                            it,
                            complete,
                            processed,
                            jobId
                        )
                    ),
                    expiration = (Clock.System.now() + 1.hours).toJavaInstant()
                )
            }
        }
    }

    suspend fun addSubtitleSegment(segment: SubtitleSegment) = mutex.withLock {
        this.subtitles = SubtitleMetadata(
            segments = (this.subtitles?.segments ?: emptyList()) + listOf(segment),
            complete = this.subtitles?.complete ?: false
        )
        put()
    }

    suspend fun completeSubtitles() = mutex.withLock {
        this.subtitles = this.subtitles?.copy(complete = true)
        put()
    }

    suspend fun addSegment(duration: Duration) = mutex.withLock {
        val index = this.segments.size
        this.segments = this.segments + listOf(StreamSegmentMetadata(index, duration))
        this.processed = processed + duration
        put()
        index
    }

    suspend fun init(mime: Mime) = mutex.withLock {
        this.mime = mime
        put()
    }

    suspend fun complete() = mutex.withLock {
        this.complete
        put()
    }
}