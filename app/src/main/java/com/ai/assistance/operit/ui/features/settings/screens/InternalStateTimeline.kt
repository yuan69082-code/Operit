package com.ai.assistance.operit.ui.features.settings.screens

import com.ai.assistance.operit.core.companion.InternalStateEngine
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Only consumes the observation projection, never the unfiltered store or raw event details. */
internal data class StateTimelineEntry(
    val key: String,
    val eventId: String,
    val at: Long,
    val source: String,
    val before: Double,
    val after: Double,
    val version: Int,
    val continuousFromPrevious: Boolean,
    val intervalStart: Long?,
    val timeReasons: List<String>,
    val experience: JSONObject?,
)

internal fun stateTimeline(observation: JSONObject, state: JSONObject): List<StateTimelineEntry> {
    val dimension = state.getString("id")
    val scope = state.getString("instance_scope")
    val experiences = InternalStateEngine.rows(observation, "experiences")
        .filter { it.getString("dimension") == dimension && it.getString("scope") == scope }
        .associateBy { InternalStateEngine.experienceEventId(it) }
    return InternalStateEngine.rows(observation, "events").flatMap { event ->
        InternalStateEngine.rows(event, "changes").mapIndexedNotNull { index, change ->
            if (change.getString("dimension") != dimension || change.getString("scope") != scope) {
                return@mapIndexedNotNull null
            }
            val eventId = event.getString("id")
            val reasons = change.optJSONArray("time_reasons")
            StateTimelineEntry(
                key = "$eventId:$index",
                eventId = eventId,
                at = if (change.has("interval_end")) change.getLong("interval_end") else event.getLong("at"),
                source = event.getString("source"),
                before = change.getDouble("before"),
                after = change.getDouble("after"),
                version = change.getInt("version"),
                // This bit is computed before privacy filtering. Matching values alone do not
                // establish continuity: an omitted private excursion could have returned to them.
                continuousFromPrevious = change.getBoolean("continuous_from_previous"),
                intervalStart = if (change.has("interval_start")) change.getLong("interval_start") else null,
                timeReasons = if (reasons == null) emptyList() else (0 until reasons.length()).map { reasons.getString(it) },
                experience = experiences[eventId],
            )
        }
    }
}

internal data class StateChartSegment(val fromAt: Long, val fromValue: Double, val toAt: Long, val toValue: Double)
internal data class StateChartGeometry(
    val segments: List<StateChartSegment>,
    val start: Long,
    val end: Long,
    val low: Double,
    val high: Double,
)

/** A time interval is continuous; an event is an instantaneous jump. Hidden intervals stay blank. */
internal fun stateChartGeometry(entries: List<StateTimelineEntry>, currentValue: Double, now: Long): StateChartGeometry {
    val segments = mutableListOf<StateChartSegment>()
    entries.forEachIndexed { index, entry ->
        val previous = entries.getOrNull(index - 1)
        val start = entry.intervalStart ?: entry.at
        val adjacent = previous != null && entry.continuousFromPrevious && previous.after == entry.before && previous.at <= start
        if (adjacent && !(entry.source == "time" && entry.intervalStart == null))
            segments += StateChartSegment(previous!!.at, previous.after, start, entry.before)
        if (entry.source == "time") {
            // Old records have only samples. Join those samples only when the host verified
            // continuity; an omitted private excursion must never become an interpolated line.
            if (entry.intervalStart != null && entry.intervalStart <= entry.at) {
                segments += StateChartSegment(entry.intervalStart, entry.before, entry.at, entry.after)
            } else if (adjacent) segments += StateChartSegment(previous!!.at, previous.after, entry.at, entry.after)
        } else if (entry.before != entry.after) {
            segments += StateChartSegment(entry.at, entry.before, entry.at, entry.after)
        }
    }
    val values = entries.flatMap { listOf(it.before, it.after) } + currentValue
    val low = values.minOrNull()!!
    val high = values.maxOrNull()!!
    val padding = if (high > low) (high - low) * 0.1 else max(abs(high) * 0.02, 1.0)
    val times = entries.flatMap { listOfNotNull(it.intervalStart, it.at) }
    return StateChartGeometry(segments, times.minOrNull() ?: now, times.maxOrNull() ?: now, low - padding, high + padding)
}
