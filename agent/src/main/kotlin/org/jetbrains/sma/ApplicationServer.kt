@file:Suppress("unused")

package org.jetbrains.sma

import ai.grazie.utils.text
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray

fun startServer() {
    embeddedServer(CIO, port = 2205, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureRouting()
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        jackson {
            enable(INDENT_OUTPUT)
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
}

class LlmRequest(
    val systemMessage: String,
    val userMessage: String,
    val args: String,
)

class ReflectRequest(
    val key: String,
    val args: String,
)

suspend fun awaitServer(maxRetries: Int = 30, delayMillis: Long = 1000L): Boolean {
    val client = HttpClient(io.ktor.client.engine.cio.CIO)
    repeat(maxRetries) {
        try {
            val response: HttpResponse = client.get("http://0.0.0.0:2205/status")
            if (response.status == HttpStatusCode.OK) {
                return true
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (e: Throwable) {
            // ignore
        }

        delay(delayMillis)
    }

    return false
}

class LogLine(val id: String, val line: String, val error: Boolean)

class TaskStatus(
    val iterationIndex: Int,
    val prompt: String,
    val completed: Boolean,
    val log: Array<LogLine>,
    val startedAt: Long,
    val code: String
)

fun Application.configureRouting() {
    routing {
        staticFiles("", projectDir.resolve("static").toFile())

        get("/status") {
            call.respond(
                TaskStatus(
                    task.iterationIndex,
                    task.prompt,
                    task.completed,
                    task.log.lines().takeLast(1000).toTypedArray(),
                    task.startedAt,
                    task.code
                )
            )
        }

        post("/prompt") {
            val prompt = call.receive<String>()
            task.awaitMainLoop()
            task.prompt = prompt
            task.launchMainLoop()
            call.respond(HttpStatusCode.OK)
        }

        post("/reflect") {
            val request = call.receive<ReflectRequest>()
            call.respond(HttpStatusCode.OK)

            task.launch {
                task.awaitMainLoop()
                task.reflect(request)
                task.launchMainLoop()
            }
        }

        post("/error") {
            val error = call.receive<String>()
            call.respond(HttpStatusCode.OK)

            task.launch {
                task.awaitMainLoop()
                task.handleError(error)
                task.launchMainLoop()
            }
        }

        post("/task-completed") {
            task.completed = true
            task.log.appendLine("Task completed.")
            call.respond(HttpStatusCode.OK)
        }

        post("/llm") {
            log.info("Received LLM request")
            val request = call.receive<LlmRequest>()
            val chat = listOf(
                GrazieChatMessageDB.System(request.systemMessage).loggable(),
                GrazieChatMessageDB.User(request.userMessage).loggable(),
            ) + GrazieChatMessageDB.User(request.args).loggable()

            task.log.appendLine("Executing LLM request: \"${chat.joinToString("\n") { it.grazieChatMessageDB.text() }.ellipsize(100)}\"")

            val result = complete(
                chat = chat,
                labelPromptId = "client-request",
                llmProfile = Config.profile,
                acceptMissingParams = true,
                builder = {

                },
                dynamicCachePoint = false
            ) { response, _ ->
                Either.Value(response.asText().text())
            }

            task.log.appendLine("LLM response: \"${result?.ellipsize(100) ?: "Error: failed to receive response from LLM. Try again."}\"", error = result == null)
            log.info("LLM request response: $result")
            call.respond(result ?: "Error: failed to receive response from LLM. Try again.")
        }
    }
}

fun String.ellipsize(take: Int): String {
    return if (length > take) {
        take(take) + "..."
    } else {
        this
    }
}