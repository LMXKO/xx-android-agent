package com.lmx.xiaoxuanagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTargetAppLauncherTest {
    @Test
    fun `launch returns false when no launcher registered`() {
        SessionTargetAppLauncher.unregister()
        assertFalse(SessionTargetAppLauncher.isAvailable())
        assertFalse(SessionTargetAppLauncher.launch("com.taobao.taobao", "test"))
    }

    @Test
    fun `launch invokes registered launcher with package`() {
        var capturedPkg = ""
        var capturedReason = ""
        SessionTargetAppLauncher.register { pkg, reason ->
            capturedPkg = pkg
            capturedReason = reason
            true
        }
        assertTrue(SessionTargetAppLauncher.isAvailable())
        assertTrue(SessionTargetAppLauncher.launch("com.jingdong.app.mall", "restore"))
        assertEquals("com.jingdong.app.mall", capturedPkg)
        assertEquals("restore", capturedReason)
        SessionTargetAppLauncher.unregister()
    }

    @Test
    fun `launch with blank package returns false without invoking`() {
        var invoked = false
        SessionTargetAppLauncher.register { _, _ ->
            invoked = true
            true
        }
        assertFalse(SessionTargetAppLauncher.launch("", "x"))
        assertFalse(invoked)
        SessionTargetAppLauncher.unregister()
    }
}
