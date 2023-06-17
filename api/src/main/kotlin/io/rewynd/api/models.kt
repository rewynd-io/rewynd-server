package io.rewynd.api

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val id: String, val username: String)
