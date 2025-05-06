package org.jetbrains.sma

import org.jetbrains.sma.prompt.END_MARKER
import org.jetbrains.sma.prompt.SEPARATOR_MARKER
import org.jetbrains.sma.prompt.START_MARKER

fun applyEdits(edits: String, content: String): Either<String> {
    return parseSearchReplaceBlocks(edits).transform {
        applySearchReplaceBlocks(content, it)
    }.flatMap {
        when (it) {
            is SearchReplaceResult.Success -> Either.Value(it.value)
            is SearchReplaceResult.NoUpdates -> Either.Value(content)
        }
    }
}

private fun parseSearchReplaceBlocks(response: String): Either<SearchReplaceResult<List<SearchReplaceBlock>>> {
    return buildList {
        var state = ParseBlocksState.NONE
        val currentBefore = mutableListOf<String>()
        val currentAfter = mutableListOf<String>()
        val responseLines = response.lines()
        responseLines.forEachIndexed { ix, line ->
            // Although we ask the LLM to produce specific separator lines between sections, we cannot trust it, hence the trimming
            when {
                state == ParseBlocksState.NONE && line.trim(
                    '`',
                    '"',
                    ' '
                ) == "NO UPDATES" -> return responseLines.filterIndexed { ix2, _ -> ix2 != ix }
                    .filter { it.isNotBlank() }.joinToString("\n")
                    .let { Either.Value(SearchReplaceResult.NoUpdates(it)) }

                state == ParseBlocksState.NONE && line.trimStart('<', ' ') == START_MARKER.trimStart('<', ' ') -> {
                    state = ParseBlocksState.BEFORE
                    currentBefore.clear()
                }

                state != ParseBlocksState.NONE && line.isNotBlank() && line.trim()
                    .all { it == SEPARATOR_MARKER[0] } -> {
                    if (state == ParseBlocksState.BEFORE) {
                        state = ParseBlocksState.AFTER
                        currentAfter.clear()
                    }
                }

                state == ParseBlocksState.AFTER && line.trimStart('>', ' ') == END_MARKER.trimStart('>', ' ') -> {
                    if (currentBefore.isEmpty()) {
                        return Either.Error("One of the search-replace blocks contains an empty \"SEARCH\" section")
                    }

                    add(SearchReplaceBlock(currentBefore.toList().map { it.normalizeSymbols() }, currentAfter.toList()))
                    state = ParseBlocksState.NONE
                    currentBefore.clear()
                    currentAfter.clear()
                }

                else -> {
                    when (state) {
                        ParseBlocksState.BEFORE -> currentBefore.add(line)

                        ParseBlocksState.AFTER -> currentAfter.add(line)

                        else -> {
                            // there might be reasoning in between the blocks, ignore it
                        }
                    }
                }
            }
        }
        if (state != ParseBlocksState.NONE) {
            return Either.Error("The response does not conform with the expected format.")
        }
        if (isEmpty()) {
            return Either.Error(
                "The response does not contain any search-replace blocks.\n" + "If there is no need to update the file or you cannot do it, respond `NO UPDATES` on the first line and then explain your decision."
            )
        }
    }.let { Either.Value(SearchReplaceResult.Success(it)) }
}

private fun applySearchReplaceBlocks(prevFileContents: String, blocks: List<SearchReplaceBlock>): Either<String> {
    if (blocks.isEmpty()) return Either.Value(prevFileContents)

    // find matching before blocks in original file contents
    val prevFileContentsLines = prevFileContents.lines().map { it.normalizeSymbols() }
    val blocksToReplace = blocks.map { searchReplaceBlock ->
        val matchLineIndexes = mutableListOf<Int>()
        (0..(prevFileContentsLines.size - searchReplaceBlock.before.size)).forEach { ix ->
            val fileSnippet = prevFileContentsLines.subList(ix, ix + searchReplaceBlock.before.size)
            if (searchReplaceBlock.before.zip(fileSnippet).all { it.first == it.second }) {
                matchLineIndexes.add(ix)
            }
        }

        if (matchLineIndexes.isEmpty()) return Either.Error(
            "Couldn't apply the requested changes to the file. This code block has not been found in the original file:\n" + "```\n${
                searchReplaceBlock.before.joinToString(
                    "\n"
                )
            }\n```\n" + "Try again and make sure the SEARCH part of the block contains a continuous chunk of lines from the source file, exactly as it is present there, with all the comments, formatting and indentation. "
        )

        if (matchLineIndexes.size > 1) return Either.Error(
            "Couldn't apply the requested changes to the file. This code block occurs several times in the source file:\n" + "```\n${
                searchReplaceBlock.before.joinToString(
                    "\n"
                )
            }\n```\n" + "Try again and make sure the SEARCH part of the block is unique in the source file. You can achieve this by adding some context lines to the code snippet, before and after the lines that need to be updated."
        )

        matchLineIndexes[0] to searchReplaceBlock
    }.sortedBy { it.first }

    // verify that search-replace blocks do not overlap
    fun SearchReplaceBlock.trimBeforeBlockLines(linesCount: Int) = buildString {
        appendLine("```")
        appendLine(before.take(linesCount).joinToString("\n") + if (before.size > linesCount) "\n..." else "")
        appendLine("```").appendLine()
    }
    blocksToReplace.zipWithNext { (prevStart, prevBlock), (nextStart, nextBlock) ->
        if (prevStart + prevBlock.before.size > nextStart) return Either.Error(
            "Couldn't apply the requested changes to the file. The following blocks overlap with each other:\n" + prevBlock.trimBeforeBlockLines(
                10
            ) + "and\n\n" + nextBlock.trimBeforeBlockLines(10) + "Try again and make sure the SEARCH part of the block does not overlap with the REPLACE part of the next block."
        )
    }

    val updatedFileContents =
        blocksToReplace.reversed().fold(prevFileContentsLines) { fileContent, (startIndex, block) ->
            buildList {
                (0 until startIndex).forEach { ix ->
                    add(fileContent[ix])
                }
                addAll(block.after)
                (startIndex + block.before.size until fileContent.size).forEach { ix ->
                    add(fileContent[ix])
                }
            }
        }.joinToString("\n")

    if (updatedFileContents == prevFileContents) return Either.Error(
        "The requested changes do not affect the contents of the file. Either try again with different SEARCH/REPLACE blocks or respond with NO UPDATES on the first line and explain why no updates are needed or can be made to the file."
    )

    return Either.Value(updatedFileContents)
}

private fun String.normalizeSymbols() = map {
    when (it) {
        '\u201C', '\u201D', '\u201E', '\u2033', '\u275D', '\u275E', '\u301D', '\u301E' -> '"'

        '\u02BB', '\u02BC', '\u066C', '\u2018', '\u201A', '\u275B', '\u275C', '\u0027', '\u02B9', '\u02BE', '\u02C8', '\u02EE', '\u0301', '\u0313', '\u0315', '\u055A', '\u05F3', '\u07F4', '\u07F5', '\u1FBF', '\u2019', '\u2032', '\uA78C', '\uFF07' -> '\''

        '\u02F8', '\u0589' -> ':'
        '\u037E', '\uFE54' -> ';'
        '\u2039' -> '<'
        '\u203A' -> '>'
        else -> it
    }
}.joinToString("")

private enum class ParseBlocksState { NONE, BEFORE, AFTER }

private class SearchReplaceBlock(val before: List<String>, val after: List<String>)

private sealed class SearchReplaceResult<T : Any> {
    class Success<T : Any>(val value: T) : SearchReplaceResult<T>()
    class NoUpdates<T : Any>(val reasoning: String) : SearchReplaceResult<T>()
}

private inline fun <T : Any, R : Any> Either<SearchReplaceResult<T>>.transform(
    f: (T) -> Either<R>
): Either<SearchReplaceResult<R>> = flatMap {
    when (it) {
        is SearchReplaceResult.Success -> f(it.value).map { SearchReplaceResult.Success(it) }

        is SearchReplaceResult.NoUpdates -> Either.Value(SearchReplaceResult.NoUpdates(it.reasoning))
    }
}