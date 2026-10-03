package com.srrtracker.feedback

import android.media.AudioManager
import android.media.ToneGenerator

/** Optional beep when a roll is saved. There is no vibration. */
class RollFeedback {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    fun onLogged(seven: Boolean, sound: Boolean) {
        if (!sound) return
        val kind = if (seven) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP
        tone.startTone(kind, if (seven) 180 else 110)
    }

    fun release() {
        tone.release()
    }
}
