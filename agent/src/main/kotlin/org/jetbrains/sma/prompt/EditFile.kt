package org.jetbrains.sma.prompt

const val START_MARKER = "<<<<<<< SEARCH"
const val SEPARATOR_MARKER = "======="
const val END_MARKER = ">>>>>>> REPLACE"

val EDIT_FILE_PROMPT = """
    Respond with the series of the *SEARCH/REPLACE* blocks. Each block should have the following structure:
    1. The block starts with the full path of the file being updated on a separate line .
    2. Then follows the '$START_MARKER' line.
    3. Then follows the "SEARCH" code block to search for in the existing source code. The "SEARCH" code block should have at least 7 lines.
    3. Then follows the '$SEPARATOR_MARKER' line which separates the "SEARCH" code block from the "REPLACE" snippet.
    4. Then follows the "REPLACE" code block to replace the "SEARCH" code block with.
    5. Then follows the '$END_MARKER' line.
    6. Subsequent blocks should be separated from each other by an empty line.
    
    Every *SEARCH* section must match the existing source code exactly once, character for character, including all comments, docstrings, etc.
    Include enough lines to make the SEARCH blocks uniquely match the lines to change.
    Do not include long runs of unchanged lines in *SEARCH/REPLACE* blocks. Keep *SEARCH/REPLACE* blocks as short as possible.
    Include just the changing lines and a few surrounding lines (but not less than 3 above and 3 below) for uniqueness.
    
    The blocks should not overlap.
    
    <example>
    ```
    <<<<<<< SEARCH
    function add(a: number, b: number): number {
        return a + b;
    }
    
    console.log(add(2, 3));
    =======
    function add(a: number, b: number): number {
        console.log(`Adding a and b`);
        return a + b;
    }
    
    console.log(add(2, 3));
    >>>>>>> REPLACE
    
    <<<<<<< SEARCH
    const numbers = [1, 2, 3, 4, 5];
    console.log(numbers.map(n => n * 2));
    =======
    const numbers = [1, 2, 3, 4, 5];
    const doubled = numbers.map(n => n * 2);
    console.log(doubled);
    >>>>>>> REPLACE
    ```
    </example>

    Every *SEARCH* section must match the existing source code exactly once, character for character, including all comments, docstrings, etc.
    Include enough lines to make the SEARCH blocks uniquely match the lines to change.
    There are two types of matching errors:
    1. *SEARCH* section doesn't match the existing source code. In case this error is present non of *SEARCH/REPLACE* blocks from
    current command will be applied. With the next command you will be able to fix *SEARCH* section to match the source code.
    2. *SEARCH* section matches to the existing source code several times. In case this error is present non of *SEARCH/REPLACE* blocks from
    current command will be applied and all matches will be displayed along with surrounding context. With the next command
    you will be able to specify more lines surrounding *SEARCH* blocks to ensure unique matches.

    Break large *SEARCH/REPLACE* blocks into a series of smaller blocks that each change a small portion of the file.

    Updates described by the search blocks the blocks will be applied sequentially. Make sure that blocks do not overlap.
    If you need to make changes to lines that are close to each other, include all those changes in a single *SEARCH/REPLACE* block.

    Do not use line numbers in *SEARCH/REPLACE* blocks. Do not wrap the blocks with fences and do not prefix them with the programming language name.

    To move code within a file, use 2 *SEARCH/REPLACE* blocks: 1 to delete it from its current location, 1 to insert it in the new location.

    If the file does not need any changes, respond with "NO UPDATES" text.
""".trimIndent()