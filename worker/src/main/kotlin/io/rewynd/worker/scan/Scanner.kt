package io.rewynd.worker.scan

interface Scanner {
    suspend fun scan()
}