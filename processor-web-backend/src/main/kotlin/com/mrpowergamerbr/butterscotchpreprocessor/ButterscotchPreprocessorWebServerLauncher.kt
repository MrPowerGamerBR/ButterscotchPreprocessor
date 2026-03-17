package com.mrpowergamerbr.butterscotchpreprocessor

object ButterscotchPreprocessorWebServerLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val jsBundle = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/js/processor-web.js")!!
            .readBytes()
            .toString(Charsets.UTF_8)

        val cssBundle = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/css/style.css")!!
            .readBytes()
            .toString(Charsets.UTF_8)

        val butterscotchElf = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/butterscotch.elf")!!
            .readBytes()

        val iconIco = ButterscotchPreprocessorWebServer::class.java.getResourceAsStream("/web/ICON.ICO")!!
            .readBytes()

        val server = ButterscotchPreprocessorWebServer(jsBundle, cssBundle, butterscotchElf, iconIco)
        server.start()
    }
}