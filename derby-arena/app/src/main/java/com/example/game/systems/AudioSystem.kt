package com.example.game.systems

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * AudioSystem: procedural SFX synthesized at init (no audio assets required) and played through
 * static AudioTracks. Engine hum is a looping track whose playback rate follows vehicle speed.
 */
class AudioSystem {

    companion object {
        private const val TAG = "AudioSystem"
        private const val RATE = 22050
    }

    enum class Sfx { SHOT, HEAVY_SHOT, IMPACT, EXPLOSION, PICKUP, ZONE_WARNING, WRECK }

    private val tracks = HashMap<Sfx, Array<AudioTrack>>()
    private val cursor = HashMap<Sfx, Int>()
    private var engineTrack: AudioTrack? = null
    private var enabled = true
    private var lastPlayFrame = HashMap<Sfx, Long>()

    fun init() {
        try {
            tracks[Sfx.SHOT] = pool(3, synth(0.09f) { t, i -> noise(i) * env(t, 0.09f, 18f) * 0.6f + sin(2 * PI * 180 * t).toFloat() * env(t, 0.09f, 30f) * 0.4f })
            tracks[Sfx.HEAVY_SHOT] = pool(2, synth(0.35f) { t, i -> noise(i) * env(t, 0.35f, 8f) * 0.5f + sin(2 * PI * 70 * t).toFloat() * env(t, 0.35f, 6f) * 0.7f })
            tracks[Sfx.IMPACT] = pool(3, synth(0.2f) { t, i -> noise(i) * env(t, 0.2f, 14f) * 0.5f + sin(2 * PI * 120 * t).toFloat() * env(t, 0.2f, 12f) * 0.5f })
            tracks[Sfx.EXPLOSION] = pool(2, synth(1.1f) { t, i -> noise(i) * env(t, 1.1f, 3.2f) * 0.8f + sin(2 * PI * 45 * t).toFloat() * env(t, 1.1f, 2.5f) * 0.5f })
            tracks[Sfx.PICKUP] = pool(2, synth(0.3f) { t, _ -> (sin(2 * PI * 660 * t) + sin(2 * PI * 990 * (t - 0.1).coerceAtLeast(0.0))).toFloat() * env(t, 0.3f, 6f) * 0.35f })
            tracks[Sfx.ZONE_WARNING] = pool(1, synth(0.6f) { t, _ -> sin(2 * PI * 440 * t).toFloat() * (if ((t * 6).toInt() % 2 == 0) 1f else 0f) * 0.4f })
            tracks[Sfx.WRECK] = pool(2, synth(0.8f) { t, i -> noise(i) * env(t, 0.8f, 5f) * 0.6f + sin(2 * PI * 90 * t * (1 - t)).toFloat() * env(t, 0.8f, 4f) * 0.5f })
            engineTrack = loopTrack(synth(0.5f) { t, i -> (sin(2 * PI * 55 * t) * 0.5 + sin(2 * PI * 110 * t) * 0.3).toFloat() + noise(i) * 0.08f })
            Log.i(TAG, "Procedural SFX bank ready (${tracks.size} effects)")
        } catch (t: Throwable) {
            enabled = false
            Log.w(TAG, "Audio init failed, running silent: ${t.message}")
        }
    }

    private fun env(t: Double, len: Float, k: Float): Float = exp(-t * k).toFloat() * (if (t < len) 1f else 0f)
    private fun noise(i: Int): Float = if (i % 2 == 0) Random.nextFloat() * 2f - 1f else Random.nextFloat() * 2f - 1f

    private fun synth(seconds: Float, fn: (Double, Int) -> Float): ShortArray {
        val n = (seconds * RATE).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val v = fn(i.toDouble() / RATE, i).coerceIn(-1f, 1f)
            out[i] = (v * 32767f * 0.8f).toInt().toShort()
        }
        return out
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun format() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .build()

    private fun staticTrack(pcm: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs())
            .setAudioFormat(format())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    private fun pool(count: Int, pcm: ShortArray): Array<AudioTrack> = Array(count) { staticTrack(pcm) }

    private fun loopTrack(pcm: ShortArray): AudioTrack {
        val t = staticTrack(pcm)
        t.setLoopPoints(0, pcm.size, -1)
        t.setVolume(0f)
        t.play()
        return t
    }

    /** Fire-and-forget SFX with a per-effect rate limit (avoids audio spam from machine guns). */
    fun play(sfx: Sfx, frame: Long, minGapFrames: Int = 3) {
        if (!enabled) return
        val last = lastPlayFrame[sfx] ?: -1000L
        if (frame - last < minGapFrames) return
        lastPlayFrame[sfx] = frame
        val arr = tracks[sfx] ?: return
        val idx = (cursor[sfx] ?: 0) % arr.size
        cursor[sfx] = idx + 1
        try {
            val t = arr[idx]
            t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Throwable) {
            Log.w(TAG, "sfx $sfx failed: ${e.message}")
        }
    }

    fun setEngine(speedFrac: Float, boosting: Boolean, active: Boolean) {
        val t = engineTrack ?: return
        try {
            if (!active) {
                t.setVolume(0f)
                return
            }
            val rate = (RATE * (0.75f + speedFrac * 0.9f + (if (boosting) 0.25f else 0f))).toInt()
            t.playbackRate = rate.coerceIn(RATE / 2, RATE * 2)
            t.setVolume((0.12f + speedFrac * 0.25f).coerceAtMost(0.4f))
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            tracks.values.flatMap { it.asList() }.forEach { it.release() }
            tracks.clear()
            engineTrack?.release()
            engineTrack = null
        } catch (_: Throwable) {
        }
    }
}
