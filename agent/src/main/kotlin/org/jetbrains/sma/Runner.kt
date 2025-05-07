package org.jetbrains.sma

import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.sma.LineType.Error
import org.jetbrains.sma.LineType.Info
import java.nio.file.Files
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.bufferedWriter

class Runner {
    suspend fun run(code: String, log: LogCollector) {
        suspendCancellableCoroutine { cont ->
            thread {
                val tempMain = Files.createTempFile("", "main.js")
                tempMain.bufferedWriter().use {
                    it.write(code)
                }

                ProcessBuilder(
                    "docker",
                    "cp",
                    "${jsEnvDir.toAbsolutePath().toString().removeSuffix("/")}/.",
                    "self-modifying-agent-nodejs:/usr/src/js-env"
                ).start().waitFor()
                ProcessBuilder(
                    "docker",
                    "cp",
                    "${tempMain.toAbsolutePath()}",
                    "self-modifying-agent-nodejs:/usr/src/js-env/main.js"
                ).start().waitFor()

                log.appendLine("Starting program execution...")
                val process = ProcessBuilder(
                    "docker",
                    "exec",
                    "-w",
                    "/usr/src/js-env",
                    "self-modifying-agent-nodejs",
                    "node",
                    "agent.js"
                )
                    .directory(jsEnvDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()

                thread {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            log.appendLine("[js-env]: $line", Info)
                        }
                    }
                }

                val errorLines = StringBuilder()
                val lock = ReentrantLock()

                thread {
                    process.errorStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            log.appendLine("[js-env]: $line", Error)
                            lock.withLock {
                                errorLines.appendLine(line)
                            }
                        }
                    }
                }

                val timeout = !process.waitFor(1, MINUTES)
                val exitCode = if (timeout) -1 else process.exitValue()

                if (exitCode == 0) {
                    cont.resumeWith(Result.success(Unit))
                } else if (timeout) {
                    log.appendLine("Program execution timed out (1 minute timeout)", Error)
                    cont.resumeWith(Result.failure(GenerationError("Program failed to finish in under 1 minute")))
                } else {
                    log.appendLine("Program execution finished with exit code $exitCode", Error)
                    val errorFile = lock.withLock { errorLines.toString() }
                    cont.resumeWith(Result.failure(GenerationError(errorFile)))
                }
            }
        }
    }
}

class GenerationError(message: String) : Exception(message)
