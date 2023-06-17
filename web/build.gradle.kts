import com.github.gradle.node.npm.task.NpmTask
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "1.8.21"
    id("com.github.node-gradle.node") version "5.0.0"
}

group = "io.rewynd"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
}

val buildNpm = tasks.register<NpmTask>("buildNpm") {
    dependsOn.add(tasks.npmInstall)
    args.addAll("run", "build")
}

sourceSets {
    main {
        resources {
            srcDir("rewynd-web/webpack")
        }
    }
}

node {
    nodeProjectDir.set(file("${project.projectDir}/rewynd-web"))
}

tasks.jar {
    dependsOn.add(buildNpm)
}

kotlin {
    jvmToolchain(17)
}