val kotlin_version: String by project
val kotlinx_serialization_version: String by project
val kotlinx_coroutines_version: String by project
val logback_version: String by project
val lettuce_version: String by project
val exposed_version: String by project
val postgres_driver_version: String by project

plugins {
    kotlin("jvm") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
}

group = "io.rewynd"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":client"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.postgresql:postgresql:$postgres_driver_version")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("io.arrow-kt:arrow-core:1.2.0-RC")
    implementation("io.arrow-kt:arrow-fx-coroutines:1.2.0-RC")

    implementation("io.lettuce:lettuce-core:$lettuce_version")

    implementation("com.typesafe:config:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$kotlinx_coroutines_version")
}
