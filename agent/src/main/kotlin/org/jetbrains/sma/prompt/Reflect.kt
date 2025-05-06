package org.jetbrains.sma.prompt

import org.jetbrains.sma.asFunction
import org.jetbrains.sma.map
import org.jetbrains.sma.template

class Reflect(val key: String)

private val REFLECT_PREAMBLE = """
    This request has been initiated by `reflect` call with key ${Reflect::key.template}.
    Please, read the program again and provide updates if needed.
""".trimIndent().asFunction<Reflect>()

val REFLECT_PROMPT = REFLECT_PREAMBLE.map {
    buildString {
        appendLine(it)
        appendLine(EDIT_FILE_PROMPT)
    }
}