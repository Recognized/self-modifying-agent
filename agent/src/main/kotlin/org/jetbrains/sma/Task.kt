package org.jetbrains.sma

import ai.grazie.utils.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.sma.prompt.AGENT_DEFINITION
import org.jetbrains.sma.prompt.CONTEXT_PROMPT
import org.jetbrains.sma.prompt.ERROR_HANDLER_PROMPT
import org.jetbrains.sma.prompt.HandleErrorRequest
import org.jetbrains.sma.prompt.INITIAL_GENERATION_PROMPT
import org.jetbrains.sma.prompt.REFLECT_PROMPT
import org.jetbrains.sma.prompt.Reflect
import org.jetbrains.sma.prompt.TaskContext
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

var task: Task = Task()

class LogCollector {
    private val lines = mutableListOf<LogLine>()
    private var id = 0

    @Synchronized
    fun appendLine(line: String, error: Boolean = false) {
        line.lines().forEach {
            lines.add(LogLine(id++.toString(), it, error))
        }
    }

    @Synchronized
    fun lines(): List<LogLine> {
        return lines.toList()
    }
}

class Task : CoroutineScope {
    override val coroutineContext: CoroutineContext get() = Dispatchers.IO

    val env = Env()
    var prompt: String = ""
    var mounts: List<String> = emptyList()
        set(value) {
            if (value == field) return
            mountsChanged = true
            field = value
        }
    var iterationIndex = 0
    var code = templateJs
    var completed = true
    var startedAt = 0L
    var log = LogCollector()
    var failed = false
    var mountsChanged = false
    val llmCache = mutableMapOf<LlmRequest, String>()
    val reflectCache = mutableSetOf<ReflectRequest>()

    var loopActive = AtomicBoolean(false)

    private fun fail(message: String): Nothing {
        failed = true
        log.appendLine(message)
        log.appendLine("Agent failed too many times in a row. Stopping.", error = true)
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
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, "", env))),
                GrazieChatMessageDB.User(INITIAL_GENERATION_PROMPT)
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
        iterationIndex++
    }

    suspend fun handleError(error: String) {
        log.appendLine("Error detected:\n$error", error = true)
        log.appendLine("Attempting to fix... ")
        val editedCode = complete(
            listOf(
                GrazieChatMessageDB.System(AGENT_DEFINITION),
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, "", env))),
                GrazieChatMessageDB.User(ERROR_HANDLER_PROMPT(HandleErrorRequest(error)))
            ).map { it.loggable() },
            "reflect",
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

    suspend fun reflect(reflect: ReflectRequest) {
        log.appendLine("Stopped at `reflect(${reflect.key}...)` call. Generating next version of the program.")
        val editedCode = complete(
            listOf(
                GrazieChatMessageDB.System(AGENT_DEFINITION),
                GrazieChatMessageDB.User(CONTEXT_PROMPT(TaskContext(prompt, code, reflect.args, env))),
                GrazieChatMessageDB.User(REFLECT_PROMPT(Reflect(reflect.key)))
            ).map { it.loggable() },
            "reflect",
            Config.profile,
            builder = {},
            acceptMissingParams = false,
            dynamicCachePoint = true
        ) { response, _ ->
            applyEdits(response.asText().text(), code)
        } ?: fail("Attempt to generate code diff for the next version of the program failed.")
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

    private suspend fun recreateContainer() {
        suspendCancellableCoroutine<Unit> { cont ->
            thread {
                try {
                    task.log.appendLine("Recreating node docker container...")
                    val process = ProcessBuilder("sh", "./run-docker.sh")
                        .directory(projectDir.parent.toFile())
                        .inheritIO()
                        .start()
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

                if (iterationIndex == 0) {
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