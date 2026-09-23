package dev.pocketdisco.phase0

enum class GeneratedSyncEnvironment(val wireValue: String) {
    PHYSICAL("physical"),
    EMULATOR("emulator"),
}

enum class GeneratedSyncOutputCategory(val wireValue: String) {
    BUILT_IN("built_in"),
    WIRED("wired"),
    BLUETOOTH("bluetooth"),
    USB("usb"),
    VIRTUAL("virtual"),
    MIXED("mixed"),
}

enum class GeneratedSyncRouteMode(val wireValue: String) {
    SYSTEM_GROUP("system_group"),
    APP_FANOUT("app_fanout"),
}

class GeneratedSyncIdentity private constructor(
    val scenarioId: String,
    val deviceId: String,
    val environment: GeneratedSyncEnvironment,
    val outputCategory: GeneratedSyncOutputCategory,
    val routeMode: GeneratedSyncRouteMode,
) {
    companion object {
        fun fromInput(
            scenarioId: String,
            deviceId: String,
            environment: GeneratedSyncEnvironment,
            outputCategory: GeneratedSyncOutputCategory,
            routeMode: GeneratedSyncRouteMode,
        ) = GeneratedSyncIdentity(
            scenarioId = normalizedIdentity("Scenario ID", scenarioId),
            deviceId = normalizedIdentity("Client ID", deviceId),
            environment = environment,
            outputCategory = outputCategory,
            routeMode = routeMode,
        )
    }
}

data class GeneratedSyncContext(
    val identity: GeneratedSyncIdentity,
    val trialId: String,
    val targetTimestampMs: Long,
    val targetElapsedRealtimeMs: Long,
    val clockUncertaintyMs: Long,
) {
    init {
        require(trialId == normalizedIdentity("Trial ID", trialId))
        require(targetTimestampMs >= 0) { "Target timestamp must not be negative" }
        require(targetElapsedRealtimeMs >= 0) { "Target monotonic time must not be negative" }
        require(clockUncertaintyMs >= 0) { "Clock uncertainty must not be negative" }
    }

    val startId: String = targetTimestampMs.toString()
    val clockId: String = "coordinator:$trialId"
}

data class GeneratedSyncObservation(
    val eventType: String,
    val scenarioId: String,
    val trialId: String,
    val startId: String,
    val deviceId: String,
    val environment: String,
    val provider: String,
    val signalId: String,
    val signalSha256: String,
    val outputCategory: String,
    val routeMode: String,
    val outcome: String,
    val timestampMs: Long?,
    val targetTimestampMs: Long?,
    val clockId: String?,
    val clockSource: String?,
    val clockUncertaintyMs: Long?,
    val failureReason: String?,
)

class GeneratedSyncTelemetryRecorder {
    private val observations = mutableListOf<GeneratedSyncObservation>()
    private val activeEventTypes = mutableSetOf<String>()
    private var activeContext: GeneratedSyncContext? = null

    @Synchronized
    fun begin(context: GeneratedSyncContext) {
        recordFailure(REPLACED)
        activeContext = context
        activeEventTypes.clear()
    }

    @Synchronized
    fun clearActive(reason: String = CANCELLED) {
        recordFailure(reason)
        activeContext = null
        activeEventTypes.clear()
    }

    @Synchronized
    fun record(event: MultiOutputProbeEvent) {
        when (event.name) {
            "play_commands_issued" -> recordSuccess(COMMAND_ISSUED, event.elapsedRealtimeMs)
            "playback_observed" -> recordSuccess(PLAYBACK_OBSERVED, event.elapsedRealtimeMs)
            "playback_start_failed" -> recordFailure(PLAYER_FAILED)
            "route_lost" -> recordFailure(ROUTE_LOST)
            "audio_focus_lost" -> recordFailure(AUDIO_FOCUS_LOST)
        }
    }

    @Synchronized
    fun recordFailure(reason: String) {
        val context = activeContext ?: return
        val stableReason = normalizedIdentity("Failure reason", reason)
        if (COMMAND_ISSUED !in activeEventTypes) {
            addFailure(context, COMMAND_ISSUED, stableReason)
        }
        if (PLAYBACK_OBSERVED !in activeEventTypes) {
            addFailure(context, PLAYBACK_OBSERVED, stableReason)
        }
    }

    @Synchronized
    fun snapshot(): List<GeneratedSyncObservation> = observations.toList()

    @Synchronized
    fun size(): Int = observations.size

    @Synchronized
    fun toNdjson(): String = observations.joinToString(
        separator = "\n",
        postfix = if (observations.isEmpty()) "" else "\n",
    ) { it.toJsonLine() }

    private fun recordSuccess(eventType: String, elapsedRealtimeMs: Long) {
        val context = activeContext ?: return
        if (!activeEventTypes.add(eventType)) return
        val deltaMs = Math.subtractExact(elapsedRealtimeMs, context.targetElapsedRealtimeMs)
        val timestampMs = Math.addExact(context.targetTimestampMs, deltaMs)
        require(timestampMs >= 0) { "Observed timestamp must not be negative" }
        observations += context.observation(
            eventType = eventType,
            outcome = OK,
            timestampMs = timestampMs,
            targetTimestampMs = context.targetTimestampMs,
            clockId = context.clockId,
            clockSource = COORDINATOR_ESTIMATE,
            clockUncertaintyMs = context.clockUncertaintyMs,
        )
    }

    private fun addFailure(
        context: GeneratedSyncContext,
        eventType: String,
        reason: String,
    ) {
        activeEventTypes += eventType
        observations += context.observation(
            eventType = eventType,
            outcome = FAILURE,
            failureReason = reason,
        )
    }

    companion object {
        const val PROVIDER = "generated_audio"
        private const val COMMAND_ISSUED = "command_issued"
        private const val PLAYBACK_OBSERVED = "playback_observed"
        private const val OK = "ok"
        private const val FAILURE = "failure"
        private const val COORDINATOR_ESTIMATE = "coordinator_estimate"
        private const val PLAYER_FAILED = "player_failed"
        private const val ROUTE_LOST = "route_lost"
        private const val AUDIO_FOCUS_LOST = "audio_focus_lost"
        private const val REPLACED = "replaced"
        private const val CANCELLED = "cancelled"
    }
}

private fun GeneratedSyncContext.observation(
    eventType: String,
    outcome: String,
    timestampMs: Long? = null,
    targetTimestampMs: Long? = null,
    clockId: String? = null,
    clockSource: String? = null,
    clockUncertaintyMs: Long? = null,
    failureReason: String? = null,
) = GeneratedSyncObservation(
    eventType = eventType,
    scenarioId = identity.scenarioId,
    trialId = trialId,
    startId = startId,
    deviceId = identity.deviceId,
    environment = identity.environment.wireValue,
    provider = GeneratedSyncTelemetryRecorder.PROVIDER,
    signalId = ClickSignal.SIGNAL_ID,
    signalSha256 = ClickSignal.PCM_SHA256,
    outputCategory = identity.outputCategory.wireValue,
    routeMode = identity.routeMode.wireValue,
    outcome = outcome,
    timestampMs = timestampMs,
    targetTimestampMs = targetTimestampMs,
    clockId = clockId,
    clockSource = clockSource,
    clockUncertaintyMs = clockUncertaintyMs,
    failureReason = failureReason,
)

private fun GeneratedSyncObservation.toJsonLine(): String = buildString {
    append('{')
    appendGeneratedField("schema_version", 2)
    appendGeneratedField("event_type", eventType)
    appendGeneratedField("scenario_id", scenarioId)
    appendGeneratedField("trial_id", trialId)
    appendGeneratedField("start_id", startId)
    appendGeneratedField("device_id", deviceId)
    appendGeneratedField("platform", "android")
    appendGeneratedField("environment", environment)
    appendGeneratedField("provider", provider)
    appendGeneratedField("signal_id", signalId)
    appendGeneratedField("signal_sha256", signalSha256)
    appendGeneratedField("output_category", outputCategory)
    appendGeneratedField("route_mode", routeMode)
    appendGeneratedField(
        "outcome",
        outcome,
        isLast = timestampMs == null && failureReason == null,
    )
    timestampMs?.let {
        appendGeneratedField("timestamp_ms", it)
        appendGeneratedField("target_timestamp_ms", requireNotNull(targetTimestampMs))
        appendGeneratedField("clock_id", requireNotNull(clockId))
        appendGeneratedField("clock_source", requireNotNull(clockSource))
        appendGeneratedField(
            "clock_uncertainty_ms",
            requireNotNull(clockUncertaintyMs),
            isLast = true,
        )
    }
    failureReason?.let { appendGeneratedField("failure_reason", it, isLast = true) }
    append('}')
}

private fun StringBuilder.appendGeneratedField(
    name: String,
    value: String,
    isLast: Boolean = false,
) {
    append(JsonString.quote(name))
    append(':')
    append(JsonString.quote(value))
    if (!isLast) append(',')
}

private fun StringBuilder.appendGeneratedField(
    name: String,
    value: Long,
    isLast: Boolean = false,
) {
    append(JsonString.quote(name))
    append(':')
    append(value)
    if (!isLast) append(',')
}

private fun normalizedIdentity(label: String, value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$label is required" }
    require(normalized.length <= 100) { "$label must be 100 characters or fewer" }
    require(normalized.none(Char::isISOControl)) { "$label contains a control character" }
    return normalized
}
