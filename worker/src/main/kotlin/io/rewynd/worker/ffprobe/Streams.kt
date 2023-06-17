package io.rewynd.worker.ffprobe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Streams(
    @SerialName("id") var id: String? = null,
    @SerialName("index") var index: Int,
    @SerialName("codec_name") var codecName: String? = null,
    @SerialName("codec_long_name") var codecLongName: String? = null,
    @SerialName("profile") var profile: String? = null,
    @SerialName("codec_type") var codecType: String? = null,
    @SerialName("codec_tag_string") var codecTagString: String? = null,
    @SerialName("codec_tag") var codecTag: String? = null,
    @SerialName("width") var width: Int? = null,
    @SerialName("height") var height: Int? = null,
    @SerialName("coded_width") var codedWidth: Int? = null,
    @SerialName("coded_height") var codedHeight: Int? = null,
    @SerialName("closed_captions") var closedCaptions: Int? = null,
    @SerialName("film_grain") var filmGrain: Int? = null,
    @SerialName("has_b_frames") var hasBFrames: Int? = null,
    @SerialName("sample_aspect_ratio") var sampleAspectRatio: String? = null,
    @SerialName("display_aspect_ratio") var displayAspectRatio: String? = null,
    @SerialName("pix_fmt") var pixFmt: String? = null,
    @SerialName("level") var level: Int? = null,
    @SerialName("color_range") var colorRange: String? = null,
    @SerialName("chroma_location") var chromaLocation: String? = null,
    @SerialName("refs") var refs: Int? = null,
    @SerialName("r_frame_rate") var rFrameRate: String? = null,
    @SerialName("avg_frame_rate") var avgFrameRate: String? = null,
    @SerialName("time_base") var timeBase: String? = null,
    @SerialName("start_pts") var startPts: Int? = null,
    @SerialName("duration") var duration: Double? = null,
    @SerialName("duration_ts") var durationTs: Double? = null,
    @SerialName("start_time") var startTime: Double? = null,
    @SerialName("extradata_size") var extradataSize: Long? = null,
    @SerialName("disposition") var disposition: Disposition? = Disposition(),
    @SerialName("tags") var tags: Tags? = Tags()

) {
}