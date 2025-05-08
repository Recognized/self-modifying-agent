import java.io.ByteArrayInputStream
import java.util.*


val localProps by lazy {
    loadProperties(File("${buildscript.sourceFile!!.parent}/../local.properties"))
}

fun RepositoryHandler.jbTeamPackages(
    repoName: String,
    projectKey: String,
    gradleName: String = repoName.split("-").joinToString("") { it.replaceFirstChar { it.uppercase() } }
) {
    maven {
        name = gradleName
        url = uri("https://packages.jetbrains.team/maven/p/$projectKey/$repoName")
        credentials {
            username = localProps?.get("spaceUsername") as String? ?: System.getenv("SPACE_USERNAME")
            password = localProps?.get("spacePassword") as String? ?: System.getenv("SPACE_PASSWORD")
        }
    }
}

fun loadProperties(propertiesFile: File): Properties? {
    return propertiesFile.takeIf { it.exists() && it.isFile }?.let {
        ByteArrayInputStream(providers.fileContents(rootProject.layout.file(rootProject.provider { propertiesFile })).asBytes.get()).use {
            Properties().apply { load(it) }
        }
    }
}

repositories {
    flatDir {
        dirs("libs") // Gradle will look for JARs in the 'libs' directory
    }

    mavenCentral()
    jbTeamPackages(repoName = "build-deps", projectKey = "crl")
}

// 1. Create a custom configuration for dependencies to download
val downloadLibs: Configuration by configurations.creating

// 3. Create a task to copy these dependencies to the 'libs' directory
tasks.register<Copy>("downloadDependenciesToLib") {
    group = "custom"
    description = "Downloads specified dependencies to the project's 'libs' directory."

    // Ensure the 'libs' directory exists
    val libsDir = project.layout.projectDirectory.dir("libs")
    destinationDir = libsDir.asFile

    from(downloadLibs) {
    }

    doFirst {
        if (!libsDir.asFile.exists()) {
            libsDir.asFile.mkdirs()
        }
        println("Downloading dependencies to ${libsDir.asFile.absolutePath}...")
    }

    doLast {
        println("Dependencies successfully copied to 'libs' directory.")
        println("Files in libs directory:")
        libsDir.asFile.listFiles()?.forEach { println(" - ${it.name}") }
    }
}

plugins {
    kotlin("jvm") version "2.1.20"
    id("io.ktor.plugin") version libs.versions.ktor.get()
    application
}

group = "org.jetbrains.sma"
version = "1.0-SNAPSHOT"

application {
    applicationName = "self-modifying-agent"
    mainClass.set("org.jetbrains.sma.MainKt")
}

tasks.register<Exec>("pullNodeImage") {
    group = "docker"
    description = "Pulls the specified Node.js Docker image."

    commandLine("docker", "pull", "node:23.1.0")
}

tasks.named<JavaExec>("run") {
    dependsOn("pullNodeImage")

    localProps.orEmpty().forEach { (key, value) ->
        systemProperty(key.toString(), value.toString())
    }
    systemProperty("projectDir", project.projectDir.absolutePath)
}


dependencies {
    implementation(libs.apache.sshd.core)
    implementation(libs.bundles.jackson)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding.jvm)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.serialization.jackson)
    implementation(libs.ktor.server.status.pages)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    downloadLibs(libs.grazie.model.llm)
    api(libs.grazie.model.llm)

    downloadLibs(libs.grazie.ktor.client)
    downloadLibs(libs.grazie.ktor.client)
    downloadLibs(libs.grazie.ktor.utils)
    downloadLibs(libs.grazie.gateway.api)
    downloadLibs(libs.grazie.gateway.client)

    implementation(libs.grazie.ktor.client) {
        exclude(group = "io.ktor")
    }
    implementation(libs.grazie.ktor.utils) {
        exclude(group = "io.ktor")
    }
    api(libs.grazie.gateway.api) {
        exclude(group = "io.ktor")
    }
    api(libs.grazie.gateway.client) {
        exclude(group = "io.ktor")
    }
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.2")

    implementation(libs.logback.classic)
    implementation("io.ktor:ktor-server-host-common:3.1.2")
    testImplementation(kotlin("test"))
    api(libs.kotlin.reflect)
    api(libs.slf4j.api)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:${libs.versions.kotlinx.coroutines.get()}")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}