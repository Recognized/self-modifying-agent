import {main} from "./main.js";
import {handleErrorRequest, reflectRequest, ReflectSignal, taskCompleted} from "./lib.js";

async function run() {
    try {
        await main();
        await taskCompleted();
    } catch (e) {
        if (e.type === "ReflectSignal") {
            console.log("Caught reflection signal");
            await reflectRequest(e.key, e.args);
        } else {
            await handleErrorRequest(e);
        }
    }
}

await run();
