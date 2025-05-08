package org.jetbrains.sma

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.KCallable
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMembers

private val log = logger(Utils::class)

class Utils

val jackson = jacksonObjectMapper().apply {
    kotlinModule()
}

fun print(any: Any): String {
    return jackson.writerWithDefaultPrettyPrinter().writeValueAsString(any)
}

inline fun <reified T : Any> String.asFunction(): (T) -> String {
    val argFields = T::class.declaredMembers.filter {
        it is KProperty1<*, *>
    }
    val template = this
    return { args ->
        template.formatTemplate(
            *argFields.map {
                val value = (it as KProperty1<T, Any>).get(args)
                it.name to (if (value is JsonElement) print(value) else value.toString())
            }.toTypedArray(),
            onMissingReplacement = {
                error("Some replacements ($it) are missing in the template: \"${template.take(50)}...\"")
            })
    }
}

fun <T> ((T) -> String).map(fn: (String) -> String): (T) -> String {
    return { fn(this(it)) }
}

inline fun <T> JVMLogger.catch(block: () -> T): T? {
    return try {
        block()
    } catch (ex: Throwable) {
        error { ex.stackTraceToString() }
        null
    }
}

inline fun <T> JVMLogger.catchAndFail(block: () -> T): T {
    return try {
        block()
    } catch (ex: Throwable) {
        error { ex.stackTraceToString() }
        error(ex)
    }
}

val KCallable<*>.template get() = "{${name}}"

val templateJs =
    log.catchAndFail { Utils::class.java.getResourceAsStream("/static/template.js")!!.bufferedReader().readText() }