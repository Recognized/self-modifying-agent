export class Memory {
    remove = (key) => {

    }
    add = (key, value) => {

    }
}

const port = 2205
const serverUrl = `http://localhost:${port}`

export async function reflect(key, ...args) {
    throw ReflectSignal(key, args)
}

export class ReflectSignal extends Error {
    constructor(key, args) {
        super();
        this.key = key
        this.args = args
    }
}

export async function llm(systemMessage, userMessage, ...args) {
    const result = await fetch(`${serverUrl}/llm`, {
        method: "POST",
        body: JSON.stringify({
            systemMessage,
            userMessage,
            args
        })
    })
    return await result.text()
}

export async function reflectRequest(key, args) {
    await fetch(`${serverUrl}/reflect`, {
        method: "POST",
        body: JSON.stringify({
            key,
            args
        })
    })
}

export async function handleErrorRequest(error) {
    await fetch(`${serverUrl}/error`, {
        method: "POST",
        body: JSON.stringify({
            error: error.name,
            message: error.message,
            stack: error.stack
        })
    })
}

export async function taskCompleted() {
    const result = await fetch(`${serverUrl}/task-completed`, {
        method: "POST"
    })
    return await result.text()
}