package io.rewynd.api.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.rewynd.common.database.Database

fun Route.seasonRoutes(db: Database) {
    get("/season/list/{showId}") {
        call.parameters["showId"]?.let { showId ->
            call.respond(db.listSeasons(showId).map { it.seasonInfo })
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/season/get/{seasonId}") {
        val season = call.parameters["seasonId"]?.let { db.getSeason(it) }?.seasonInfo
        if (season == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(season)
        }
    }
}

