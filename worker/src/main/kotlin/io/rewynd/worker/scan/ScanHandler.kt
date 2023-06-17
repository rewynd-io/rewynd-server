package io.rewynd.worker.scan

import io.rewynd.model.LibraryType
import io.rewynd.common.ScanJobHandler
import io.rewynd.common.database.Database

fun mkScanJobHandler(db: Database): ScanJobHandler = { context ->
    val lib = context.request
    when(lib.type) {
        LibraryType.show -> ShowScanner(lib, db).scan()
        LibraryType.movie -> TODO()
        LibraryType.image -> TODO()
    }
}