package org.jetbrains.sma

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    ProcessBuilder(
        "docker", "run",
        "--name", "self-modifying-agent-nodejs",
        "--network", "host",
    )

    GlobalScope.launch {
        awaitServer()

//        task.prompt = "Add info that this repository is created by Vladislav Saifulin"
//        task.initialGeneration()
//        task.launchMainLoop()
    }

    startServer()
}