package io.rewynd.worker

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.experimental.and
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val FTYP_BYTE_ARR = byteArrayOf(0x66, 0x74, 0x79, 0x70) // ftyp
val MOOV_BYTE_ARR = byteArrayOf(0x6d, 0x6f, 0x6f, 0x76) // moov
val MDHD_BYTE_ARR = byteArrayOf(0x6d, 0x64, 0x68, 0x64) // mdhd
val MOOF_BYTE_ARR = byteArrayOf(0x6d, 0x6f, 0x6f, 0x66) // moof
val MDAT_BYTE_ARR = byteArrayOf(0x6d, 0x64, 0x61, 0x74) // mdat
val TFHD_BYTE_ARR = byteArrayOf(0x74, 0x66, 0x68, 0x64) // tfhd
val TRUN_BYTE_ARR = byteArrayOf(0x74, 0x72, 0x75, 0x6e) // trun
val MFRA_BYTE_ARR = byteArrayOf(0x6d, 0x66, 0x72, 0x61) // mfra
val HVCC_BYTE_ARR = byteArrayOf(0x68, 0x76, 0x63, 0x43) // hvcC
val HEV1_BYTE_ARR = byteArrayOf(0x68, 0x65, 0x76, 0x31) // hev1
val HVC1_BYTE_ARR = byteArrayOf(0x68, 0x76, 0x63, 0x31) // hvc1
val AVCC_BYTE_ARR = byteArrayOf(0x61, 0x76, 0x63, 0x43) // avcC
val AVC1_BYTE_ARR = byteArrayOf(0x61, 0x76, 0x63, 0x31) // avc1
val AVC2_BYTE_ARR = byteArrayOf(0x61, 0x76, 0x63, 0x32) // avc2
val AVC3_BYTE_ARR = byteArrayOf(0x61, 0x76, 0x63, 0x33) // avc3
val AVC4_BYTE_ARR = byteArrayOf(0x61, 0x76, 0x63, 0x34) // avc4
val MP4A_BYTE_ARR = byteArrayOf(0x6d, 0x70, 0x34, 0x61) // mp4a
val ESDS_BYTE_ARR = byteArrayOf(0x65, 0x73, 0x64, 0x73) // esds
val FTYP = ByteBuffer.wrap(FTYP_BYTE_ARR)
val MOOV = ByteBuffer.wrap(MOOV_BYTE_ARR)
val MDHD = ByteBuffer.wrap(MDHD_BYTE_ARR)
val MOOF = ByteBuffer.wrap(MOOF_BYTE_ARR)
val MDAT = ByteBuffer.wrap(MDAT_BYTE_ARR)
val TFHD = ByteBuffer.wrap(TFHD_BYTE_ARR)
val TRUN = ByteBuffer.wrap(TRUN_BYTE_ARR)
val MFRA = ByteBuffer.wrap(MFRA_BYTE_ARR)
val HVCC = ByteBuffer.wrap(HVCC_BYTE_ARR)
val HEV1 = ByteBuffer.wrap(HEV1_BYTE_ARR)
val HVC1 = ByteBuffer.wrap(HVC1_BYTE_ARR)
val AVCC = ByteBuffer.wrap(AVCC_BYTE_ARR)
val AVC1 = ByteBuffer.wrap(AVC1_BYTE_ARR)
val AVC2 = ByteBuffer.wrap(AVC2_BYTE_ARR)
val AVC3 = ByteBuffer.wrap(AVC3_BYTE_ARR)
val AVC4 = ByteBuffer.wrap(AVC4_BYTE_ARR)
val MP4A = ByteBuffer.wrap(MP4A_BYTE_ARR)
val ESDS = ByteBuffer.wrap(ESDS_BYTE_ARR)
val _HLS_INIT_DEF = true; // hls playlist available after initialization and before 1st segment
val _HLS_SIZE_DEF = 4; // hls playlist size default
val _HLS_SIZE_MIN = 2; // hls playlist size minimum
val _HLS_SIZE_MAX = 20; // hls playlist size maximum
val _HLS_EXTRA_DEF = 0; // hls playlist extra segments in memory default
val _HLS_EXTRA_MIN = 0; // hls playlist extra segments in memory minimum
val _HLS_EXTRA_MAX = 10; // hls playlist extra segments in memory maximum
val _SEG_SIZE_DEF = 2; // segment list size default
val _SEG_SIZE_MIN = 2; // segment list size minimum
val _SEG_SIZE_MAX = 30; // segment list size maximum
val _MOOF_SEARCH_LIMIT = 50; // number of allowed attempts to find missing moof atom

sealed interface Event {
    data class Segment(val duration: Duration, val data: ByteArray) : Event
    data class Init(val data: ByteArray, val videoCodec: String?, val audioCodec: String?) : Event
    data class Error(val reason: String) : Event

}

sealed interface Box {
    val data: ByteArray

    class Ftyp(override val data: ByteArray) : Box
    class Moov(override val data: ByteArray) : Box
    class Mdhd(override val data: ByteArray) : Box
    class Moof(override val data: ByteArray) : Box
    class Mdat(override val data: ByteArray) : Box
    class Mfra(override val data: ByteArray) : Box
}

/**
 * @file
 * <ul>
 * <li>Creates a stream transform for piping a fmp4 (fragmented mp4) from ffmpeg.</li>
 * <li>Can be used to generate a fmp4 m3u8 HLS playlist and compatible file fragments.</li>
 * <li>Can be used for storing past segments of the mp4 video in a buffer for later access.</li>
 * <li>Must use the following ffmpeg args <b><i>-movflags +frag_keyframe+empty_moov+default_base_moof</i></b> to generate
 * a valid fmp4 with a compatible file structure : ftyp+moov -> moof+mdat -> moof+mdat -> moof+mdat ...</li>
 * </ul>
 * @extends stream.Transform
 */

class Mp4Frag(val inputStream: InputStream) {
    /**
     * @constructor
     * @param {object} [options] - Configuration options.
     * @param {boolean} [options.readableObjectMode = false] - If true, segments will be piped out as an object instead of a Buffer.
     * @param {string} [options.hlsPlaylistBase] - Base name of files in m3u8 playlist. Affects the generated m3u8 playlist by naming file fragments. Must be set to generate m3u8 playlist. e.g. 'front_door'
     * @param {number} [options.hlsPlaylistSize = 4] - Number of segments to use in m3u8 playlist. Must be an integer ranging from 2 to 20.
     * @param {number} [options.hlsPlaylistExtra = 0] - Number of extra segments to keep in memory. Must be an integer ranging from 0 to 10.
     * @param {boolean} [options.hlsPlaylistInit = true] - Indicates that m3u8 playlist should be generated after [initialization]{@link Mp4Frag#initialization} is created and before media segments are created.
     * @param {number} [options.segmentCount = 2] - Number of segments to keep in memory. Has no effect if using options.hlsPlaylistBase. Must be an integer ranging from 2 to 30.
     * @throws Will throw an error if options.hlsPlaylistBase contains characters other than letters(a-zA-Z) and underscores(_).
     */
    val buffer: BufferedInputStream = inputStream.buffered()
//    var audioCodec: String? = null
//    var videoCodec: String? = null
//    var initMp4: ByteArray? = null
//    var segments = mutableListOf<Segment>()

    suspend fun read() = flow {
        try {
            val ftyp = parseBox().let {
                require(it is Box.Ftyp) { "Unexpected Box $it" }
                log.info { "Found Ftype" }
                it
            }
            val moov = parseBox().let {
                require(it is Box.Moov) { "Unexpected Box $it" }
                log.info { "Found MOOV" }
                it
            }
            val initData = ftyp.data + moov.data
            val initEvent = Event.Init(
                data = initData,
                videoCodec = initData.videoCodec,
                audioCodec = initData.audioCodec
            )
            val timeScale = requireNotNull(initEvent.data.parseTimeScale()) { "Failed to extract timescale" }
            emit(initEvent)
            do {
                val box1 = parseBox()
                val box2 = parseBox()
                if (box1 is Box.Moof && box2 is Box.Mdat) {
                    val data = box1.data + box2.data
                    emit(Event.Segment(data.extractDuration(timeScale), data))
                } else if (box1 !is Box.Mfra) {
                    log.error { "Unexpected boxes $box1, $box2" }
                }
            } while (currentCoroutineContext().isActive && box1 !is Box.Mfra && box2 !is Box.Mfra)
        } catch (e: Exception) {
            log.error(e) { "Error when fragmenting MP4" }
        }
    }

    private fun readNextByteArray(): ByteArray? {
        val header = buffer.readNBytes(8)
        if (header.size < 8) {
            return null
        }
        val sizeBytes = header.slice(IntRange(0, 3)).toByteArray()
        val typeBytes = header.slice(IntRange(4, 7)).toByteArray()
        val len = ByteBuffer.wrap(sizeBytes).getInt() - 8
        val arr = ByteArray(len + 8)
        sizeBytes.copyInto(arr)
        typeBytes.copyInto(arr, 4)
        if (buffer.readNBytes(arr, 8, len) != len) {
            throw RuntimeException("Could not read $len bytes for box.")
        }
        return arr
    }

    private fun parseBox(bytes: ByteArray? = readNextByteArray()) = bytes?.let {
        when (val typeBytes = bytes.slice(IntRange(4, 7)).toByteArray().let(ByteBuffer::wrap)) {
            null -> null
            FTYP -> Box.Ftyp(bytes)
            MOOV -> Box.Moov(bytes)
            MDHD -> Box.Mdhd(bytes)
            MOOF -> Box.Moof(bytes)
            MDAT -> Box.Mdat(bytes)
            MFRA -> Box.Mfra(bytes)
            else -> {
                throw RuntimeException("Unknown box type ${String(typeBytes.array())}")
            }
        }
    }

}

private fun ByteArray.parseTimeScale(): Int? = indexOf(MDHD_BYTE_ARR)?.let {
    val version = get(it + 4);
    val offset = if (version == 0.toByte()) 16 else 24
    ByteBuffer.wrap(this).getInt(it + offset)
}

private fun Box.Moov.parseVideoCodecs() = data.indexOf(AVCC_BYTE_ARR)?.let { index ->
    val avcList = listOfNotNull(
        data.indexOf(AVC1_BYTE_ARR)?.let { "avc1" },
        data.indexOf(AVC2_BYTE_ARR)?.let { "avc2" },
        data.indexOf(AVC3_BYTE_ARR)?.let { "avc3" },
        data.indexOf(AVC4_BYTE_ARR)?.let { "avc4" }
    )
    if (avcList.isEmpty()) {
        emptyList<String>()
    } else {
        avcList + listOf(data.slice(IntRange(index + 5, index + 7)))
    }
} ?: emptyList<String>()

fun ByteArray.indexOf(arr: ByteArray): Int? {
    (0 until this.size - arr.size).forEach { thisIndex ->
        if ((0 until arr.size).all { otherIndex ->
                arr[otherIndex] == this[thisIndex + otherIndex]
            }) return thisIndex
    }
    return null
}

fun ByteArray.extractDuration(timescale: Int) = (indexOf(TRUN_BYTE_ARR)?.let { trunIndex ->
    val wrapper = ByteBuffer.wrap(this)
    var trunOffset = trunIndex + 4
    val trunFlags = wrapper.getInt(trunOffset)
    trunOffset += 4
    val sampleCount = wrapper.getInt(trunOffset)
    if (trunFlags and 0x000100 != 0) {
        trunOffset += 4
        if (trunFlags and 0x000001 != 0) {
            trunOffset += 4
            if (trunFlags and 0x000004 != 0) {
                trunOffset += 4
            }
        }
        val increment = 4 +
                (if (trunFlags and 0x000200 != 0) 4 else 0) +
                (if (trunFlags and 0x000400 != 0) 4 else 0) +
                (if (trunFlags and 0x000800 != 0) 4 else 0)

        (0 until sampleCount)
            .fold(0.0 to trunOffset) { acc, i -> (acc.first + wrapper.getInt(acc.second)) to acc.second + increment }.first.toDouble() / timescale.toDouble()
    } else {
        indexOf(TFHD_BYTE_ARR)?.let {
            var tfhdOffset = it + 4
            val tfhdFlags = wrapper.getInt(tfhdOffset)
            if (tfhdFlags and 0x000008 != 0) {
                tfhdOffset += 8
                if (tfhdFlags and 0x000001 != 0) {
                    tfhdOffset += 8
                    if (tfhdFlags and 0x000002 != 0) {
                        tfhdOffset += 4
                    }
                }
                wrapper.getInt(tfhdOffset).toDouble() * sampleCount.toDouble() / timescale.toDouble()
            } else 0.0
        } ?: 0.0
    }
} ?: 0.0).seconds


val ByteArray.audioCodec: String?
    get() {
        val mp4aIndex = indexOf(MP4A_BYTE_ARR)
        return if (mp4aIndex != null) {
            val audioCodecBuilder = StringBuilder()
            audioCodecBuilder.append("mp4a") //.40.2
            val esdsIndex = indexOf(ESDS_BYTE_ARR)
            if (esdsIndex !== null && this[esdsIndex + 8].toInt() == 0x03 && this[esdsIndex + 16].toInt() == 0x04 && this[esdsIndex + 34].toInt() == 0x05) {
                audioCodecBuilder.append(".")
                audioCodecBuilder.append(this[esdsIndex + 21].toString(16));
                audioCodecBuilder.append(".")
                audioCodecBuilder.append(((this[esdsIndex + 39] and 0xf8.toByte()).toInt() shr 3).toString());
            }
            audioCodecBuilder.toString()
        } else null
    }

val ByteArray.videoCodec: String?
    get() {
        val avccIndex = indexOf(AVCC_BYTE_ARR)
        return if (avccIndex != null) {
            val version = if (indexOf(AVC1_BYTE_ARR) != null) {
                "avc1"
            } else if (indexOf(AVC1_BYTE_ARR) != null) {
                "avc2"
            } else if (indexOf(AVC2_BYTE_ARR) != null) {
                "avc3"
            } else if (indexOf(AVC3_BYTE_ARR) != null) {
                "avc4"
            } else {
                null
            }
            val profile = HexFormat.of().formatHex(slice(IntRange(avccIndex + 5, avccIndex + 7)).toByteArray())

            version?.let {
                "$it.$profile"
            }
        } else {
            val hvccIndex = indexOf(HVCC_BYTE_ARR)
            if (hvccIndex != null) {
                val version = if (indexOf(AVC1_BYTE_ARR) != null) {
                    "hvc1"
                } else if (indexOf(AVC1_BYTE_ARR) != null) {
                    "hev1"
                } else {
                    null
                }

                val tmpByte = this[hvccIndex + 5].toInt()
                val generalProfileSpace =
                    when ((tmpByte and 192) shr 6) {
                        1 -> "A"
                        2 -> "B"
                        3 -> "C"
                        else -> ""
                    } // get 1st 2 bits (11000000) and shift them to the least significant two digits
                val generalTierFlag =
                    if ((tmpByte and 32) == 32) "H" else "L" // get next bit (00100000) and map it high/low
                val generalProfileIdc = tmpByte and 31; // get last 5 bits (00011111)
                val generalProfileCompatibility = ByteBuffer.wrap(this).getInt(hvccIndex + 6).reverse().toString(16)
                val generalConstraintIndicator =
                    ByteBuffer.wrap(this.slice(IntRange(hvccIndex + 10, hvccIndex + 15)).filter { it.toInt() != 0 }
                        .toByteArray()).getInt().toString(16)
                val generalLevelIdc = this[hvccIndex + 16].toInt()
                return listOfNotNull(
                    version,
                    "$generalProfileSpace$generalProfileIdc",
                    generalProfileCompatibility,
                    "$generalTierFlag$generalLevelIdc",
                    generalConstraintIndicator.ifEmpty { null }
                ).joinToString(".")
            } else null
        }
    }

fun Int.reverse(): Int {
    val a = this and 0x55555555 shl 1 or (this and 0x55555555 shr 1)
    val b = a and 0x33333333 shl 2 or (a and 0x33333333 shr 2)
    val c = b and 0x0f0f0f0f shl 4 or (b and 0x0f0f0f0f shr 4)
    val d = c and 0x00ff00ff shl 8 or (c and 0x00ff00ff shr 8)
    return d shl 16 or (d shr 16)
}
