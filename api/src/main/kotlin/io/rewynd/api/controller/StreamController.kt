package io.rewynd.api.controller

import arrow.fx.coroutines.parMapUnordered
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.rewynd.api.UserSession
import io.rewynd.common.*
import io.rewynd.common.cache.Cache
import io.rewynd.common.cache.withLock
import io.rewynd.common.database.Database
import io.rewynd.model.CreateStreamRequest
import io.rewynd.model.HlsStreamProps
import io.rewynd.model.LibraryType
import io.rewynd.model.StreamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import java.util.*
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
fun Route.streamRoutes(db: Database, cache: Cache, queue: StreamJobQueue) {
    get("/stream/{streamId}/{segmentId}.m4s") {
        call.parameters["streamId"]?.let { streamId ->
            call.parameters["segmentId"]?.toIntOrNull()?.let { segmentId ->
                cache.getSegmentM4s(streamId, segmentId)?.let {
                    call.respondBytes(it, ContentType("video", "mp4"))
                }
            }
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/stream/{streamId}/{segmentId}.vtt") {
        call.parameters["streamId"]?.let { streamId ->
            call.parameters["segmentId"]?.toIntOrNull()?.let { segmentId ->
                cache.getStreamMetadata(streamId)?.streamMetadata?.subtitles?.segments?.get(segmentId)
                    ?.let { call.respondText(it.content) }
            }
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/stream/{streamId}/stream.m3u8") {
        call.parameters["streamId"]?.let { streamId ->
            cache.getStreamMetadata(streamId)?.streamMetadata?.let {
                call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                call.respondText(
                    it.segments.fold(
                        StringBuilder(
                            """
                        #EXTM3U
                        #EXT-X-VERSION:7
                        #EXT-X-PLAYLIST-TYPE:EVENT
                        #EXT-X-TARGETDURATION:${it.segments.maxBy { segment -> segment.duration }.duration.inWholeSeconds}
                        #EXT-X-MEDIA-SEQUENCE:0
                        #EXT-X-MAP:URI="init-stream.mp4"
                        
                    """.trimIndent()
                        )
                    ) { acc, seg ->
                        acc.apply {
                            append("#EXTINF:${seg.duration.inWholeMicroseconds.toDouble() / 1000000.0},\n")
                            append("${seg.index}.m4s\n")
                        }
                    }.apply {
                        if (it.complete) {
                            append("#EXT-X-ENDLIST\n")
                        }
                    }.toString(), ContentType.defaultForFileExtension("m3u8")
                )
            }
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/stream/{streamId}/index.m3u8") {
        call.parameters["streamId"]?.let { streamId ->
            cache.getStreamMetadata(streamId)?.streamMetadata?.let {
                val builder = StringBuilder().append("#EXTM3U\n")
                it.subtitles?.let {
                    builder.append("#EXT-X-MEDIA:TYPE=SUBTITLES,").append("""GROUP-ID="subs",""")
                        .append("""CHARACTERISTICS="public.accessibility.transcribes-spoken-dialog",""")
                        .append("""NAME="English",""").append("""AUTOSELECT=YES,""").append("""AUTOSELECT=YES,""")
                        .append("""DEFAULT=YES,""").append("""FORCED=YES,""").append("""LANGUAGE="en-US",""")
                        .append("""URI="subs.m3u8"""")
                        .append("\n")
                }
                builder.append("#EXT-X-STREAM-INF:").append("BANDWIDTH=1924009,")// TODO actual correct bandwidth
                    .append("""CODECS="${(it.mime.codecs + listOfNotNull(it.subtitles?.let { "wvtt" })).joinToString(", ")}"""")

                it.subtitles?.let {
                    builder.append(""",SUBTITLES="subs"""")
                }
                builder.append("\n").append("""/api/stream/${streamId}/stream.m3u8""")
                call.respondText(ContentType.defaultForFileExtension("m3u8")) { builder.toString() }
            } ?: call.respond(HttpStatusCode.BadRequest)
        }
    }
    get("/stream/{streamId}/subs.m3u8") {
        call.parameters["streamId"]?.let { streamId ->
            cache.getStreamMetadata(streamId)?.streamMetadata?.subtitles?.let { subtitleMetadata ->
                val builder = StringBuilder().append("#EXTM3U\n").append("#EXT-X-VERSION:7\n")
                    .append("#EXT-X-TARGETDURATION:${subtitleMetadata.segments.maxBy { it.duration.inWholeMilliseconds }.duration.inWholeMilliseconds.toDouble() / 1000.0}\n")
                    .append("#EXT-X-MEDIA-SEQUENCE:0\n")

                subtitleMetadata.segments.forEachIndexed { index, seg ->
                    builder.append("#EXTINF:${seg.duration.inWholeMilliseconds.toDouble() / 1000.0},\n${index}.vtt\n")
                }

                if (subtitleMetadata.complete) {
                    builder.append("#EXT-X-ENDLIST\n")
                }
                call.respondText(ContentType.defaultForFileExtension("m3u8")) { builder.toString() }
            }

        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/stream/{streamId}/init-stream.mp4") {
        call.parameters["streamId"]?.let { streamId ->
            cache.getInitMp4(streamId)?.let {
                call.respondBytes(it, ContentType("video", "mp4"))
            }
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    delete("/stream/delete/{streamId}") {
        call.parameters["streamId"]?.let { streamId ->
            call.sessionId<UserSession>()?.let { sessionId ->
                cache.getSessionStreamMapping(sessionId)?.let { streamMapping ->
                    deleteStream(queue, streamMapping, cache)
                    call.respond(HttpStatusCode.OK)
                } ?: call.respond(HttpStatusCode.NotFound)
            } ?: call.respond(HttpStatusCode.Forbidden)
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    post("/stream/create") {
        runBlocking {
            val req: CreateStreamRequest = call.receive()
            val id = UUID.randomUUID().toString()
            db.getLibrary(req.library)?.let { library ->
                call.sessionId<UserSession>()?.let { sessionId ->
                    when (library.type) {
                        LibraryType.show -> db.getEpisode(req.id)?.toServerMediaInfo()
                        LibraryType.movie -> TODO()
                        LibraryType.image -> TODO()
                    }?.let { serverMediaInfo ->
                        // Lock to prevent parallel creation of streams using the same session
                        withLock(cache, "Lock:Session:$sessionId:Stream", 10.seconds, 10.seconds) {
                            cache.getSessionStreamMapping(sessionId)?.let { streamMapping ->
                                deleteStream(queue, streamMapping, cache)
                            }

                            cache.putStreamMetadata(
                                id,
                                StreamMetadataWrapper(null),
                                (Clock.System.now() + 2.minutes).toJavaInstant()
                            )

                            val props = StreamProps(
                                id = id,
                                mediaInfo = serverMediaInfo,
                                audioStreamName = req.audioTrack,
                                videoStreamName = req.videoTrack,
                                subtitleStreamName = req.subtitleTrack,
                                startOffset = req.startOffset?.seconds ?: ZERO
                            )

                            val jobId = queue.submit(
                                props
                            )

                            cache.putSessionStreamMapping(
                                sessionId, StreamMapping(id, jobId), (Clock.System.now() + 1.hours).toJavaInstant()
                            )

                            call.respond(
                                HlsStreamProps(
                                    id = id,
                                    url = "/api/stream/$id/index.m3u8",
                                    startOffset = req.startOffset ?: 0.0,
                                    duration = serverMediaInfo.mediaInfo.runTime
                                )
                            )
                        }
                    }
                }
            }
        } ?: call.respond(HttpStatusCode.InternalServerError)
    }
    post("/stream/heartbeat/{streamId}") {
        val reqStreamId = requireNotNull(call.parameters["streamId"]) { "Missing StreamId" }
        val sessionId = call.sessionId<UserSession>()
        val metadata = cache.getStreamMetadata(reqStreamId)
        val streamMetadata = metadata?.streamMetadata
        if (metadata == null) {
            call.respond(StreamStatus.canceled)
        } else if (streamMetadata == null) {
            call.respond(StreamStatus.pending)
        } else {
            val expire = Clock.System.now().plus(2.minutes).toJavaInstant()
            streamMetadata.segments.asFlow().parMapUnordered {
                cache.expireSegmentM4s(
                    reqStreamId, it.index, expire
                )
            }.flowOn(Dispatchers.IO).collect {}
            cache.expireInitMp4(reqStreamId, expire)
            cache.expireStreamMetadata(reqStreamId, expire)
            sessionId?.let {
                cache.expireSessionStreamJobId(it, expire)
            }
            queue.notify(streamMetadata.jobId, ClientStreamEvents.Heartbeat)
            if (streamMetadata.segments.size > 0 && (streamMetadata.streamProps.subtitleStreamName == null || (streamMetadata.subtitles?.segments?.size
                    ?: 0) > 0)
            ) {
                call.respond(StreamStatus.available)
            } else {
                call.respond(StreamStatus.pending)
            }
        }


    }
}

private suspend fun deleteStream(
    queue: StreamJobQueue,
    streamMapping: StreamMapping,
    cache: Cache
) = with(streamMapping) {
    queue.cancel(jobId)
    cache.getStreamMetadata(streamId)?.streamMetadata?.segments?.forEach {
        cache.delSegmentM4s(streamId, it.index)
    }
    cache.delInitMp4(streamId)
    cache.delStreamMetadata(streamId)
    cache.delInitMp4(streamId)
}

