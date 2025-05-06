import {main} from "./main.js";
import {handleErrorRequest, reflectRequest, ReflectSignal, taskCompleted} from "./lib.js";

try {
    await main()
    await taskCompleted()
} catch (e) {
    if (e instanceof ReflectSignal) {
        await reflectRequest(e.key, e.args)
    } else {
        await handleErrorRequest(e)
    }
}
