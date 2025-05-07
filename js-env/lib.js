export class Memory {
    remove = (key) => {

    }
    add = (key, value) => {

    }
}

const port = 2205
const serverUrl = `http://host.docker.internal:${port}`

export async function reflect(key, ...args) {
    throw new ReflectSignal(key, args)
}

export class ReflectSignal extends Error {
    constructor(key, args) {
        super();
        this.key = key
        this.args = args
        this.type = "ReflectSignal"
    }
}

export async function llm(systemMessage, userMessage, ...args) {
    const body = JSON.stringify({
        systemMessage,
        userMessage,
        args: JSON.stringify(args)
    })
    const result = await fetch(`${serverUrl}/llm`, {
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
    const result = await fetch(`${serverUrl}/reflect`, {
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

function expectOk(response) {
    if (!response.ok) {
        throw new Error(`HTTP error! ${response.url} status: ${response.status}`)
    }
    return response;
}

export async function handleErrorRequest(error) {
    const result = await fetch(`${serverUrl}/error`, {
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
    const result = await fetch(`${serverUrl}/task-completed`, {
        method: "POST"
    })
    expectOk(result)
    return await result.text()
}