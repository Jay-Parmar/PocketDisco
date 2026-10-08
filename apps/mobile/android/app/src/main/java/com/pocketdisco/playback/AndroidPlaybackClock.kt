package com.pocketdisco.playback

import android.os.Handler
import android.os.SystemClock

class AndroidPlaybackClock(
    private val handler: Handler,
    private val active: () -> Boolean,
) : PlaybackClock {
    override val nowMs get() = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, action: () -> Unit): PlaybackCancellation {
        val task = Runnable { if (active()) action() }
        check(handler.postDelayed(task, delayMs))
        return PlaybackCancellation { handler.removeCallbacks(task) }
    }
}
