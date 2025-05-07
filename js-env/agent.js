import {main} from "./main.js";
import {handleErrorRequest, taskCompleted} from "./lib.js";

async function run() {
    try {
        await main();
        await taskCompleted();
    } catch (e) {
        await handleErrorRequest(e);
    }
}

await run();
