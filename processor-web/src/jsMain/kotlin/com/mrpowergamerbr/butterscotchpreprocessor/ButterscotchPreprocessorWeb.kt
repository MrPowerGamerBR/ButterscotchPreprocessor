package com.mrpowergamerbr.butterscotchpreprocessor

import com.mrpowergamerbr.butterscotchpreprocessor.components.App
import js.objects.unsafeJso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.perfectdreams.luna.modals.ModalManager
import org.jetbrains.compose.web.renderComposable
import web.dom.ElementId
import web.dom.document

class ButterscotchPreprocessorWeb {
    val modalManager = ModalManager {
        onDispose {}
    }

    fun startWorker() {
        val self: dynamic = js("self")
        val scope = CoroutineScope(Dispatchers.Default)

        self.onmessage = { event: dynamic ->
            val msg: dynamic = event.data
            val type = msg.type as String

            when (type) {
                "process" -> {
                    // Receive the data.win ArrayBuffer
                    val arrayBuffer: dynamic = msg.data
                    val bytes = js("new Int8Array(arrayBuffer)").unsafeCast<ByteArray>()

                    // Receive external audio files
                    val externalAudioObj: dynamic = msg.externalAudio
                    val externalAudioFiles = HashMap<String, ByteArray>()
                    val keys = js("Object.keys(externalAudioObj)").unsafeCast<Array<String>>()
                    for (key in keys) {
                        val audioData = externalAudioObj[key]
                        externalAudioFiles[key] = js("new Int8Array(audioData)").unsafeCast<ByteArray>()
                    }

                    scope.launch {
                        try {
                            val result = processDataWin(bytes, externalAudioFiles) { progressMsg ->
                                self.postMessage(
                                    unsafeJso {
                                        this.type = "progress"
                                        this.message = progressMsg
                                    }
                                )
                            }

                            // Send result back with all the byte arrays
                            val resultMsg = unsafeJso<dynamic> {
                                this.type = "result"
                                this.gameName = result.gameName
                                this.clut4 = result.clut4Bin
                                this.clut8 = result.clut8Bin
                                this.textures = result.texturesBin
                                this.atlas = result.atlasBin
                                this.soundBnk = result.soundBnkBin
                                this.sounds = result.soundsBin
                            }

                            self.postMessage(resultMsg)
                        } catch (e: Exception) {
                            self.postMessage(
                                unsafeJso {
                                    this.type = "error"
                                    this.message = (e.message ?: "Unknown error")
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    fun start() {
        val modalWrapperElement = document.createElement("div").also { it.id = ElementId("modal-wrapper") }
        document.body.appendChild(modalWrapperElement)
        modalManager.render(modalWrapperElement)

        renderComposable("root") {
            App(this@ButterscotchPreprocessorWeb)
        }
    }
}

external fun plausible(eventName: String)