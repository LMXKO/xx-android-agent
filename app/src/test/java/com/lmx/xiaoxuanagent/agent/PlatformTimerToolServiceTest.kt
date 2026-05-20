package com.lmx.xiaoxuanagent.agent

import android.provider.AlarmClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformTimerToolServiceTest {
    @Test
    fun `stopwatch action no longer aliases timer page`() {
        assertEquals("android.intent.action.SHOW_STOPWATCH", PlatformTimerToolService.DIRECT_STOPWATCH_ACTION)
        assertTrue(PlatformTimerToolService.DIRECT_STOPWATCH_ACTION != AlarmClock.ACTION_SHOW_TIMERS)
        assertEquals("android.intent.category.APP_CLOCK", PlatformTimerToolService.APP_CLOCK_CATEGORY)
    }

    @Test
    fun `stopwatch fallback message is explicit about clock app handoff`() {
        val message = PlatformTimerToolService.stopwatchSuccessMessage(directStopwatchSupported = false)

        assertTrue(message.contains("未提供通用秒表直达入口"))
        assertTrue(message.contains("时钟应用"))
    }
}
