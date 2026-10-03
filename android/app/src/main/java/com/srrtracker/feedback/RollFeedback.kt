package com.srrtracker.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class RollFeedback(context: Context) {
    private val app = context.applicationContext
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    fun onLogged(seven: Boolean, sound: Boolean, vibrate: Boolean) {
        if (sound) {
            val kind = if (seven) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP
            tone.startTone(kind, if (seven) 180 else 110)
        }
        if (vibrate) buzz(if (seven) 55 else 35)
    }

    fun onUndo() {
        buzz(20)
    }

    fun release() {
        tone.release()
    }

    private fun buzz(ms: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val manager = app.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
