package com.pocketdisco.capturelab.protocol

internal class ClockProbeSchedule {
    private var lastProbeAt: Long? = null
    private var refilling = true
    private var rapidProbesLeft = REFILL_PROBES

    fun shouldProbe(nowNanos: Long, ready: Boolean, samples: Int, pending: Boolean): Boolean {
        val needsRefill = !ready || samples < SAMPLE_RESERVE
        if (needsRefill && !refilling) rapidProbesLeft = REFILL_PROBES
        refilling = needsRefill
        if (pending) return false

        val previous = lastProbeAt
        if (previous != null && nowNanos < previous) {
            lastProbeAt = nowNanos
            return false
        }
        val interval = if (refilling && rapidProbesLeft > 0) REFILL_INTERVAL_NANOS else MAINTENANCE_INTERVAL_NANOS
        if (previous != null && nowNanos - previous < interval) return false
        lastProbeAt = nowNanos
        if (refilling && rapidProbesLeft > 0) rapidProbesLeft--
        return true
    }

    fun reset() {
        lastProbeAt = null
        refilling = true
        rapidProbesLeft = REFILL_PROBES
    }

    companion object {
        private const val SAMPLE_RESERVE = 6
        private const val REFILL_PROBES = 7
        private const val REFILL_INTERVAL_NANOS = 100_000_000L
        private const val MAINTENANCE_INTERVAL_NANOS = 500_000_000L
    }
}
