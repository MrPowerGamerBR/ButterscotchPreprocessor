@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.mrpowergamerbr.butterscotchpreprocessor

import com.mrpowergamerbr.butterscotchpreprocessor.network.AudioDataWire
import com.mrpowergamerbr.butterscotchpreprocessor.network.C2SDecodeAudioPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.C2SEnvelope
import com.mrpowergamerbr.butterscotchpreprocessor.network.C2SPacketType
import com.mrpowergamerbr.butterscotchpreprocessor.network.C2SProcessDataWin
import com.mrpowergamerbr.butterscotchpreprocessor.network.S2CDecodeAudioPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.jsObjectToByteArrayMap
import com.mrpowergamerbr.butterscotchpreprocessor.network.s2cDecodeAudioPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.s2cEnvelope
import com.mrpowergamerbr.butterscotchpreprocessor.network.s2cErrorPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.s2cProcessResultPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.s2cProgressPacket
import com.mrpowergamerbr.butterscotchpreprocessor.network.toAudioData
import kotlin.js.JsAny
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import web.navigator.navigator

class ButterWorkerServer {
    fun startWorker() {
        val self: dynamic = js("self")
        val scope = CoroutineScope(Dispatchers.Default)

        self.onmessage = { event: dynamic ->
            val envelope = event.data.unsafeCast<C2SEnvelope>()
            val id = envelope.id
            val packet = envelope.packet

            when (packet.type) {
                C2SPacketType.DECODE_AUDIO -> {
                    val request = packet.unsafeCast<C2SDecodeAudioPacket>()
                    val bytes = request.data.unsafeCast<ByteArray>()
                    val decoded = parseWav(bytes) ?: parseOgg(bytes)
                    self.postMessage(
                        s2cEnvelope(id, s2cDecodeAudioPacket(decoded), terminal = true).unsafeCast<JsAny>()
                    )
                }

                C2SPacketType.PROCESS_DATA_WIN -> {
                    val request = packet.unsafeCast<C2SProcessDataWin>()
                    val dataWinBytes = request.data.unsafeCast<ByteArray>()
                    val externalAudioFiles = jsObjectToByteArrayMap(request.externalAudioFiles)
                    val audioGroupFiles = jsObjectToByteArrayMap(request.audioGroupFiles)
                        .mapKeys { it.key.toInt() }
                    val musFiles = jsObjectToByteArrayMap(request.musFiles)
                    val force4bppPatterns = request.force4bppPatterns.toList()

                    scope.launch {
                        // Spawn a worker pool so audio decoding can run in parallel across real threads
                        val workerCount = navigator.hardwareConcurrency.toInt().coerceIn(2, 6)
                        val workers = List(workerCount) { ButterWorkerClient() }
                        var nextWorker = 0

                        try {
                            val result = processDataWin(
                                dataWinBytes,
                                externalAudioFiles,
                                audioGroupFiles,
                                musFiles,
                                force4bppPatterns,
                                { audioBytes ->
                                    val pickedWorker = workers[nextWorker++ % workers.size]
                                    val response = pickedWorker.sendPacket(
                                        com.mrpowergamerbr.butterscotchpreprocessor.network.c2sDecodeAudioPacket(audioBytes)
                                    ).unsafeCast<S2CDecodeAudioPacket>()
                                    response.audio?.unsafeCast<AudioDataWire>()?.toAudioData()
                                }
                            ) { progressMsg ->
                                self.postMessage(
                                    s2cEnvelope(id, s2cProgressPacket(progressMsg), terminal = false).unsafeCast<JsAny>()
                                )
                            }

                            self.postMessage(
                                s2cEnvelope(
                                    id,
                                    s2cProcessResultPacket(
                                        result.gameName,
                                        result.clut4Bin,
                                        result.clut8Bin,
                                        result.texturesBin,
                                        result.atlasBin,
                                        result.soundBnkBin,
                                        result.soundsBin,
                                    ),
                                    terminal = true,
                                ).unsafeCast<JsAny>()
                            )
                        } catch (e: Exception) {
                            self.postMessage(
                                s2cEnvelope(id, s2cErrorPacket(e.message ?: "Unknown error"), terminal = true).unsafeCast<JsAny>()
                            )
                        } finally {
                            workers.forEach { it.terminate() }
                        }
                    }
                }
            }
            Unit
        }
    }
}
