package io.rewynd.worker.ffprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Tags(
    @SerialName("encoder") var encoder: String? = null,
    @SerialName("creation_time") var creationTime: String? = null,
    @SerialName("language") var language: String? = null
)