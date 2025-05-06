package org.jetbrains.sma

import org.jetbrains.sma.prompt.Variables
import java.nio.file.Paths
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMembers

class Env {
    var cd: String = Paths.get(".").toAbsolutePath().normalize().toString()

    fun vars(): Variables {
        return Variables(this::class.declaredMembers.filterIsInstance<KProperty1<*, *>>().associate { it.name to (it as KProperty1<Env, Any>).get(this) })
    }

    override fun toString(): String {
        return vars().toString()
    }
}