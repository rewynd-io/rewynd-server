package io.rewynd.api.controller

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.rewynd.common.ServerEpisodeInfo
import io.rewynd.common.database.Database
import java.text.Collator
import java.util.*

fun Route.episodeRoutes(db: Database) {
    get("/episode/list/{seasonId}") {
        call.parameters["seasonId"]?.let { seasonId ->
            call.respond(db.listEpisodes(seasonId).map { it.toEpisodeInfo() })
        } ?: call.respond(HttpStatusCode.BadRequest)
    }
    get("/episode/get/{episodeId}") {
        val episode = call.parameters["episodeId"]?.let { db.getEpisode(it) }?.toEpisodeInfo()
        if (episode == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(episode)
        }
    }

    // TODO actually implement these
    get("/episode/next/{episodeId}") {
        call.parameters["episodeId"]?.let { db.getEpisode(it) }?.let { serverEpisodeInfo ->
            (getNextEpisodeInSeason(db, serverEpisodeInfo) ?: getFirstEpisodeInNextSeason(db, serverEpisodeInfo))?.let {
                call.respond(it.toEpisodeInfo())
            }
        } ?: call.respond(HttpStatusCode.NotFound)
    }
    get("/episode/previous/{episodeId}") {
        call.parameters["episodeId"]?.let { db.getEpisode(it) }?.let { serverEpisodeInfo ->
            (getNextEpisodeInSeason(db, serverEpisodeInfo, true) ?: getFirstEpisodeInNextSeason(
                db,
                serverEpisodeInfo,
                true
            ))?.let {
                call.respond(it.toEpisodeInfo())
            }
        } ?: call.respond(HttpStatusCode.NotFound)
    }
}

private suspend fun getFirstEpisodeInNextSeason(
    db: Database,
    serverEpisodeInfo: ServerEpisodeInfo,
    reverse: Boolean = false
): ServerEpisodeInfo? =
    db.listSeasons(serverEpisodeInfo.showId)
        .sortedBy { it.seasonInfo.seasonNumber }
        .let { seasons ->
            seasons.getOrNull(
                seasons.indexOfFirst {
                    it.seasonInfo.seasonNumber == serverEpisodeInfo.season
                } + (if (reverse) -1 else 1)
            )
        }?.let { season -> db.listEpisodes(season.seasonInfo.id) }?.sort()
        ?.let { if (reverse) it.lastOrNull() else it.firstOrNull() }


private suspend fun getNextEpisodeInSeason(
    db: Database,
    serverEpisodeInfo: ServerEpisodeInfo,
    reverse: Boolean = false
) = db.listEpisodes(serverEpisodeInfo.seasonId).sort().let { seasonEpisodes ->
    seasonEpisodes.getOrNull(
        seasonEpisodes.indexOfFirst {
            it.id == serverEpisodeInfo.id
        } + (if (reverse) -1 else 1)
    )
}

fun List<ServerEpisodeInfo>.sort() = if (this.all { it.episode != null }) {
    this.sortedBy { it.episode }
} else {
    // TODO take Locale as an argument
    this.sortedWith(Collator.getInstance(Locale.US).apply { strength = Collator.PRIMARY })
}
