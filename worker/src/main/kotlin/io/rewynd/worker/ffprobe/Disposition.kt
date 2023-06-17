package io.rewynd.worker.ffprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Disposition(
    @SerialName("default") var default: Int? = null,
    @SerialName("dub") var dub: Int? = null,
    @SerialName("original") var original: Int? = null,
    @SerialName("comment") var comment: Int? = null,
    @SerialName("lyrics") var lyrics: Int? = null,
    @SerialName("karaoke") var karaoke: Int? = null,
    @SerialName("forced") var forced: Int? = null,
    @SerialName("hearing_impaired") var hearingImpaired: Int? = null,
    @SerialName("visual_impaired") var visualImpaired: Int? = null,
    @SerialName("clean_effects") var cleanEffects: Int? = null,
    @SerialName("attached_pic") var attachedPic: Int? = null,
    @SerialName("timed_thumbnails") var timedThumbnails: Int? = null,
    @SerialName("captions") var captions: Int? = null,
    @SerialName("descriptions") var descriptions: Int? = null,
    @SerialName("metadata") var metadata: Int? = null,
    @SerialName("dependent") var dependent: Int? = null,
    @SerialName("still_image") var stillImage: Int? = null
)