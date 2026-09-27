package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkScheduleSliderTest {
    private val width = 1000f
    private val inset = 50f

    @Test
    fun minuteAndPositionRoundTrip() {
        for (minute in listOf(0, 8 * 60 + 30, 17 * 60 + 30, 23 * 60 + 45)) {
            assertEquals(minute, WorkScheduleSlider.minuteAt(WorkScheduleSlider.x(minute, width, inset), width, inset))
        }
    }

    @Test
    fun minuteSnapsToQuarterHour() {
        val x = WorkScheduleSlider.x(8 * 60 + 37, width, inset)
        assertEquals(8 * 60 + 30, WorkScheduleSlider.minuteAt(x, width, inset))
    }

    @Test
    fun minuteStaysInsideValidDay() {
        // WorkSchedule 要求分钟数 < 1440，拖到最右端不能得到 24:00
        assertEquals(23 * 60 + 45, WorkScheduleSlider.minuteAt(width + 200f, width, inset))
        assertEquals(0, WorkScheduleSlider.minuteAt(-200f, width, inset))
    }

    @Test
    fun pickIgnoresTouchesFarFromThumbs() {
        assertNull(WorkScheduleSlider.pick(downX = 500f, dragDx = 0f, startX = 100f, endX = 900f, hitRadius = 60f))
    }

    @Test
    fun pickChoosesNearestThumb() {
        assertEquals(WorkThumb.START, WorkScheduleSlider.pick(120f, 5f, 100f, 900f, 60f))
        assertEquals(WorkThumb.END, WorkScheduleSlider.pick(880f, -5f, 100f, 900f, 60f))
    }

    @Test
    fun overlappingThumbsSplitByDragDirection() {
        assertEquals(WorkThumb.END, WorkScheduleSlider.pick(505f, 8f, 500f, 510f, 60f))
        assertEquals(WorkThumb.START, WorkScheduleSlider.pick(505f, -8f, 500f, 510f, 60f))
        // 跨夜班：end 在 start 左侧
        assertEquals(WorkThumb.START, WorkScheduleSlider.pick(505f, 8f, 510f, 500f, 60f))
        assertEquals(WorkThumb.END, WorkScheduleSlider.pick(505f, -8f, 510f, 500f, 60f))
    }
}
