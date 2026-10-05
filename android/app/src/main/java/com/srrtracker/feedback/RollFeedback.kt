package com.srrtracker.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three cues, each a SoundPool clip on the alarm stream so the media-volume
 * slider does not silence them. There is no vibration.
 *
 * A clear read is one short high beep. A total of 7 is a low buzz instead of
 * that beep. An unread roll is two descending tones.
 */
class RollFeedback(context: Context) {
    private val pool: SoundPool
    private val beepId: Int
    private val buzzId: Int
    private val unreadId: Int
    @Volatile private var loaded = 0
    @Volatile private var pending = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) Log.w(TAG, "sound $sampleId failed to load ($status)")
            val cue = synchronized(this) {
                if (status == 0) loaded++
                if (loaded >= 3) {
                    val queued = pending
                    pending = 0
                    queued
                } else {
                    0
                }
            }
            if (cue != 0) play(cue)
        }
        val dir = File(context.cacheDir, "cues").apply { mkdirs() }
        beepId = load(dir, "beep.wav", beepPcm())
        buzzId = load(dir, "buzz.wav", buzzPcm())
        unreadId = load(dir, "unread.wav", unreadPcm())
    }

    fun onClearRead(seven: Boolean, sound: Boolean) {
        if (!sound) return
        cue(if (seven) buzzId else beepId)
    }

    fun onUnread(sound: Boolean) {
        if (!sound) return
        cue(unreadId)
    }

    fun release() {
        pending = 0
        try {
            pool.release()
        } catch (_: Throwable) {
        }
    }

    private fun cue(id: Int) {
        if (id == 0) return
        val playNow = synchronized(this) {
            if (loaded < 3) {
                pending = id
                false
            } else {
                true
            }
        }
        if (playNow) play(id)
    }

    private fun play(id: Int) {
        try {
            pool.play(id, 1f, 1f, 1, 0, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "sound failed", t)
        }
    }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val file = File(dir, name)
        file.writeBytes(wav(pcm))
        return pool.load(file.absolutePath, 1)
    }

    private fun beepPcm(): ShortArray = tone(880.0, 90, square = false)

    private fun buzzPcm(): ShortArray = tone(120.0, 280, square = true)

    private fun unreadPcm(): ShortArray {
        val high = tone(520.0, 120, square = false)
        val gap = ShortArray(RATE * 40 / 1000)
        val low = tone(260.0, 180, square = false)
        return high + gap + low
    }

    private fun tone(freq: Double, ms: Int, square: Boolean): ShortArray {
        val n = (RATE * ms / 1000).coerceAtLeast(1)
        val out = ShortArray(n)
        val amp = 14000.0
        for (i in 0 until n) {
            val attack = (i / (RATE * 0.008)).coerceAtMost(1.0)
            val release = ((n - i) / (RATE * 0.02)).coerceAtMost(1.0)
            val env = attack.coerceAtMost(release)
            val wave = sin(2.0 * PI * freq * i / RATE)
            val sample = if (square) {
                if (wave >= 0) 1.0 else -1.0
            } else {
                wave
            }
            out[i] = (sample * amp * env).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val body = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        body.put("RIFF".toByteArray())
        body.order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(36 + dataBytes)
        body.put("WAVE".toByteArray())
        body.put("fmt ".toByteArray())
        body.putInt(16)
        body.putShort(1)
        body.putShort(1)
        body.putInt(RATE)
        body.putInt(RATE * 2)
        body.putShort(2)
        body.putShort(16)
        body.put("data".toByteArray())
        body.putInt(dataBytes)
        for (s in pcm) body.putShort(s)
        return body.array()
    }

    companion object {
        private const val TAG = "SrrTracker"
        private const val RATE = 44100
    }
}
