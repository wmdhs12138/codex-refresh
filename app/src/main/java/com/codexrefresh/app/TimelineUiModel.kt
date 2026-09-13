package com.codexrefresh.app

/** Pure presentation model shared by the Compose timeline and its projector. */
enum class FiveHourWindowPhase { UNKNOWN, ACTIVE, READY }

enum class TimelineMarkerKind { CONFIRMED, PLANNED, NEXT, COMPLETED }

data class TimelineMarker(
    val minuteOfDay: Float,
    val kind: TimelineMarkerKind,
)

data class TimelineRange(
    val startMinute: Float,
    val endMinute: Float,
)

data class DayTimelineState(
    val markers: List<TimelineMarker> = emptyList(),
    val workRanges: List<TimelineRange> = emptyList(),
    val workLabel: String? = null,
    val nowMinute: Float? = null,
    val accessibilityText: String = "等待额度数据",
)
