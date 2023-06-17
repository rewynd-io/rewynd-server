package io.rewynd.common

import mu.KLogger
import mu.KLogging
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


val encoder by lazy { Base64.getUrlEncoder() }
val decoder by lazy { Base64.getUrlDecoder() }
private val factory by lazy { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") }
private val random by lazy { SecureRandom() }

fun generateSalt(): String {
    val salt = ByteArray(256)
    random.nextBytes(salt)
    return encoder.encodeToString(salt)
}

fun hashPassword(password: String, salt: String): String {
    val spec: KeySpec = PBEKeySpec(password.toCharArray(), decoder.decode(salt), 65536, 512)
    return encoder.encodeToString(factory.generateSecret(spec).encoded)
}

open class KLog : KLogging() {
    val log: KLogger
        get() = logger
}

fun md5(input:String): String {
    val md = MessageDigest.getInstance("MD5")
    return encoder.encodeToString(md.digest(input.toByteArray()))
}

fun Map<String, ServerVideoTrack>.toVideoTracks() = mapValues { it.value.toVideoTrack() }
fun Map<String, ServerAudioTrack>.toAudioTracks() = mapValues { it.value.toAudioTrack() }
fun Map<String, ServerSubtitleTrack>.toSubtitleTracks() = mapValues { it.value.toSubtitleTrack() }
