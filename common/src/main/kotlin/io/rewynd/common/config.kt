package io.rewynd.common

import com.typesafe.config.Config
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.lettuce.core.RedisURI
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes


sealed interface DatabaseConfig {
    val url
        get() = when (this) {
            is PostgresConfig -> "jdbc:postgresql://$hostname:$port/$database"
        }

    val driver
        get() = when (this) {
            is PostgresConfig -> "org.postgresql.Driver"
        }

    data class PostgresConfig(
        val hostname: String,
        val username: String,
        val password: String,
        val port: Int,
        val database: String
    ) : DatabaseConfig {
        val datasource: DataSource
            get() {
                val config = HikariConfig()
                config.jdbcUrl = url
                config.username = username
                config.password = password
                config.keepaliveTime = 5.minutes.inWholeMilliseconds
                config.leakDetectionThreshold = 5.minutes.inWholeMilliseconds
                return HikariDataSource(config)
            }

        companion object {
            fun fromConfig(config: Config) =
                if (
                    config.hasPath("postgres") &&
                    config.hasPath("postgres.hostname") &&
                    config.hasPath("postgres.username") &&
                    config.hasPath("postgres.password") &&
                    config.hasPath("postgres.port") &&
                    config.hasPath("postgres.database")
                ) {
                    with(config.getConfig("postgres")) {
                        PostgresConfig(
                            hostname = getString("hostname"),
                            username = getString("username"),
                            password = getString("password"),
                            port = getInt("port"),
                            database = getString("database")
                        )
                    }
                } else null
        }
    }

    companion object {
        fun fromConfig(config: Config) =
            requireNotNull(PostgresConfig.fromConfig(config)) { "No database configured" }
    }
}


sealed interface CacheConfig {

    data class RedisConfig(
        val uri: RedisURI
    ) : CacheConfig {


        companion object {
            fun fromConfig(config: Config) =
                if (
                    config.hasPath("redis") &&
                    config.hasPath("redis.hostname") &&
                    config.hasPath("redis.port")
                ) {
                    with(config.getConfig("redis")) {
                        RedisConfig(
                            RedisURI.create(getString("hostname"), getInt("port"))
                        )
                    }
                } else null
        }
    }

    data class RedisClusterConfig(
        val uris: List<RedisURI>,
    ) : CacheConfig {


        companion object {
            fun fromConfig(config: Config) = if (
                config.hasPath("redis-cluster") &&
                config.hasPath("redis-cluster.hosts")
            ) {
                with(config) {
                    RedisClusterConfig(
                        uris = getString("hosts").split(",").mapNotNull {
                            val split = it.split(":")
                            if (split.isEmpty() || split.size > 2) {
                                log.warn { "Invalid host:port combination: $it" }
                                null
                            } else {
                                val port = split.getOrNull(1)?.toIntOrNull() ?: 6379
                                RedisURI.create(split[0], port)
                            }
                        }
                    )
                }
            } else null
        }
    }

    companion object : KLog() {
        fun fromConfig(config: Config) =
            requireNotNull(
                RedisConfig.fromConfig(config) ?: RedisClusterConfig.fromConfig(config)
            ) { "No cache configured" }


    }
}
