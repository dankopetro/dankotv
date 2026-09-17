package tv.danko.app.ui.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/**
 * F4-rec v2 (solo teléfono): dump .ts de mpv → .mp4 H264+AAC con el
 * encoder de HARDWARE del teléfono (MediaCodec, presente en todos).
 *
 * Por qué existe (16/09): el remux por copia (Extractor→Muxer) es frágil
 * con estos streams: los PTS del proveedor vienen rotos (escala mal,
 * saltos atrás) y eso producía, según la variante, duración 0s, 48s por
 * 7s, video congelado o cadencia "tartamuda". Acá no se copia ningún
 * timestamp: el decoder ordena a presentación y el encoder genera PTS
 * limpios y monótonos. Resultado: fluido, duración exacta por conteo de
 * frames, headers válidos (SPS del propio encoder).
 *
 * Presupuesto (pedido del usuario): 10s → máx 4MB, calidad de teléfono.
 * Video 3.0 Mbps + audio 128 kbps ≈ 3.9MB cada 10s. Se conserva la
 * resolución de origen (típico 1080p/576p): a 3 Mbps es calidad de
 * teléfono sin reescalado (menos código, menos riesgo).
 */
object ClipTranscoder {

    const val VIDEO_BITRATE = 3_000_000
    const val AUDIO_BITRATE = 128_000
    private const val DEQ_TIMEOUT_US = 10_000L
    private const val MAX_WALL_MS = 120_000L
    private const val KEYFRAME_EVERY_S = 2
    /** Calentamiento del decoder (segundos a decodificar y tirar) para que
     *  el primer frame útil salga limpio aunque el corte sea mid-GOP. */
    private const val WARMUP_S = 4L
    /** Si el decoder no reporta su color-format, no juntar más crudos que esto. */
    private const val MAX_RAW_QUEUE = 64

    data class Quotas(
        val feedStartV: Long, // desde qué sample alimentar el decoder de video
        val skipOutV: Long, // outputs decodificados a tirar (warmup)
        val targetV: Long, // frames de video a codificar (= duración exacta)
        val skipA: Long, // samples de audio a saltear
        val targetA: Long, // frames de audio AAC a codificar
        val fpsLong: Long,
        val audioRate: Int,
    )

    /** Matemática pura (testeable JVM): ventana exacta al final del archivo. */
    fun computeQuotas(
        vc: Long,
        ac: Long,
        audioRate: Int,
        keepUs: Long,
        fps: Double,
    ): Quotas {
        val f = fps.coerceIn(1.0, 120.0)
        val fpsLong = f.toLong().coerceAtLeast(1)
        val targetV = if (keepUs > 0 && vc > 0) {
            (keepUs * f / 1_000_000L).toLong().coerceIn(1L, vc)
        } else {
            vc.coerceAtLeast(0)
        }
        val skipV = (vc - targetV).coerceAtLeast(0)
        // Ventana de video [skipV, skipV+targetV). Se alimenta desde antes
        // (warmup) pero solo se codifica la ventana.
        val feedStartV = (skipV - WARMUP_S * fpsLong).coerceAtLeast(0)
        val skipOutV = skipV - feedStartV
        val videoDurUs = if (fpsLong > 0) targetV * 1_000_000L / fpsLong else 0L
        val targetA = if (ac > 0 && audioRate > 0) {
            (videoDurUs * audioRate / 1_000_000L / 1024L).coerceIn(0L, ac)
        } else {
            0L
        }
        val skipA = (ac - targetA).coerceAtLeast(0)
        return Quotas(feedStartV, skipOutV, targetV, skipA, targetA, fpsLong, audioRate)
    }

    private data class Enc(val bytes: ByteArray, val pts: Long, val flags: Int)

    /** Color de entrada que el encoder acepta (Flexible si hay). */
    private fun pickEncoderColor(mime: String): Int {
        var c: MediaCodec? = null
        return try {
            c = MediaCodec.createDecoderByType(mime)
            val fmts = c!!.codecInfo.getCapabilitiesForType(mime).colorFormats
            fmts.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible }
                ?: fmts.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar }
                ?: fmts.firstOrNull { it == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar }
                ?: fmts.first()
        } catch (_: Exception) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        } finally {
            try {
                c?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun makeVideoEncoder(w: Int, h: Int, fpsInt: Int, color: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fpsInt.coerceIn(1, 60))
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_EVERY_S)
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /**
     * Transcodifica [src] (.ts del dump) a [dst] (.mp4). keepUs = últimos
     * microsegundos a conservar (lo que el usuario grabó); 0 = todo.
     * Devuelve true si el .mp4 quedó válido.
     */
    fun transcode(
        src: File,
        dst: File,
        keepUs: Long = 0,
        fps: Double = 30.0,
        log: (String) -> Unit = {},
    ): Boolean {
        var extractor: MediaExtractor? = null
        var vDec: MediaCodec? = null
        var vEnc: MediaCodec? = null
        var aDec: MediaCodec? = null
        var aEnc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            if (!src.exists() || src.length() == 0L) return false
            extractor = MediaExtractor()
            extractor.setDataSource(src.absolutePath)
            var vIdx = -1
            var aIdx = -1
            var vFormat: MediaFormat? = null
            var aFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && vIdx < 0) {
                    vIdx = i
                    vFormat = f
                } else if (mime.startsWith("audio/") && aIdx < 0) {
                    aIdx = i
                    aFormat = f
                }
            }
            if (vIdx < 0 || vFormat == null) {
                log("transcode: sin video en ${src.name}")
                return false
            }
            val width = vFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = vFormat.getInteger(MediaFormat.KEY_HEIGHT)

            // Conteo barato (sin decode) para la ventana exacta.
            var vc = 0L
            var ac = 0L
            run {
                var cnt: MediaExtractor? = null
                try {
                    cnt = MediaExtractor()
                    cnt.setDataSource(src.absolutePath)
                    cnt.selectTrack(vIdx)
                    if (aIdx >= 0) cnt.selectTrack(aIdx)
                    while (true) {
                        val t = cnt.sampleTrackIndex
                        if (t < 0) break
                        if (t == vIdx) vc++ else ac++
                        cnt.advance()
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        cnt?.release()
                    } catch (_: Exception) {
                    }
                }
            }
            if (vc <= 0) {
                log("transcode: 0 frames de video")
                return false
            }
            val audioRate = try {
                aFormat?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 48000
            } catch (_: Exception) {
                48000
            }.coerceIn(8000, 192000)
            val q = computeQuotas(vc, ac, audioRate, keepUs, fps)
            log("transcode: v=$vc a=$ac ventana v[${q.feedStartV},+${q.targetV}] a[skip=${q.skipA},+${q.targetA}] fps=${q.fpsLong}")

            extractor.selectTrack(vIdx)
            if (aIdx >= 0) extractor.selectTrack(aIdx)
            // Avanzar a los puntos de alimentación.
            var fedV = 0L
            var fedA = 0L
            while (fedV < q.feedStartV || (aIdx >= 0 && fedA < q.skipA)) {
                val t = extractor.sampleTrackIndex
                if (t < 0) break
                if (t == vIdx) {
                    if (fedV < q.feedStartV) {
                        fedV++
                        extractor.advance()
                    } else {
                        break
                    }
                } else {
                    if (fedA < q.skipA) {
                        fedA++
                        extractor.advance()
                    } else {
                        break
                    }
                }
            }

            vDec = MediaCodec.createDecoderByType(
                vFormat.getString(MediaFormat.KEY_MIME)!!,
            )
            vDec.configure(vFormat, null, null, 0)
            vDec.start()

            val hasAudio = aIdx >= 0 && aFormat != null && q.targetA > 0
            if (hasAudio) {
                val aCh = try {
                    aFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } catch (_: Exception) {
                    2
                }.coerceIn(1, 8)
                aDec = MediaCodec.createDecoderByType(
                    aFormat.getString(MediaFormat.KEY_MIME)!!,
                )
                aDec.configure(aFormat, null, null, 0)
                aDec.start()
                val aEncFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioRate, aCh,
                )
                aEncFmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                aEnc.configure(aEncFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                aEnc.start()
            }

            val vBuf = ByteBuffer.allocate(4 * 1024 * 1024)
            val aBuf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            val rawQueue = ArrayDeque<Pair<ByteArray, Int>>()
            var vEncColor = pickEncoderColor(MediaFormat.MIMETYPE_VIDEO_AVC)
            var vColorConfirmed = false
            var vInEos = false // EOS encolado al decoder de video
            var vDecEosOut = false // decoder de video drenó EOS
            var vDecOutCount = 0L // outputs del decoder (para el warmup)
            var vEncInIdx = 0L // frames codificados (define duración exacta)
            var vEncEosQueued = false
            var vEncEosOut = false
            val vEncSamples = ArrayList<Enc>()
            var vEncFormat: MediaFormat? = null

            var aInEos = false
            var aDecEosOut = false
            var aEncInIdx = 0L
            var aEncEosQueued = false
            var aEncEosOut = false
            val aEncSamples = ArrayList<Enc>()
            var aEncFormat: MediaFormat? = null

            val t0 = SystemClock.uptimeMillis()
            var extractorDoneV = false
            var extractorDoneA = !hasAudio

            fun feedDecoder(dec: MediaCodec, isVideo: Boolean) {
                if (isVideo) {
                    if (vInEos) return
                } else {
                    if (aInEos) return
                }
                val wantTrack = if (isVideo) vIdx else aIdx
                // El extractor es compartido: solo avanzar en nuestro turno.
                var guard = 0
                while (guard++ < 64) {
                    val t = extractor!!.sampleTrackIndex
                    if (t < 0) {
                        val bi = dec.dequeueInputBuffer(DEQ_TIMEOUT_US)
                        if (bi >= 0) {
                            dec.queueInputBuffer(
                                bi, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            if (isVideo) {
                                vInEos = true
                                extractorDoneV = true
                            } else {
                                aInEos = true
                                extractorDoneA = true
                            }
                        }
                        return
                    }
                    if (t != wantTrack) return // turno del otro track
                    val buf = if (isVideo) vBuf else aBuf
                    buf.clear()
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) {
                        extractor.advance()
                        continue
                    }
                    val bi = dec.dequeueInputBuffer(DEQ_TIMEOUT_US)
                    if (bi < 0) return
                    val ib = dec.getInputBuffer(bi)!!
                    if (ib.remaining() < sz) {
                        // Sample más grande que el buffer: no bloquear, reintentar.
                        log("transcode: input chico para sample $sz, se saltea")
                        extractor.advance()
                        return
                    }
                    buf.position(0)
                    buf.limit(sz)
                    ib.put(buf)
                    dec.queueInputBuffer(bi, 0, sz, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                    return
                }
            }

            while (true) {
                if (SystemClock.uptimeMillis() - t0 > MAX_WALL_MS) {
                    log("transcode: timeout, se aborta")
                    return false
                }
                if (vEncEosOut && (!hasAudio || aEncEosOut)) break
                if (!vInEos || !vDecEosOut) feedDecoder(vDec, true)
                if (hasAudio && (!aInEos || !aDecEosOut)) feedDecoder(aDec!!, false)

                // Drenar decoder de video → cola de crudos.
                run {
                    val out = vDec.dequeueOutputBuffer(info, DEQ_TIMEOUT_US)
                    if (out >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            if (!vColorConfirmed) {
                                try {
                                    val actual = vDec.outputFormat
                                        .getInteger(MediaFormat.KEY_COLOR_FORMAT)
                                    vColorConfirmed = true
                                    // Recrear encoder solo si aún no tragó nada.
                                    if (actual != vEncColor && vEncInIdx == 0L) {
                                        log("transcode: color decoder=$actual, se recrea encoder")
                                        vEncColor = actual
                                        try {
                                            vEnc?.stop()
                                        } catch (_: Exception) {
                                        }
                                        try {
                                            vEnc?.release()
                                        } catch (_: Exception) {
                                        }
                                        vEnc = null
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            val bb = vDec.getOutputBuffer(out)!!
                            val bytes = ByteArray(info.size)
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            bb.get(bytes)
                            vDecOutCount++
                            if (vDecOutCount > q.skipOutV && rawQueue.size < MAX_RAW_QUEUE * 4) {
                                rawQueue.addLast(bytes to info.size)
                            }
                        }
                        try {
                            vDec.releaseOutputBuffer(out, false)
                        } catch (_: Exception) {
                        }
                        if (eos) vDecEosOut = true
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            val actual = vDec.outputFormat
                                .getInteger(MediaFormat.KEY_COLOR_FORMAT)
                            vColorConfirmed = true
                            if (actual != vEncColor && vEncInIdx == 0L) {
                                log("transcode: color (fmt) decoder=$actual, se usa")
                                vEncColor = actual
                                try {
                                    vEnc?.stop()
                                } catch (_: Exception) {
                                }
                                try {
                                    vEnc?.release()
                                } catch (_: Exception) {
                                }
                                vEnc = null
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // Crear encoder cuando hay color confirmado o cola suficiente.
                if (vEnc == null && (vColorConfirmed || rawQueue.size >= MAX_RAW_QUEUE || vDecEosOut)) {
                    try {
                        vEnc = makeVideoEncoder(width, height, q.fpsLong.toInt(), vEncColor)
                        vEnc!!.start()
                        log("transcode: video encoder listo color=$vEncColor ${width}x$height")
                    } catch (e: Exception) {
                        log("transcode: video encoder falló: $e")
                        return false
                    }
                }
                // Alimentar encoder con crudos (pts secuencial limpio).
                // Solo con color confirmado (o cola forzada): evita basura.
                if (vEnc != null && (vColorConfirmed || rawQueue.size >= MAX_RAW_QUEUE)) {
                    while (rawQueue.isNotEmpty() && vEncInIdx < q.targetV) {
                        val bi = vEnc!!.dequeueInputBuffer(DEQ_TIMEOUT_US)
                        if (bi < 0) break
                        val (bytes, sz) = rawQueue.removeFirst()
                        val ib = vEnc!!.getInputBuffer(bi)!!
                        if (ib.remaining() < sz) {
                            log("transcode: crudo no entra al encoder, se tira")
                            continue
                        }
                        ib.put(bytes, 0, sz)
                        vEnc!!.queueInputBuffer(bi, 0, sz, vEncInIdx * 1_000_000L / q.fpsLong, 0)
                        vEncInIdx++
                    }
                    if (!vEncEosQueued && (vEncInIdx >= q.targetV || (vDecEosOut && rawQueue.isEmpty()))) {
                        val bi = vEnc!!.dequeueInputBuffer(DEQ_TIMEOUT_US)
                        if (bi >= 0) {
                            vEnc!!.queueInputBuffer(
                                bi, 0, 0, vEncInIdx * 1_000_000L / q.fpsLong,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            vEncEosQueued = true
                        }
                    }
                    // Drenar encoder → lista (el CSD se saltea: ya va en el formato).
                    var drainGuard = 0
                    while (drainGuard++ < 32) {
                        val out = vEnc!!.dequeueOutputBuffer(info, 0)
                        if (out >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !cfg) {
                                val bb = vEnc!!.getOutputBuffer(out)!!
                                val bytes = ByteArray(info.size)
                                bb.position(info.offset)
                                bb.limit(info.offset + info.size)
                                bb.get(bytes)
                                vEncSamples.add(Enc(bytes, info.presentationTimeUs, info.flags))
                            }
                            try {
                                vEnc!!.releaseOutputBuffer(out, false)
                            } catch (_: Exception) {
                            }
                            if (eos) {
                                vEncEosOut = true
                                break
                            }
                        } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            try {
                                vEncFormat = vEnc!!.outputFormat
                            } catch (_: Exception) {
                            }
                        } else {
                            break
                        }
                    }
                }

                // Audio: decode → encode directo (formato conocido de entrada).
                if (hasAudio && !aEncEosOut) {
                    var audioGuard = 0
                    while (audioGuard++ < 32) {
                        val out = aDec!!.dequeueOutputBuffer(info, 0)
                        if (out >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.size > 0 && aEncInIdx < q.targetA) {
                                val bb = aDec!!.getOutputBuffer(out)!!
                                val bytes = ByteArray(info.size)
                                bb.position(info.offset)
                                bb.limit(info.offset + info.size)
                                bb.get(bytes)
                                val bi = aEnc!!.dequeueInputBuffer(DEQ_TIMEOUT_US)
                                if (bi >= 0) {
                                    val ib = aEnc!!.getInputBuffer(bi)!!
                                    if (ib.remaining() >= bytes.size) {
                                        ib.put(bytes)
                                        aEnc!!.queueInputBuffer(
                                            bi, 0, bytes.size,
                                            aEncInIdx * 1024L * 1_000_000L / audioRate, 0,
                                        )
                                        aEncInIdx++
                                    }
                                }
                            }
                            try {
                                aDec!!.releaseOutputBuffer(out, false)
                            } catch (_: Exception) {
                            }
                            if (eos) aDecEosOut = true
                        } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            break
                        } else {
                            break
                        }
                        if (aDecEosOut) break
                    }
                    if (!aEncEosQueued && (aEncInIdx >= q.targetA || (aDecEosOut && extractorDoneA))) {
                        val bi = aEnc!!.dequeueInputBuffer(DEQ_TIMEOUT_US)
                        if (bi >= 0) {
                            aEnc!!.queueInputBuffer(
                                bi, 0, 0, aEncInIdx * 1024L * 1_000_000L / audioRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            aEncEosQueued = true
                        }
                    }
                    var drainGuard = 0
                    while (drainGuard++ < 32) {
                        val out = aEnc!!.dequeueOutputBuffer(info, 0)
                        if (out >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !cfg) {
                                val bb = aEnc!!.getOutputBuffer(out)!!
                                val bytes = ByteArray(info.size)
                                bb.position(info.offset)
                                bb.limit(info.offset + info.size)
                                bb.get(bytes)
                                aEncSamples.add(Enc(bytes, info.presentationTimeUs, info.flags))
                            }
                            try {
                                aEnc!!.releaseOutputBuffer(out, false)
                            } catch (_: Exception) {
                            }
                            if (eos) {
                                aEncEosOut = true
                                break
                            }
                        } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            try {
                                aEncFormat = aEnc!!.outputFormat
                            } catch (_: Exception) {
                            }
                        } else {
                            break
                        }
                    }
                } else if (!hasAudio) {
                    aEncEosOut = true
                }
            }

            if (vEncSamples.isEmpty() || vEncFormat == null) {
                log("transcode: sin video codificado (samples=${vEncSamples.size})")
                return false
            }
            val useAudio = hasAudio && aEncSamples.isNotEmpty() && aEncFormat != null
            if (hasAudio && !useAudio) {
                log("transcode: sin audio codificado, va solo-video")
            }

            // Fase 2: mux con formatos del propio encoder + pts monótonos.
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer!!.addTrack(vEncFormat!!)
            val aOut = if (useAudio) muxer!!.addTrack(aEncFormat!!) else -1
            muxer!!.start()
            val tmpInfo = MediaCodec.BufferInfo()
            for (s in vEncSamples) {
                tmpInfo.offset = 0
                tmpInfo.size = s.bytes.size
                tmpInfo.presentationTimeUs = s.pts
                tmpInfo.flags = s.flags
                muxer!!.writeSampleData(vOut, ByteBuffer.wrap(s.bytes), tmpInfo)
            }
            if (useAudio) {
                for (s in aEncSamples) {
                    tmpInfo.offset = 0
                    tmpInfo.size = s.bytes.size
                    tmpInfo.presentationTimeUs = s.pts
                    tmpInfo.flags = s.flags
                    muxer!!.writeSampleData(aOut, ByteBuffer.wrap(s.bytes), tmpInfo)
                }
            }
            muxer!!.stop()
            val vDurMs = if (vEncSamples.isNotEmpty()) vEncSamples.last().pts / 1000 else 0L
            log("transcode: ok vFrames=${vEncSamples.size} aFrames=${aEncSamples.size} vDur=${vDurMs}ms out=${dst.length()}")
            return dst.exists() && dst.length() > 0
        } catch (e: Exception) {
            log("transcode falló: $e")
            try {
                dst.delete()
            } catch (_: Exception) {
            }
            return false
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
            for (c in listOf(vDec, vEnc, aDec, aEnc)) {
                try {
                    c?.stop()
                } catch (_: Exception) {
                }
                try {
                    c?.release()
                } catch (_: Exception) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }
}
