import java.net.URI

val kotlin_version: String by project
val kotlinx_serialization_version: String by project
val logback_version: String by project
val lettuce_version: String by project
val slf4j_version: String by project
val postgres_driver_version: String by project
val kotlinx_coroutines_version: String by project
val exposed_version: String by project

plugins {
    kotlin("jvm") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}
apply(plugin = "kotlin")
group = "io.rewynd"
version = "0.0.1"
application {
    mainClass.set("io.rewynd.worker.MainKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven { url = URI.create("https://jitpack.io") }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":client"))

    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.slf4j:slf4j-api:$slf4j_version")
    implementation("io.lettuce:lettuce-core:$lettuce_version")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.postgresql:postgresql:$postgres_driver_version")

    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")

    // NFO parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.15.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.1")

    implementation("com.typesafe:config:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialization_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$kotlinx_coroutines_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:$kotlinx_coroutines_version")
    implementation("io.arrow-kt:arrow-core:1.2.0-RC")
    implementation("io.arrow-kt:arrow-fx-coroutines:1.2.0-RC")

    // Searching
    implementation("org.apache.lucene:lucene-core:9.6.0")
    implementation("org.apache.lucene:lucene-queries:9.6.0")
    implementation("org.apache.lucene:lucene-queryparser:9.6.0")
    implementation("org.apache.lucene:lucene-analysis-common:9.6.0")
    implementation("org.apache.lucene:lucene-analysis-icu:9.6.0")
    implementation("org.apache.lucene:lucene-suggest:9.6.0")
    implementation("org.apache.lucene:lucene-memory:9.6.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}

tasks.register("prepareKotlinBuildScriptModel") {}

kotlin {
    jvmToolchain(17)
}


tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "io.rewynd.worker.MainKt"
    }
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}