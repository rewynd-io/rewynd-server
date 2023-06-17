package io.rewynd.worker.ffprobe

import io.rewynd.worker.execToString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File


@Serializable
data class FfprobeResult(
    @SerialName("streams") var streams: List<Streams> = emptyList(),
    @SerialName("chapters") var chapters: List<Chapters> = emptyList(),
    @SerialName("format") var format: Format? = Format()
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun parseFile(file: File) =
            json.decodeFromString<FfprobeResult>(
            listOf(
                "ffprobe",
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-show_error",
                "-show_format",
                "-show_streams",
                "-show_chapters",
                "${file.absolutePath}"
            ).execToString()
        )

    }
}