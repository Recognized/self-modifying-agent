package org.jetbrains.sma.prompt

import org.jetbrains.sma.LlmRequest
import org.jetbrains.sma.asFunction
import org.jetbrains.sma.template

val LLM_REQUEST_PROMPT = """
    The execution of the program has been suspended. Now the program makes request to LLM from the line
    marked with '$EXECUTION_SUSPENDED_MARKER'. Next, you will be provided actual prompt sent along with the request.
    Please, take into account how the result of your response is going to be used and adjust format accordingly.
    
    Here is the prompt:
    ${LlmRequest::systemMessage.template}

    ${LlmRequest::userMessage.template}
    
    Other args provided with the request:
    ${LlmRequest::args.template}.
    
    If this request can't be fulfilled by you, call tool `error`. 
""".trimIndent().asFunction<LlmRequest>()