package io.rewynd.worker.stream

import io.rewynd.common.cache.Cache
import io.rewynd.common.*
import io.rewynd.worker.Event
import io.rewynd.worker.Mp4Frag
import io.rewynd.worker.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class Cue(val start: Duration, val end: Duration, val content: String)
data class CueState(val cues: List<Cue>, val styles: List<String>, val startOffset: Duration = ZERO)

sealed interface InProgressCue {
    data class Cue(val start: Duration, val end: Duration, val content: String) : InProgressCue
    data class Style(val content: String) : InProgressCue

}

fun CueState.push(complete: InProgressCue?) =
    when (complete) {
        null -> this
        is InProgressCue.Cue -> copy(cues = cues + listOf(Cue(complete.start, complete.end, complete.content)))
        is InProgressCue.Style -> copy(styles = styles + listOf(complete.content))
    }

fun List<Cue>.format() = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:0,LOCAL:00:00:00.000\n\n\n\n" + joinToString("\n\n\n") { cue ->
    val startHours = cue.start.inWholeHours
    val startMinutes = (cue.start - startHours.hours).inWholeMinutes
    val startSecs = (cue.start - startHours.hours - startMinutes.minutes).inWholeSeconds
    val startMillis = (cue.start - startHours.hours - startMinutes.minutes - startSecs.seconds).inWholeMilliseconds
    val endHours = cue.end.inWholeHours
    val endMinutes = (cue.end - endHours.hours).inWholeMinutes
    val endSecs = (cue.end - endHours.hours - endMinutes.minutes).inWholeSeconds
    val endMillis = (cue.end - endHours.hours - endMinutes.minutes - endSecs.seconds).inWholeMilliseconds
    "${
        startHours.takeIf {
            it != 0L
            true
        }?.let { "${it.leftPad()}:" } ?: ""
    }${startMinutes.leftPad()}:${startSecs.leftPad()}.${endMillis.rightPad()} --> ${
        endHours.takeIf {
            it != 0L
            true
        }?.let { "${it.leftPad()}:" } ?: ""
    }${endMinutes.leftPad()}:${endSecs.leftPad()}.${endMillis.rightPad()}\n${cue.content.trim()}"
} + "\n"

fun mkStreamJobHandler(cache: Cache): StreamJobHandler = { context ->
    val streamProps = context.request
    val location = streamProps.mediaInfo.fileInfo.location.toFfmpegUri()
    val videoTrackProps = streamProps.mkVideoTrackProps()
    val audioTrackProps = streamProps.mkAudioTrackProps()
    val metadataHelper = StreamMetadataHelper(streamProps, context.jobId, cache)

    val subtitleJob = launch(Dispatchers.IO) {
        log.info { "Started subtitle job" }
        if (streamProps.subtitleStreamName != null) {
            val track = streamProps.mediaInfo.subtitleTracks[streamProps.subtitleStreamName]
            val file = streamProps.mediaInfo.subtitleFiles[streamProps.subtitleStreamName]
            val args = if (file != null) {
                listOf(
                    "ffmpeg",
                    "-loglevel",
                    "quiet",
                    "-accurate_seek",
                    "-ss",
                    (streamProps.startOffset.inWholeSeconds).toString(),
                    "-i",
                    file.toFfmpegUri(),
                    "-c:s:0",
                    "webvtt",
                    "-f",
                    "webvtt",
                    "pipe:1"
                )
            } else if (track != null) {
                listOf(
                    "ffmpeg",
                    "-loglevel",
                    "quiet",
                    "-accurate_seek",
                    "-ss",
                    (streamProps.startOffset.inWholeSeconds).toString(),
                    "-i",
                    streamProps.mediaInfo.fileInfo.location.toFfmpegUri(),
                    "-c:s:${track.index}",
                    "webvtt",
                    "-f",
                    "webvtt",
                    "pipe:1"
                )
            } else {
                null
            }
            val lines = args?.let {
                log.info { "Running: ${args.joinToString(" ")}" }
                val pb = ProcessBuilder(*it.toTypedArray())
                val process = pb.start()
                process.errorReader().lineSequence().forEach { log.error { it } }
                process.inputReader().lineSequence().asFlow()
            } ?: emptyFlow()

            lines.fold(
                CueState(
                    emptyList(),
                    emptyList()
                ) to null as InProgressCue?
            ) { (state, inProgress), line ->
                log.info { line }
                val (newState, newProgress) = if (line.trim() == "STYLE") {
                    state to InProgressCue.Style("")
                } else if (line.trim() == "REGION") {
                    state.push(inProgress) to null
                } else if (line.trim() == "NOTE") {
                    state.push(inProgress) to null
                } else if (line.contains("-->")) {
                    val timestamps = line.split("-->")
                    if (timestamps.size == 2) {
                        timestamps[0].parseDuration()?.let { start ->
                            timestamps[1].parseDuration()?.let { end ->
                                state.push(inProgress) to InProgressCue.Cue(
                                    start,
                                    end,
                                    ""
                                )
                            }
                        } ?: (state to inProgress)
                    } else {
                        state to inProgress
                    }
                } else if (inProgress != null) {
                    when (inProgress) {
                        is InProgressCue.Style -> {
                            state to inProgress.copy(content = inProgress.content + "\n" + line)
                        }

                        is InProgressCue.Cue -> {
                            state to inProgress.copy(content = inProgress.content + "\n" + line)
                        }
                    }
                } else {
                    state to null
                }
                val cues = newState.cues
                val end = (cues.lastOrNull()?.end ?: ZERO)
                val newStartOffset = newState.startOffset + 10.seconds
                val trimmedNewState = if (newStartOffset < end) {
                    val segmentCues = cues.takeWhile { newStartOffset > it.end }.map {
                        val segEnd =
                            it.end.inWholeMicroseconds.coerceAtMost(newStartOffset.inWholeMicroseconds).microseconds
                        val segStart =
                            it.start.inWholeMicroseconds.coerceAtMost(segEnd.inWholeMicroseconds).microseconds
                        it.copy(
                            start = segStart,
                            end = segEnd
                        )
                    }
                    val remaining = cues.takeLastWhile { newStartOffset < it.end }.map {
                        val segStart =
                            it.start.inWholeMicroseconds.coerceAtLeast(newStartOffset.inWholeMicroseconds).microseconds
                        val segEnd = it.end.inWholeMicroseconds.coerceAtLeast(segStart.inWholeMicroseconds).microseconds
                        it.copy(
                            start = segStart,
                            end = segEnd
                        )
                    }

                    metadataHelper.addSubtitleSegment(SubtitleSegment(10.seconds, segmentCues.format()))
                    newState.copy(cues = remaining, startOffset = newStartOffset)
                } else newState

                trimmedNewState to newProgress
            }.let { (cueState, inProgress) ->
                val finalCueState = cueState.push(inProgress)
                val duration = finalCueState.cues.lastOrNull()?.let { it.end - finalCueState.startOffset }
                duration?.let {
                    metadataHelper.addSubtitleSegment(
                        SubtitleSegment(
                            it,
                            finalCueState.cues.map { cue ->
                                cue.copy(
                                    start = cue.start,
                                    end = cue.end
                                )
                            }.format()
                        )
                    )
                }
                metadataHelper.completeSubtitles()
            }
            log.info { "Ended subtitle job" }
        }
    }

    val job = launch(Dispatchers.IO, CoroutineStart.LAZY) {
        val args = (listOf(
            "ffmpeg",
            "-loglevel",
            "quiet",
            "-progress", "pipe:2", // TODO parse these from error output and add them to metadata
            "-nostats",
            //TODO nvidia hwaccel actually appears to be as simple as
            //"-hwaccel", "cuda",
            "-probesize",
            "1000000", "-analyzeduration",
            "1000000", "-accurate_seek",
            "-ss",
            (streamProps.startOffset.inWholeSeconds).toString(),
            "-i", location
        ) +
                videoTrackProps + audioTrackProps +
                listOf(
                    "-f", "mp4",
                    "-ac", "2", // TODO support more than just stereo audio streams
                    "-movflags", "+delay_moov+frag_keyframe+empty_moov+default_base_moof",
                    "pipe:1"
                )).toTypedArray()
        log.info { "Running: ${args.joinToString(" ")}" }
        val pb = ProcessBuilder(*args)
        val process = pb.start()
        val progressJob = launch(Dispatchers.IO) {
            try {
                process.errorReader().lineSequence().asFlow().takeWhile {
                    currentCoroutineContext().isActive
                }.runningFold(emptyMap<String, String>()) { acc, value ->
                    val mapping = value.split("=")
                    if (mapping.size == 2) {
                        acc + mapOf(mapping[0] to mapping[1])
                    } else acc
                }.collect {
                    context.workerEventEmitter(WorkerStreamEvents.Progress(it))
                }
            } catch (e: Exception) {
                log.error(e) { "Progress job encountered error" }
            }
        }
        try {
            Mp4Frag(process.inputStream).read().fold(metadataHelper) { acc, event ->
                when (event) {
                    is Event.Init -> {
                        cache.putInitMp4(
                            streamProps.id,
                            event.data,
                            (Clock.System.now() + 1.hours).toJavaInstant()
                        )
                        acc.init(
                            Mime(
                                if (event.videoCodec != null) "video/mp4" else "audio/mp4",
                                listOfNotNull(event.videoCodec, event.audioCodec)
                            )
                        )
                    }

                    is Event.Segment -> {
                        val segmentIndex = acc.addSegment(event.duration)
                        cache.putSegmentM4s(
                            streamProps.id,
                            segmentIndex,
                            event.data,
                            (Clock.System.now() + 1.hours).toJavaInstant()
                        )
                    }

                    is Event.Error -> {
                        throw RuntimeException(event.reason)
                    }
                }
                acc
            }.also {
                it.complete()
            }
        } finally {
            log.info { "Cancelling Progress" }
            progressJob.cancel()
            subtitleJob.cancel()
            process.destroy()
            progressJob.invokeOnCompletion {
                log.info { "Canceled Progress" }
            }
        }
    }

    val heartbeat = launch(Dispatchers.IO) {
        log.info { "Started heartbeat for ${streamProps.id}" }
        val heartbeatState = context.clientEvents.takeWhile { isActive }.map {
            log.info { "Stream ${streamProps.id} got heartbeat" }
            Instant.now()
        }.stateIn(this)
        while (isActive && heartbeatState.value.isAfter(Instant.now().minus(30.seconds.toJavaDuration()))) {
            log.info { "Stream ${streamProps.id} last heartbeat ${heartbeatState.value}" }
            delay(1.seconds)
        }
        log.info { "Killing ${streamProps.id} due to failure to heartbeat" }
        job.cancel()
    }

    try {
        log.info { "Started stream ${streamProps.id}" }
        job.join()
    } catch (e: CancellationException) {
        job.invokeOnCompletion {
            runBlocking {
                cache.getStreamMetadata(streamProps.id)?.streamMetadata?.segments?.forEach {
                    cache.delSegmentM4s(streamProps.id, it.index)
                }
                cache.delStreamMetadata(streamProps.id)
                cache.delInitMp4(streamProps.id)
            }
        }
    } catch (e: Exception) {
        log.error(e) { "Error waiting for stream job to finish" }
    } finally {
        if (!job.isCompleted) {
            job.cancel()
        }
        if (!subtitleJob.isCompleted) {
            subtitleJob.cancel()
        }

        log.info { "Cancelling heartbeat" }
        heartbeat.cancel()
        log.info { "Canceled heartbeat" }
    }
}

val ServerVideoTrack.key: String
    get() = "-c:v:${this.index}"
val ServerAudioTrack.key: String
    get() = "-c:a:${this.index}"

fun StreamProps.mkVideoTrackProps(): List<String> =
    videoStreamName?.let {
        mediaInfo.videoTracks[videoStreamName]?.let { videoTrack ->
            // TODO Known good settings. Support copy when codec is compatible
            // See https://cconcolato.github.io/media-mime-support/? for compatibility
            listOf(
                videoTrack.key,
                "h264",
                "-preset",
                "medium",
                "-crf",
                "28",
                "-pix_fmt",
                "yuv420p"
            )
        }
    } ?: listOf(
        "-vf",
        "drawbox=color=black:t=fill",
        "video_size",
        "1x1"
    )

fun StreamProps.mkAudioTrackProps(): List<String> =
    audioStreamName?.let {
        mediaInfo.audioTracks[audioStreamName]?.let { audioTrack ->
            when (audioTrack.codecName?.lowercase()) {
                "aac",
                "ac3",
                "vorbis",
                "mp3" -> listOf(audioTrack.key, "copy")

                else -> listOf(audioTrack.key, "aac")
            }
        }
    } ?: listOf(
        "-vf",
        "drawbox=color=black:t=fill",
        "video_size",
        "1x1"
    )

fun FileLocation.toFfmpegUri() = when (this) {
    is FileLocation.LocalFile -> path
}

fun String.parseDuration() = this.trim().split(":").let {
    try {
        when (it.size) {
            3 -> it[0].toInt().hours + it[1].toInt().minutes + it[2].toDouble().seconds
            2 -> it[0].toInt().minutes + it[1].toDouble().seconds
            else -> null
        }
    } catch (e: Exception) {
        log.error(e) { "Failed to parse duration from '$this'" }
        null
    }
}

private fun Long.leftPad(length: Int = 2) =
    toString().let { (if (it.length < length) "0".repeat(length - it.length) else "") + it }

private fun Long.rightPad(length: Int = 3) =
    toString().let { it + (if (it.length < length) "0".repeat(length - it.length) else "") }