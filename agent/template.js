import {llm, reflect} from "./lib.js";
import fs from "fs";

const globalMemory = {}

// Must be exported
export async function main() {

    console.log("... doing something")

    const fileBefore = fs.readFileSync("hello-world.txt", "utf-8");
    const result = await llm("file-edit-1", "You are a file editor...", "Please, edit this file to include...", `File:\n${fileBefore}`)
    fs.writeFileSync("hello-world.txt", result);

    await reflect("final-thought", globalMemory, "Have we completed the task?")
}