package dev.pocketdisco.phase0

import android.os.Handler
import android.os.Looper

class MonotonicScheduler(
    private val elapsedRealtimeMs: () -> Long,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var pending: Runnable? = null

    fun scheduleAt(targetElapsedRealtimeMs: Long, action: (actualElapsedRealtimeMs: Long) -> Unit) {
        cancel()
        lateinit var check: Runnable
        check = Runnable {
            when (val decision = SchedulePlanner.decide(targetElapsedRealtimeMs - elapsedRealtimeMs())) {
                ScheduleDecision.Execute -> {
                    pending = null
                    action(elapsedRealtimeMs())
                }

                is ScheduleDecision.Wait -> handler.postDelayed(check, decision.delayMs)
            }
        }
        pending = check
        handler.post(check)
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
