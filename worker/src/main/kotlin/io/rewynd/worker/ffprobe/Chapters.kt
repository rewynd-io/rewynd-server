package io.rewynd.worker.ffprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Chapters(
    @SerialName("id") var id: Long? = null,
    @SerialName("time_base") var timeBase: String? = null,
    @SerialName("start") var start: Double? = null,
    @SerialName("start_time") var startTime: Double? = null,
    @SerialName("end") var end: Double? = null,
    @SerialName("end_time") var endTime: Double? = null,
    @SerialName("tags") var tags: Tags? = Tags()
)