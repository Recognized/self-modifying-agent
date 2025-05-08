package org.jetbrains.sma

import ai.grazie.model.cloud.AuthType
import ai.grazie.model.llm.profile.AnthropicProfileIDs

object Config {
    val grazieToken: String? = System.getProperty("GRAZIE_TOKEN") ?: System.getenv("GRAZIE_TOKEN")
    val grazieAuthType = System.getProperty("GRAZIE_AUTH_TYPE")?.let { authType ->
        AuthType.entries.firstOrNull { it.name.lowercase() == authType.lowercase() }
    } ?: AuthType.User
    val grazieUrl = "https://api.app.stgn.grazie.aws.intellij.net"
    var profile = "google-chat-gemini-flash-2.5"
//    var proProfile = "google-chat-gemini-pro-2.5"
    var proProfile = AnthropicProfileIDs.Claude_3_7_Sonnet.id
    var flashProfile = "google-chat-gemini-flash-2.5"
}