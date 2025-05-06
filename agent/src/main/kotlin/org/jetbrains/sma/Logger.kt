package org.jetbrains.sma

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

fun logger(owner: KClass<*>): JVMLogger = defaultLogger(owner)

private fun defaultLogger(clazz: KClass<*>): JVMLogger {
    val ourClass = clazz.java
    val parentClass = ourClass.declaringClass
    // first check if parentClass exists because isCompanion from Kotlin reflection is slow
    val javaClazz = if (parentClass != null && clazz.isCompanion) {
        parentClass
    } else {
        ourClass
    }
    val slf4jLogger = LoggerFactory.getLogger(javaClazz)
    return JVMLogger(slf4jLogger)
}

open class JVMLogger(val sl4jLogger: Logger) {
    val isTraceEnabled: Boolean get() = sl4jLogger.isTraceEnabled
    val isDebugEnabled: Boolean get() = sl4jLogger.isDebugEnabled
    val isInfoEnabled: Boolean get() = sl4jLogger.isInfoEnabled
    val isWarnEnabled: Boolean get() = sl4jLogger.isWarnEnabled
    val isErrorEnabled: Boolean get() = sl4jLogger.isErrorEnabled

    inline fun trace(msg: () -> Any?) {
        if (isTraceEnabled) {
            val msgStr = msg()
            sl4jLogger.trace(msgStr?.toString())
        }
    }

    inline fun debug(msg: () -> Any?) {
        if (isDebugEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.debug(msgStr)
        }
    }

    inline fun info(msg: () -> Any?) {
        if (isInfoEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.info(msgStr)
        }
    }

    inline fun warn(msg: () -> Any?) {
        if (isWarnEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.warn(msgStr)
        }
    }

    inline fun error(msg: () -> Any?) {
        if (isErrorEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.error(msgStr)
        }
    }

    inline fun trace(t: Throwable, msg: () -> Any?) {
        if (isTraceEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.trace(msgStr, t)
        }
    }

    inline fun debug(t: Throwable, msg: () -> Any?) {
        if (isDebugEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.debug(msgStr, t)
        }
    }

    inline fun info(t: Throwable, msg: () -> Any?) {
        if (isInfoEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.info(msgStr, t)
        }
    }

    inline fun warn(t: Throwable, msg: () -> Any?) {
        if (isWarnEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.warn(msgStr, t)
        }
    }

    inline fun error(t: Throwable, msg: () -> Any?) {
        if (isErrorEnabled) {
            val msgStr = msg()?.toString()
            sl4jLogger.error(msgStr, t)
        }
    }
}
