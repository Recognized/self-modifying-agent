package org.jetbrains.sma.prompt

import org.jetbrains.sma.Env
import org.jetbrains.sma.asFunction
import org.jetbrains.sma.template

class Variables(val vars: Map<String, Any?> = emptyMap()) {
    override fun toString(): String = buildString {
        vars.forEach { (name, value) ->
            appendLine("<var name=\"$name\">$value</var>")
        }
    }
}

fun String.withSuspendMarker(key: String): String {
    return lines().map {
        if (it.contains("\"$key\"")) {
            "$it $EXECUTION_SUSPENDED_MARKER"
        }
    }.joinToString("\n")
}

class TaskContext(
    val originalPrompt: String,
    code: String,
    val args: String,
    val env: Env,
    suspensionKey: String?
) {
    val code = suspensionKey?.let { code.withSuspendMarker(it) } ?: code
}

val CONTEXT_PROMPT = """
    The original user prompt is:
    <prompt>
    ${TaskContext::originalPrompt.template}
    </prompt>
    
    Environment variables:
    <env>
    ${TaskContext::env.template}
    </env>
    
    Current code of the program:
    <code>
    ${TaskContext::code.template}
    </code>
    
    Variables provided to the function that suspended program execution:
    <vars>
    ${TaskContext::args.template}
    </vars>
""".trimIndent()
    .asFunction<TaskContext>()