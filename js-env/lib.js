const port = 2205

export async function reflect(key, ...args) {
    checkKey(key)
    if (await shouldReflectRequest(key, args)) {
        await reflectRequest(key, args);
        process.exit(0)
    } else {
        console.log(`Already processed reflection point: ${key}`)
    }
}

export class ReflectSignal extends Error {
    constructor(key, args) {
        super();
        this.key = key
        this.args = args
        this.type = "ReflectSignal"
    }
}

export async function llm(key, systemMessage, userMessage, ...args) {
    checkKey(key)
    const body = JSON.stringify({
        key,
        systemMessage,
        userMessage,
        args: JSON.stringify(args)
    })
    const result = await fetch(`${await serverUrl()}/llm`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: body
    })
    expectOk(result)
    return await result.text()
}

export async function reflectRequest(key, args) {
    checkKey(key)
    const result = await fetch(`${await serverUrl()}/reflect`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            key,
            args: JSON.stringify(args)
        })
    })
    expectOk(result)
}

export async function shouldReflectRequest(key, args) {
    const result = await fetch(`${await serverUrl()}/should-reflect`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            key,
            args: JSON.stringify(args)
        })
    })
    expectOk(result)
    return (await result.text()) === "true"
}

let serverUrlCached = null

export async function serverUrl() {
    if (serverUrlCached) {
        return serverUrlCached
    }
    while (true) {
        let r1 = null
        let r2 = null
        try {
            r1 = await fetch(`http://localhost:${port}/status`, {
                method: "GET"
            })
        } catch (e) {
            // ignore
        }
        try {
            r2 = await fetch(`http://host.docker.internal:${port}/status`, {
                method: "GET"
            })
        } catch (e) {
            // ignore
        }
        if (r1 && r1.ok) {
            serverUrlCached = `http://localhost:${port}`
            return `http://localhost:${port}`
        }
        if (r2 && r2.ok) {
            serverUrlCached = `http://host.docker.internal:${port}`
            return `http://host.docker.internal:${port}`
        }
        await new Promise(r => setTimeout(r, 1000))
    }
}

function expectOk(response) {
    if (!response.ok) {
        throw new Error(`HTTP error! ${JSON.stringify(response)}`)
    }
    return response;
}

export async function handleErrorRequest(error) {
    const result = await fetch(`${await serverUrl()}/error`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({
            error: error.name,
            message: error.message,
            stack: error.stack
        })
    })
    expectOk(result)
}

export async function taskCompleted() {
    const result = await fetch(`${await serverUrl()}/task-completed`, {
        method: "POST"
    })
    expectOk(result)
    return await result.text()
}

function checkKey(key) {
    if (!key) {
        throw new Error("First `key` parameter is required")
    }
    if (typeof key !== "string") {
        throw new Error("First `key` parameter must be a string")
    }
    if (key.length === 0) {
        throw new Error("First `key` parameter must not be empty")
    }
}