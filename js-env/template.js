import {llm, Memory, reflect} from "./lib.js";
import fs from "fs";

const globalMemory = new Memory()

async function main() {

    console.log("... doing something")

    const fileBefore = fs.readFileSync("hello-world.txt", "utf-8");
    const result = llm("You are a file editor...", "Please, edit this file to include...", `File:\n${fileBefore}`)
    fs.writeFileSync("hello-world.txt", result);

    reflect(globalMemory, "Have we completed the task?")
}