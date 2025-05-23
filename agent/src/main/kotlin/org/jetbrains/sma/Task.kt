package org.jetbrains.sma

import ai.grazie.model.llm.chat.tool.LLMTool
import ai.grazie.utils.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.apache.commons.lang3.SystemUtils.IS_OS_LINUX
import org.jetbrains.sma.LineType.Error
import org.jetbrains.sma.LineType.Trace
import org.jetbrains.sma.prompt.AGENT_DEFINITION
import org.jetbrains.sma.prompt.CONTEXT_PROMPT
import org.jetbrains.sma.prompt.ERROR_HANDLER_PROMPT
import org.jetbrains.sma.prompt.FollowUpRequest
import org.jetbrains.sma.prompt.HandleErrorRequest
import org.jetbrains.sma.prompt.INITIAL_GENERATION_PROMPT
import org.jetbrains.sma.prompt.LLM_REQUEST_PROMPT
import org.jetbrains.sma.prompt.REFLECT_PROMPT
import org.jetbrains.sma.prompt.Reflect
import org.jetbrains.sma.prompt.SUBSEQUENT_GENERATION_PROMPT
import org.jetbrains.sma.prompt.TaskContext
import org.jetbrains.sma.prompt.withSuspendMarker
import org.jetbrains.sma.prompt.withoutSuspendMarker
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

var task: Task = Task()

enum class LineType {
    Error, Info, Trace
}

class LogCollector {
    private val lines = mutableListOf<LogLine>()
    private var id = 0

    @Synchronized
    fun appendLine(line: String, type: LineType = Trace) {
        line.lines().forEach {
            lines.add(LogLine(id++.toString(), it, type.name))
        }
    }

    @Synchronized
    fun lines(): List<LogLine> {
        return lines.toList()
    }
}

private val logger = logger(Task::class)

class Task : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = Dispatchers.IO

    val env = Env()
    var prompt: String = ""
        set(value) {
            if (value == field) return
            promptChanged = true
            llmCache.clear()
            reflectCache.clear()
            oldPrompts.add(field)
            field = value
        }
    var mounts: List<String> = emptyList()
        set(value) {
            if (value == field) return
            mountsChanged = true
            field = value
        }
    var iterationIndex = 0
    var code = templateJs
        set(value) {
            if (value == field) return
            field = value
        }
    var completed = true
    var startedAt = 0L
    var log = LogCollector()
    var failed = false
    var mountsChanged = false
    val llmCache = mutableMapOf<LlmRequest, String>()
    val reflectCache = mutableSetOf<ReflectRequest>()
    var promptChanged = false
    var oldPrompts = mutableListOf<String>()

    var loopActive = AtomicBoolean(false)

    private fun fail(message: String): Nothing {
        failed = true
        log.appendLine(message)
        log.appendLine("Agent failed too many times in a row. Stopping.", Error)
        error("Failed without chance to recover")
    }

    private suspend fun initialGeneration() {
        log.appendLine("Prompt accepted: $prompt")
        log.appendLine("Generating initial version of the program...")
        startedAt = System.currentTimeMillis()
        completed = false
        val editedCode = complete(
            listOf(
                GrazieChatMessageDB.System(AGENT_DEFINITION),
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, "", env, null))),
                GrazieChatMessageDB.User(if (promptChanged) SUBSEQUENT_GENERATION_PROMPT(FollowUpRequest(oldPrompts)) else INITIAL_GENERATION_PROMPT)
            ).map { it.loggable() },
            "initial",
            Config.profile,
            builder = {},
            acceptMissingParams = false,
            dynamicCachePoint = true
        ) { response, _ ->
            applyEdits(response.asText().text(), code)
        } ?: fail("Initial code diff generation failed.")
        code = editedCode
        promptChanged = false
        iterationIndex++
    }

    suspend fun handleError(error: String) {
        log.appendLine("Error detected:\n$error", Error)
        log.appendLine("Attempting to fix... ")
        val editedCode = complete(
            listOf(
                GrazieChatMessageDB.System(AGENT_DEFINITION),
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, "", env, null))),
                GrazieChatMessageDB.User(ERROR_HANDLER_PROMPT(HandleErrorRequest(error)))
            ).map { it.loggable() },
            "error",
            Config.profile,
            builder = {},
            acceptMissingParams = false,
            dynamicCachePoint = true
        ) { response, _ ->
            applyEdits(response.asText().text(), code)
        } ?: fail("Attempt to generate code diff to fix the error failed.")
        code = editedCode
        iterationIndex++
    }

    suspend fun llm(request: LlmRequest): String {
        val chat = listOf(
            GrazieChatMessageDB.System(AGENT_DEFINITION).loggable(),
            GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, request.args, env, request.key)))
                .loggable(),
            GrazieChatMessageDB.User(LLM_REQUEST_PROMPT(request)).loggable()
        )

        task.log.appendLine(
            "Executing LLM request: \"${
                chat.joinToString("\n") { it.grazieChatMessageDB.text() }.ellipsize(100)
            }\""
        )

        logger.info { "Executing LLM request:\n${chat.joinToString("\n") { it.grazieChatMessageDB.text() }}" }

        val result = complete(
            chat = chat,
            labelPromptId = "client-request",
            llmProfile = Config.profile,
            acceptMissingParams = true,
            builder = {
                tools = listOf(
                    LLMTool(
                        "error",
                        "LLM request cannot be correctly answered (e.g. due to missing tools).",
                        LLMTool.Parameters.fromJsonString(
                            """
                        {
                            "type": "object",
                            "properties": {
                                "message": {
                                    "type": "string",
                                    "description": "Error message"
                                }
                            },
                            "required": []
                        }
                        """.trimIndent()
                        )
                    )
                )
            },
            dynamicCachePoint = false
        ) { response, callId ->
            var message: GrazieChatMessageDB? = null
            val text = response.withAssistantMessageConsumer(callId) {
                message = it
            }.asText().text()

            val errorCall =
                (message as? GrazieChatMessageDB.Assistant)?.calls.orEmpty().find { it.functionName == "error" }
            if (errorCall != null) {
                Either.Value(Either.Error(errorCall.content.takeIf { it.isNotBlank() } ?: text))
            } else {
                Either.Value(Either.Value(text))
            }
        }

        val content = when (result) {
            is Either.Error -> result.message
            is Either.Value -> result.value
            else -> null
        }

        if (result != null && result is Either.Value) {
            llmCache[request] = result.value
        }

        log.appendLine(
            "LLM response: \"${content?.ellipsize(100) ?: "Error: failed to receive response from LLM. Try again."}\"",
            if (result == null) Error else Trace
        )

        when (result) {
            is Either.Error -> throw IllegalArgumentException(result.message)
            is Either.Value -> return result.value
            else -> throw IllegalArgumentException("Error: failed to receive response from LLM. Try again.")
        }
    }

    suspend fun reflect(reflect: ReflectRequest) {
        log.appendLine("Stopped at `reflect(${reflect.key}...)` call. Generating next version of the program.")
        val editedCode = complete(
            listOf(
                GrazieChatMessageDB.System(AGENT_DEFINITION),
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, reflect.args, env, reflect.key))),
                GrazieChatMessageDB.User(REFLECT_PROMPT(Reflect(reflect.key)))
            ).map { it.loggable() },
            "reflect",
            Config.profile,
            builder = {},
            acceptMissingParams = false,
            dynamicCachePoint = true
        ) { response, _ ->
            applyEdits(response.asText().text(), code.withSuspendMarker(reflect.key)).map {
                it.withoutSuspendMarker()
            }
        } ?: fail("Attempt to generate code diff for the next version of the program failed.")
        logger.info { "Updated code after reflection:\n$editedCode" }
        code = editedCode
        iterationIndex++
    }

    suspend fun awaitMainLoop() {
        while (loopActive.get()) {
            kotlinx.coroutines.delay(100L)
        }
    }

    private fun shouldRecreateContainer(): Boolean {
        return iterationIndex == 0 || mountsChanged
    }

    private fun safeSh(command: String): Process {
        return ProcessBuilder(command.split(" "))
            .inheritIO()
            .start()
    }

    private suspend fun recreateContainer() {
        suspendCancellableCoroutine<Unit> { cont ->
            thread {
                try {
                    val containerName = "self-modifying-agent-nodejs"
                    task.log.appendLine("Recreating node docker container...")
                    safeSh("docker stop $containerName").waitFor()
                    safeSh("docker rm $containerName").waitFor()
                    val process =
                        safeSh("docker run -d -it --name $containerName${if (IS_OS_LINUX) " --network=\"host\"" else ""} node:23.1.0")
                    val timeout = !process.waitFor(1, MINUTES)
                    if (timeout) {
                        cont.resumeWith(Result.failure(Exception("Failed to recreate container. Timeout")))
                        return@thread
                    }
                    val exitCode = process.exitValue()
                    if (exitCode != 0) {
                        cont.resumeWith(Result.failure(Exception("Failed to recreate container. Exit code: $exitCode")))
                    } else {
                        cont.resumeWith(Result.success(Unit))
                    }
                } catch (ex: Throwable) {
                    cont.resumeWith(Result.failure(ex))
                }
            }
        }
        task.log.appendLine("Docker container successfully recreated")
    }

    fun launchMainLoop() {
        launch {
            loopActive.set(true)
            try {
                val totalAttempts = 5
                var fixErrorAttempts = totalAttempts

                if (shouldRecreateContainer()) {
                    recreateContainer()
                }

                if (iterationIndex == 0 || promptChanged) {
                    initialGeneration()
                }

                while (true) {
                    try {
                        Runner().run(code, log)
                        break
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (e: GenerationError) {
                        if (fixErrorAttempts > 0) {
                            fixErrorAttempts--
                            handleError(e.message!!)
                        } else {
                            fail("Error is still present after $totalAttempts attempts. Stopping")
                        }
                    }
                }
            } finally {
                loopActive.set(false)
            }
        }
    }
}