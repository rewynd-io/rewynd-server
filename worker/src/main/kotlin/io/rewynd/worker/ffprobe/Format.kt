package io.rewynd.worker.ffprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Format(
    @SerialName("filename") var filename: String? = null,
    @SerialName("nb_streams") var nbStreams: Int? = null,
    @SerialName("nb_programs") var nbPrograms: Int? = null,
    @SerialName("format_name") var formatName: String? = null,
    @SerialName("format_long_name") var formatLongName: String? = null,
    @SerialName("start_time") var startTime: Double? = null,
    @SerialName("duration") var duration: Double? = null,
    @SerialName("size") var size: Long? = null,
    @SerialName("bit_rate") var bitRate: Long? = null,
    @SerialName("probe_score") var probeScore: Int? = null,
    @SerialName("tags") var tags: Tags? = Tags()
)