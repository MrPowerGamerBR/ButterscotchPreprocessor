package com.mrpowergamerbr.butterscotchpreprocessor

fun main() {
    // Detect if we are running inside a Web Worker (no document available)
    val isWorker = js("typeof document === 'undefined'") as Boolean

    val m = ButterscotchPreprocessorWeb()

    if (isWorker) {
        m.startWorker()
    } else {
        m.start()
    }
}