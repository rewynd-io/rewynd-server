package io.rewynd.api.controller

import io.rewynd.model.DeleteLibrariesRequest
import io.rewynd.model.Library
import io.rewynd.model.ScanLibrariesRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.rewynd.common.ScanJobQueue
import io.rewynd.common.database.Database
import io.rewynd.api.plugins.mkAdminAuthZPlugin

fun Route.libRoutes(db: Database, scanJobQueue: ScanJobQueue) {
    get("/lib/list") {
        call.respond(db.listLibraries())
    }
    get("/lib/get/{lib}") {
        val lib = call.parameters["lib"]?.let { db.getLibrary(it) }
        if (lib == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respond(lib)
        }
    }
    route("/lib/delete") {
        install(mkAdminAuthZPlugin(db))
        post {
            call.receive<DeleteLibrariesRequest>().libraries.forEach { db.deleteLibrary(it) }
            call.respond(HttpStatusCode.OK)
        }
    }

    route("/lib/create") {
        install(mkAdminAuthZPlugin(db))
        post {
            val lib = call.receive<Library>()
            db.upsertLibrary(lib)
            scanJobQueue.submit(lib)
            call.respond(HttpStatusCode.OK)
        }
    }
    route("/lib/scan") {
        install(mkAdminAuthZPlugin(db))
        post {
            call.receive<ScanLibrariesRequest>().libraryIds.forEach {
                db.getLibrary(it)?.let { lib ->
                    scanJobQueue.submit(lib)
                }
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}