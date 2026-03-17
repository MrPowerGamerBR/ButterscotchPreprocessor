package com.mrpowergamerbr.butterscotchpreprocessor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.html.consumers.delayed
import kotlinx.html.consumers.onFinalizeMap
import kotlinx.html.stream.HTMLStreamBuilder
import java.security.MessageDigest

class ButterscotchPreprocessorWebServer(
    private val jsBundle: String,
    private val cssBundle: String,
    private val butterscotchElf: ByteArray,
    private val iconIco: ByteArray
) {
    private val jsBundleHash = md5Hex(jsBundle.toByteArray())
    private val cssBundleHash = md5Hex(cssBundle.toByteArray())

    fun start() {
        val server = embeddedServer(Netty, port = 8080) {
            install(Compression) {
                gzip()
            }

            routing {
                get("/") {
                    call.respondHtml {
                        head {
                            meta(charset = "UTF-8")
                            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
                            title("Butterscotch Preprocessor")
                            link(rel = "stylesheet", href = "/assets/css/style.css?v=$cssBundleHash")
                            script {
                                defer = true
                                attributes["data-domain"] = "butterscotch.mrpowergamerbr.com"
                                src = "https://web-analytics.perfectdreams.net/js/script.tagged-events.js"
                            }
                            script {
                                unsafe {
                                    raw("""window.plausible = window.plausible || function() { (window.plausible.q = window.plausible.q || []).push(arguments) }""")
                                }
                            }
                            script {
                                attributes["async"] = "true"
                                src = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
                            }
                        }
                        body {
                            div {
                                classes = setOf("sync-with-system-theme")
                                id = "app-wrapper"

                                div {
                                    id = "content-wrapper"

                                    h1 {
                                        text("Butterscotch (PS2)")
                                    }
                                    p {
                                        text("Butterscotch is an open source re-implementation of GameMaker: Studio's runner.")
                                    }
                                    p {
                                        text("Generate a PlayStation 2 ISO for a GameMaker: Studio 1.4.1804 game! (for now it is tailored for Undertale v1.08, screen resizing and input mapping coming soon\u2122)") }
                                    p {
                                        b {
                                            text("Butterscotch Project URL: ")
                                        }
                                        a(href = "https://github.com/MrPowerGamerBR/Butterscotch") {
                                            +"https://github.com/MrPowerGamerBR/Butterscotch"
                                        }
                                    }

                                    hr {}

                                    // Google AdSense ad
                                    div {
                                        classes = setOf("centered-text")
                                        ins {
                                            classes = setOf("adsbygoogle")
                                            attributes["style"] = "display: block;"
                                            attributes["data-ad-client"] = "ca-pub-9989170954243288"
                                            attributes["data-ad-slot"] = "1872097516"
                                            attributes["data-ad-format"] = "auto"
                                            attributes["data-full-width-responsive"] = "true"
                                        }
                                        script {
                                            unsafe {
                                                raw("""(adsbygoogle = window.adsbygoogle || []).push({});""")
                                            }
                                        }
                                    }

                                    hr {}

                                    // Compose HTML mounts here
                                    div {
                                        id = "root"
                                    }
                                }
                            }
                            script(src = "/assets/js/processor-web.js?v=$jsBundleHash") {}
                        }
                    }
                }

                get("/assets/js/processor-web.js") {
                    call.respondText(
                        jsBundle,
                        contentType = ContentType.Application.JavaScript
                    )
                }

                get("/assets/css/style.css") {
                    call.respondText(
                        cssBundle,
                        contentType = ContentType.Text.CSS
                    )
                }

                get("/web/butterscotch.elf") {
                    call.respondBytes(
                        butterscotchElf,
                        contentType = ContentType.Application.OctetStream
                    )
                }

                get("/web/ICON.ICO") {
                    call.respondBytes(
                        iconIco,
                        contentType = ContentType.Application.OctetStream
                    )
                }
            }
        }

        server.start(wait = true)
    }
}

private fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

private const val AVERAGE_PAGE_SIZE = 32768

suspend fun ApplicationCall.respondHtml(status: HttpStatusCode? = null, content: HTML.() -> (Unit)) {
    val output = StringBuilder(AVERAGE_PAGE_SIZE)
    output.append("<!doctype html>")

    val builder = HTMLStreamBuilder(
        output,
        prettyPrint = false,
        xhtmlCompatible = false
    ).onFinalizeMap { sb, _ -> sb.toString() }.delayed()

    builder.html {
        content.invoke(this)
    }

    this.respondText(
        output.toString(),
        ContentType.Text.Html,
        status = status
    )
}