package io.rewynd.api.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.rewynd.api.UserSession
import io.rewynd.common.UserProgress
import io.rewynd.common.database.Database
import io.rewynd.model.ListProgressRequest
import io.rewynd.model.ListProgressResponse
import io.rewynd.model.Progress
import kotlinx.datetime.Clock

fun Route.progressRoutes(db: Database) {
    get("/user/progress/get/{id}") {
        call.parameters["id"]?.let { mediaId ->
            call.sessions.get<UserSession>()?.username?.let { username ->
                call.respond(
                    db.getProgress(mediaId, username)?.progress ?: Progress(
                        mediaId,
                        0.0,
                        Clock.System.now().toEpochMilliseconds().toDouble()
                    )
                )
            } ?: call.respond(HttpStatusCode.Forbidden)
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    post("/user/progress/list") {
        call.sessions.get<UserSession>()?.username?.let { username ->
            val req: ListProgressRequest = call.receive()
            val res = db.listRecentProgress(
                username = username,
                cursor = req.cursor,
                minPercent = req.minPercent ?: 0.0,
                maxPercent = req.maxPercent ?: 1.0
            ).map { it.progress }
            call.respond(
                ListProgressResponse(results = res) // TODO return a cursor
            )
        } ?: call.respond(HttpStatusCode.Forbidden)
    }
    post("/user/progress/put") {
        call.sessions.get<UserSession>()?.username?.let { username ->
            val req: Progress = call.receive()
            db.upsertProgress(UserProgress(username, req))
            call.respond(HttpStatusCode.OK)
        }
    }
}

