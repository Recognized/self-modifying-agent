package org.jetbrains.sma.prompt

import org.jetbrains.sma.asFunction
import org.jetbrains.sma.map
import org.jetbrains.sma.template

class FollowUpRequest(
    oldPrompts: List<String>,
) {
    val oldPrompts = oldPrompts.joinToString(separator = "\n") { "\"it\"" }
}

private val SUBSEQUENT_GENERATION_PREAMBLE = """
    This is the follow-up request to generate program. <code> block now contains the program used with last prompt.
    Here is the list of previous prompts from oldest to newest:
    ${FollowUpRequest::oldPrompts.template}
    You should probably totally rewrite `main` function, but it's up to you. You shouldn't leave in the code something that
    is not achieving the goal of the new prompt.
    Remember, that program execution starts from the `main` function and it hasn't been started yet. 
    Please, provide updates to this code. Don't plan for all the details right away. Add `reflect` function calls instead
    to let yourself make a decision later based on the information you retrieve. Make sure to double-check your assumptions
    and fallback to modifying the program code if needed. First generation should be more high-level/abstract.
""".trimIndent().asFunction<FollowUpRequest>()

val SUBSEQUENT_GENERATION_PROMPT = SUBSEQUENT_GENERATION_PREAMBLE.map {
    buildString {
        appendLine(it)
        appendLine(EDIT_FILE_PROMPT)
    }
}