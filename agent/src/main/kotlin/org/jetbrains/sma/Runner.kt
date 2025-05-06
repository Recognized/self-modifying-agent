package org.jetbrains.sma

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.bufferedWriter

class Runner {
    suspend fun run(code: String, log: LogCollector) {
        jsEnvDir.resolve("main.js").bufferedWriter().use {
            it.write(code)
        }

        suspendCancellableCoroutine { cont ->
            thread {
                log.appendLine("Starting program execution...")
                val process = ProcessBuilder("npm", "run", "run")
                    .directory(jsEnvDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()

                thread {
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            log.appendLine("[js-env]: $line")
                        }
                    }
                }

                val errorLines = StringBuilder()
                val lock = ReentrantLock()

                thread {
                    process.errorStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            log.appendLine("[js-env]: $line", error = true)
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
                    log.appendLine("Program execution timed out (1 minute timeout)", error = true)
                    cont.resumeWith(Result.failure(GenerationError("Program failed to finish in under 1 minute")))
                } else {
                    log.appendLine("Program execution finished with exit code $exitCode", error = true)
                    val errorFile = lock.withLock { errorLines.toString() }
                    cont.resumeWith(Result.failure(GenerationError(errorFile)))
                }
            }
        }
    }
}

class GenerationError(message: String) : Exception(message)
