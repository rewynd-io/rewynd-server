package io.rewynd.api.controller

import io.rewynd.model.LoginRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.rewynd.api.UserSession
import io.rewynd.common.database.Database
import io.rewynd.common.decoder
import io.rewynd.common.hashPassword
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*

fun Route.authRoutes(db: Database) {
    route("/auth") {
        get("/verify") {
            val session = this.context.sessions.get<UserSession>()
            val user = session?.let { db.getUser(it.username) }
            if (user != null) {
                this.context.response.status(HttpStatusCode.OK)
                call.respond(user.user)
            } else {
                this.context.response.status(
                    HttpStatusCode.BadRequest
                )
            }
        }
        post("/logout") {
            this.context.sessions.clear<UserSession>()
            call.respond(HttpStatusCode.OK)
        }
        post("/login") {
            val request = call.receive<LoginRequest>()
            val username = request.username
            val user = username?.let { db.getUser(it) }
            val password = request.password
            call.respond(
                if (user != null && password != null) {
                    val hashedPass = hashPassword(password, user.salt)
                    if (MessageDigest.isEqual(decoder.decode(hashedPass), decoder.decode(user.hashedPass))) {
                        this.context.sessions.set<UserSession>(UserSession(generateSessionId(), username))
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.Forbidden
                    }
                } else {
                    HttpStatusCode.BadRequest
                }
            )
        }
    }
}

private val encoder by lazy { Base64.getEncoder() }
private val random by lazy { SecureRandom() }
fun generateSessionId(): String {
    val arr = ByteArray(1024)
    random.nextBytes(arr)
    return encoder.encodeToString(arr)
}