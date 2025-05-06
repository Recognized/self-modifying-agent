package org.jetbrains.sma.prompt

import org.jetbrains.sma.asFunction
import org.jetbrains.sma.map
import org.jetbrains.sma.template

class HandleErrorRequest(
    val error: String,
)

private val ERROR_HANDLER = """
    The program failed to complete normally. An error occurred:
    ${HandleErrorRequest::error.template}
    Please, read the program again and provide updates if needed.
""".trimIndent().asFunction<HandleErrorRequest>()

val ERROR_HANDLER_PROMPT = ERROR_HANDLER.map {
    buildString {
        appendLine(it)
        appendLine(EDIT_FILE_PROMPT)
    }
}