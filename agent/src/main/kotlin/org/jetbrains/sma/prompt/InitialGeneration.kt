package org.jetbrains.sma.prompt

private val INITIAL_GENERATION_PREAMBLE = """
    This is the first request to generate program. <code> block now contains only the initial template for the program.
    Remember, that program execution starts from the `main` function and it hasn't been started yet. 
    Please, provide updates to this code. Don't plan for all the details right away. Add `reflect` function calls instead
    to let yourself make a decision later based on the information you retrieve. Make sure to double-check your assumptions
    and fallback to modifying the program code if needed. First generation should be more high-level/abstract.
""".trimIndent()

val INITIAL_GENERATION_PROMPT = buildString {
    appendLine(INITIAL_GENERATION_PREAMBLE)
    appendLine(EDIT_FILE_PROMPT)
}
