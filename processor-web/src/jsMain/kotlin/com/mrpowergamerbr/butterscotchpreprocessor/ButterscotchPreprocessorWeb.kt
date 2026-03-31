package com.mrpowergamerbr.butterscotchpreprocessor

import com.mrpowergamerbr.butterscotchpreprocessor.components.App
import js.buffer.ArrayBufferLike
import js.objects.Object
import js.objects.unsafeJso
import js.typedarrays.Int8Array
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.perfectdreams.luna.modals.ModalManager
import org.jetbrains.compose.web.renderComposable
import web.dom.ElementId
import web.dom.document

class ButterscotchPreprocessorWeb {
    companion object {
        const val CHECKMARK_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\" fill=\"currentColor\" viewBox=\"0 0 256 256\"><path d=\"M232.49,80.49l-128,128a12,12,0,0,1-17,0l-56-56a12,12,0,1,1,17-17L96,183,215.51,63.51a12,12,0,0,1,17,17Z\"></path></svg>"
        const val EYE_DROPPER_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\" fill=\"currentColor\" viewBox=\"0 0 256 256\"><path d=\"M224,67.3a35.79,35.79,0,0,0-11.26-25.66c-14-13.28-36.72-12.78-50.62,1.13L138.8,66.2a24,24,0,0,0-33.14.77l-5,5a16,16,0,0,0,0,22.64l2,2.06-51,51a39.75,39.75,0,0,0-10.53,38l-8,18.41A13.68,13.68,0,0,0,36,219.3a15.92,15.92,0,0,0,17.71,3.35L71.23,215a39.89,39.89,0,0,0,37.06-10.75l51-51,2.06,2.06a16,16,0,0,0,22.62,0l5-5a24,24,0,0,0,.74-33.18l23.75-23.87A35.75,35.75,0,0,0,224,67.3ZM97,193a24,24,0,0,1-24,6,8,8,0,0,0-5.55.31l-18.1,7.91L57,189.41a8,8,0,0,0,.25-5.75A23.88,23.88,0,0,1,63,159l51-51,33.94,34Z\"></path></svg>"
    }
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

                    // Receive audiogroup files
                    val audioGroupObj: dynamic = msg.audioGroups
                    val audioGroupFiles = HashMap<Int, ByteArray>()
                    if (audioGroupObj != null && audioGroupObj != undefined) {
                        val agKeys = Object.keys(audioGroupObj)
                        for (key in agKeys) {
                            val groupData = audioGroupObj[key]
                            audioGroupFiles[key.toInt()] = Int8Array(groupData as ArrayBufferLike).unsafeCast<ByteArray>()
                        }
                    }

                    scope.launch {
                        try {
                            val result = processDataWin(bytes, externalAudioFiles, audioGroupFiles) { progressMsg ->
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