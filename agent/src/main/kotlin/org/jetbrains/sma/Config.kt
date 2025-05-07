package org.jetbrains.sma

import ai.grazie.model.cloud.AuthType

object Config {
    val grazieToken: String? = System.getProperty("GRAZIE_TOKEN")
    val grazieAuthType = System.getProperty("GRAZIE_AUTH_TYPE")?.let { authType ->
        AuthType.entries.firstOrNull { it.name.lowercase() == authType.lowercase() }
    } ?: AuthType.Service
    val grazieUrl = "https://api.app.stgn.grazie.aws.intellij.net"
    val profile = "google-chat-gemini-flash-2.5"
}