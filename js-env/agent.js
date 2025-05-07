import {main} from "./main.js";
import {handleErrorRequest, reflect, taskCompleted} from "./lib.js";

const originalLog = console.log;
const logs = []
console.log = (...args) => {
    originalLog(...args);
    logs.push(args.map(a => typeof a === "string" ? a : JSON.stringify(a)).join(" "));
}

async function run() {
    try {
        await main();
        await reflect("review-logs-xyz", {
            message: "The program finished execution. Please, review what the program printed to user.",
            logs: logs
        })
        await taskCompleted();
    } catch (e) {
        await handleErrorRequest(e);
    }
}

await run();
