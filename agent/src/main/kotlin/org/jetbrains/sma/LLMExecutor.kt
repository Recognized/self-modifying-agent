package org.jetbrains.sma

import ai.grazie.api.gateway.api.llm.LlmAPI
import ai.grazie.api.gateway.client.api.llm.ChatRequestBuilder
import ai.grazie.client.common.model.get
import ai.grazie.model.cloud.exceptions.HttpExceptionBase
import ai.grazie.model.cloud.sse.continuous.ContinuousSSEException
import ai.grazie.model.llm.chat.tool.LLMTool
import ai.grazie.model.llm.chat.v5.LLMChatAssistantMessageText
import ai.grazie.model.llm.chat.v5.LLMChatAssistantMessageTool
import ai.grazie.model.llm.chat.v5.LLMChatFunctionMessage
import ai.grazie.model.llm.chat.v5.LLMChatMessage
import ai.grazie.model.llm.chat.v5.LLMChatSystemMessage
import ai.grazie.model.llm.chat.v5.LLMChatToolMessage
import ai.grazie.model.llm.chat.v5.LLMChatUserMessage
import ai.grazie.model.llm.data.stream.LLMStreamData
import ai.grazie.model.llm.data.stream.LLMStreamFunctionCall
import ai.grazie.model.llm.data.stream.LLMStreamQuotaMetaData
import ai.grazie.model.llm.data.stream.LLMStreamText
import ai.grazie.model.llm.data.stream.LLMStreamToolCall
import ai.grazie.model.llm.parameters.LLMParameters
import ai.grazie.model.llm.profile.LLMProfileID
import ai.grazie.model.llm.profile.dto.v8.LLMProfileDTO
import ai.grazie.model.llm.prompt.LLMPromptID
import ai.grazie.utils.mpp.money.Credit
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.sma.Either.Error
import org.jetbrains.sma.Either.Value
import org.jetbrains.sma.GrazieChatMessageDB.Assistant
import org.jetbrains.sma.GrazieChatMessageDB.Function
import org.jetbrains.sma.GrazieChatMessageDB.System
import org.jetbrains.sma.GrazieChatMessageDB.User
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

private class LLMExecutor

private val log = logger(LLMExecutor::class)

@OptIn(ExperimentalAtomicApi::class)
private val callIdSequence = AtomicInt(0)
private var cachedProfiles: List<LLMProfileDTO>? = null

private suspend fun profiles(): List<LLMProfileDTO> {
    if (cachedProfiles == null) {
        cachedProfiles = client.llm().get(LlmAPI.Profiles.V8)
            .get<LlmAPI.Profiles.V8.Response>()
            .profiles
    }
    return cachedProfiles!!
}

@OptIn(ExperimentalAtomicApi::class)
suspend fun <T> complete(
    chat: List<LoggableLLMChatMessage>,
    labelPromptId: String,
    llmProfile: String,
    builder: ChatRequestBuilder.() -> Unit,
    acceptMissingParams: Boolean,
    dynamicCachePoint: Boolean,
    consume: suspend (Flow<LLMStreamData>, callId: String) -> Either<T>
): T? {
    var attempts = 5

    var response: LoggableLLMChatMessage? = null
    var error: LoggableLLMChatMessage? = null

    do {
        --attempts

        if (dynamicCachePoint) {
            readjustCachePointIfNeeded(chat)
        }

        val callId = callIdSequence.fetchAndIncrement().toString()

        var responseMessage: GrazieChatMessageDB? = null
        val profile = profiles().find { it.id.id == llmProfile }

        val formattedMessages = chat.map { it.formatted(acceptMissingParams) }
        var credit: Credit? = null
        val myBuilder: ChatRequestBuilder.() -> Unit = {
            this.profile = LLMProfileID(llmProfile)
            this.prompt = LLMPromptID(labelPromptId)
            this.messages {
                formattedMessages.forEach {
                    messages(it.llmChatMessages)
                    if (it.cachePoint && hasCacheSupport(profile)) {
                        addCachePoint()
                    }
                }

                if (response != null) {
                    messages(response!!.llmChatMessages)
                }

                if (error != null) {
                    messages(error!!.llmChatMessages)
                }
            }

            builder()
        }

        try {
            val flow = client.llm().v8().withDefaultRetry { chat(myBuilder) }
                .withAssistantMessageConsumer(callId) { responseMessage = it }
                .withCreditConsumer { credit = it }

            when (val result = consume(flow, callId)) {
                is Error -> {
                    response = responseMessage?.loggable()
                    error = User("Error: ${result.message}").loggable()
                }

                is Value -> {
                    return result.value
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ContinuousSSEException) {
            throw e
        } catch (e: HttpExceptionBase) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    } while (attempts > 0)

    return null
}

private fun readjustCachePointIfNeeded(messages: List<LoggableLLMChatMessage>) {
    messages.zipWithNext().forEach { (prev, next) ->
        if (next.grazieChatMessageDB is System && prev.grazieChatMessageDB !is System) {
            log.error { "Bad order of LLM messages: system message appears after non-system message." }
        }
    }
    val totalSymbols = messages.sumOf { it.symbolsCount }
    if (totalSymbols < MIN_SYMBOLS_THRESHOLD) {
        return
    }
    val cachePointIndex = messages.indexOfLast { it.cachePoint }.takeIf { it != -1 }
    val cachedMessages = cachePointIndex?.let { messages.subList(0, it + 1) } ?: emptyList()
    val cachedSymbols = cachedMessages.sumOf { it.symbolsCount }
    val cachedFraction = cachedSymbols / totalSymbols.toDouble()
    if (cachedFraction < LOWER_FRACTION_CACHE_POINT) {
        if (cachePointIndex != null) {
            messages[cachePointIndex].cachePoint = false
        }
        val targetCachedSymbols = UPPER_FRACTION_CACHE_POINT * totalSymbols
        var acc = 0
        for ((index, message) in messages.withIndex()) {
            acc += message.symbolsCount
            if (acc >= targetCachedSymbols || index == messages.lastIndex) {
                message.cachePoint = true
                log.info {
                    "Resetting cache point to index=$index, " +
                            "lastIndex=${messages.lastIndex}, " +
                            "totalSymbols=$totalSymbols, " +
                            "cachedBefore=${(cachedFraction * 100).toInt()}%, " +
                            "cachedNow=${(acc.toDouble() / totalSymbols * 100).toInt()}%, " +
                            "targetCachedFraction=${(UPPER_FRACTION_CACHE_POINT * 100).toInt()}%"
                }
                break
            }
        }
    }
}

private const val MIN_SYMBOLS_THRESHOLD = 2048
private const val UPPER_FRACTION_CACHE_POINT = 1.0
private const val LOWER_FRACTION_CACHE_POINT = 0.6

class CallBuilder {
    var name: String? = null
    val content: StringBuilder = StringBuilder()
}

fun Flow<LLMStreamData>.withAssistantMessageConsumer(
    callId: String,
    action: suspend (Assistant) -> Unit
): Flow<LLMStreamData> {
    return flow {
        val content = StringBuilder()
        val functionContent = mutableListOf<CallBuilder>()

        suspend fun consume() {
            action(
                Assistant(
                    content.toString(),
                    functionContent.withIndex().mapNotNull { (index, builder) ->
                        GrazieChatFunctionCallDB(
                            "${callId}-$index",
                            builder.name ?: return@mapNotNull null,
                            builder.content.toString()
                        )
                    })
            )
        }

        var alwaysFullContent = true

        fun appendCall(name: String?, content: String) {
            val fullContent = alwaysFullContent && try {
                val element = Json.parseToJsonElement(content)
                element is JsonObject
            } catch (ex: Exception) {
                false
            }
            if (!fullContent) {
                alwaysFullContent = false
            }
            val builder = if (fullContent || functionContent.isEmpty()) {
                CallBuilder().also { functionContent.add(it) }
            } else {
                functionContent.last()
            }
            if (name != null) {
                builder.name = name
            }
            builder.content.append(content)
        }

        this@withAssistantMessageConsumer.collect {
            when (it) {
                is LLMStreamFunctionCall -> {
                    appendCall(it.name, it.content)
                }

                is LLMStreamToolCall -> {
                    appendCall(it.name, it.content)
                }

                else -> content.append(it.content)
            }

            consume()

            this.emit(it)
        }

        consume()
    }
}

fun Flow<LLMStreamData>.withCreditConsumer(cost: suspend (Credit) -> Unit): Flow<LLMStreamData> {
    var totalCost = Credit.ZERO
    return flow {
        this@withCreditConsumer.collect {
            when (it) {
                is LLMStreamQuotaMetaData -> {
                    totalCost += it.spent
                }

                else -> {}
            }

            cost(totalCost)

            this.emit(it)
        }
        cost(totalCost)
    }
}

private fun hasCacheSupport(llmProfile: LLMProfileDTO?): Boolean {
    return llmProfile != null && llmProfile.chatDefinition?.parameters.orEmpty().contains(LLMParameters.CachePoints)
}

class LoggableLLMChatMessage(
    val grazieChatMessageDB: GrazieChatMessageDB,
    val params: Array<out Pair<String, String?>>,
    val isContext: Boolean = false,
    var cachePoint: Boolean = false,
) {
    val llmChatMessages = grazieChatMessageDB.llmChatMessages()
    val symbolsCount: Int by lazy { grazieChatMessageDB.text().length }
    var loggedInPromptId: String? = null
    var error: String? = null
    var fromDarkmatter: Boolean = true
    var credit: Credit? = null
    var functions: List<LLMTool> = emptyList()

    fun formatted(acceptMissingParams: Boolean = false): LoggableLLMChatMessage {
        fun String.format(): String {
            return formatTemplate(*params, onMissingReplacement = {
                if (!acceptMissingParams && params.isNotEmpty()) {
                    error("Missing replacement in message: $it")
                }
            })
        }

        return LoggableLLMChatMessage(
            grazieChatMessageDB = when (val message = grazieChatMessageDB) {
                is User -> User(message.message.format())
                is Assistant -> Assistant(
                    message.message?.format(),
                    message.calls
                )

                is Function -> message
                is System -> System(message.message.format())
            },
            params = params,
            isContext = isContext,
            cachePoint = cachePoint
        )
    }
}

fun GrazieChatMessageDB.loggable(vararg params: Pair<String, String?>) =
    LoggableLLMChatMessage(this, params, isContext = false)

fun GrazieChatMessageDB.loggable(isContext: Boolean, vararg params: Pair<String, String?>) =
    LoggableLLMChatMessage(this, params, isContext)

fun LoggableLLMChatMessage.setLogged(promptId: String) = also {
    loggedInPromptId = promptId
}

fun Flow<LLMStreamData>.asText(): Flow<String> {
    return mapNotNull {
        it.content.takeIf { _ -> it is LLMStreamText }
    }
}

sealed class GrazieChatMessageDB {
    abstract fun text(): String

    abstract fun llmChatMessages(): List<LLMChatMessage>

    class User(val message: String) : GrazieChatMessageDB() {
        override fun text(): String = message
        override fun llmChatMessages(): List<LLMChatMessage> = listOf(LLMChatUserMessage(message))
    }

    class Assistant(val message: String?, val calls: List<GrazieChatFunctionCallDB>? = emptyList()) :
        GrazieChatMessageDB() {
        override fun text(): String = buildString {
            message?.let {
                appendLine(message)
            }

            calls?.forEach {
                appendLine(it)
            }
        }

        override fun llmChatMessages(): List<LLMChatMessage> = calls.orEmpty().mapNotNull { call ->
            LLMChatAssistantMessageTool(call.callId ?: return@mapNotNull null, call.functionName, call.content.trim())
        }.takeIf { it.isNotEmpty() } ?: listOfNotNull(message.orEmpty().trim().takeIf { it.isNotBlank() }
            ?.let { LLMChatAssistantMessageText(it) })
    }

    class Function(val callId: String?, val functionName: String, val content: String) : GrazieChatMessageDB() {
        override fun text(): String = "$functionName($content), callId=${callId}"
        override fun llmChatMessages(): List<LLMChatMessage> =
            callId?.let { listOf(LLMChatToolMessage(callId, functionName, content)) }
                ?: listOf(LLMChatFunctionMessage(functionName, content))
    }

    class System(val message: String) : GrazieChatMessageDB() {
        override fun text(): String = message
        override fun llmChatMessages() = listOf(LLMChatSystemMessage(message))
    }
}

class GrazieChatFunctionCallDB(
    val callId: String?,
    val functionName: String,
    val content: String
) {
    override fun toString(): String = "$functionName($content), callId=$callId"
}

@Suppress("unused")
sealed class Either<out ValueType> {
    inline fun <T> flatMap(f: (ValueType) -> Either<T>): Either<T> {
        return when (this) {
            is Error -> Error(message)
            is Value -> when (val result = f(value)) {
                is Error -> Error(result.message)
                is Value -> result
            }
        }
    }

    fun <T> map(f: (ValueType) -> T): Either<T> {
        return when (this) {
            is Error -> Error(message)
            is Value -> Value(f(value))
        }
    }

    class Value<ValueType>(val value: ValueType) : Either<ValueType>()
    class Error<ValueType>(val message: String) : Either<ValueType>()
}

private fun <TBuilder, TReplacement> formatTemplate(
    template: String,
    replacements: Map<String, TReplacement>,
    builder: TBuilder,
    addReplacement: TBuilder.(TReplacement) -> Unit,
    addPart: TBuilder.(String) -> Unit,
    missingReplacement: (String) -> Unit,
) {
    val parts = getTemplateParts(template)

    for (part in parts) {
        when {
            part.startsWith("{") && part.endsWith("}") -> {
                val key = part.drop(1).dropLast(1)
                val replacement = replacements[key]
                if (replacement != null) {
                    builder.addReplacement(replacement)
                } else {
                    missingReplacement(key)
                    builder.addPart(part)
                }
            }

            else -> builder.addPart(part)
        }
    }
}

fun String.formatTemplate(
    vararg replacements: Pair<String, String?>,
    onMissingReplacement: (String) -> Unit = {}
): String {
    val resultBuilder = StringBuilder()

    formatTemplate(
        this,
        replacements.toMap(),
        resultBuilder,
        addReplacement = { append(it) },
        addPart = { append(it) },
        missingReplacement = onMissingReplacement
    )

    return resultBuilder.toString()
}

private fun getTemplateParts(template: String): MutableList<String> {
    val parts = mutableListOf<String>()
    val currentPartBuilder = StringBuilder()

    for (char in template) {
        when (char) {
            '{' -> {
                parts.add(currentPartBuilder.toString())
                currentPartBuilder.clear()
                currentPartBuilder.append(char)
            }

            '}' -> {
                currentPartBuilder.append(char)
                parts.add(currentPartBuilder.toString())
                currentPartBuilder.clear()
            }

            else -> currentPartBuilder.append(char)
        }
    }

    if (currentPartBuilder.isNotEmpty()) {
        parts.add(currentPartBuilder.toString())
    }

    return parts
}