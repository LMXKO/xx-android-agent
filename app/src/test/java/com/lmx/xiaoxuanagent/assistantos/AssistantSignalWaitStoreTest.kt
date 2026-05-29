package com.lmx.xiaoxuanagent.assistantos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AssistantSignalWaitStoreTest {
    @Before
    fun setup() {
        AssistantSignalWaitStore.resetForTest()
    }

    @Test
    fun `bind then resolve by type and package`() {
        AssistantSignalWaitStore.bind(
            sessionId = "s1",
            targetPackageName = "com.taobao.taobao",
            signalType = AssistantExternalSignalType.APP_FOREGROUND,
            resumeEvent = "manual_verification",
        )
        val resolved =
            AssistantSignalWaitStore.resolve(AssistantExternalSignalType.APP_FOREGROUND, "com.taobao.taobao")
        assertNotNull(resolved)
        assertEquals("s1", resolved!!.sessionId)
        assertNull(AssistantSignalWaitStore.resolve(AssistantExternalSignalType.NOTIFICATION, "com.taobao.taobao"))
        assertNull(AssistantSignalWaitStore.resolve(AssistantExternalSignalType.APP_FOREGROUND, "com.other.app"))
    }

    @Test
    fun `bind dedupes by session and type`() {
        AssistantSignalWaitStore.bind("s1", "com.taobao.taobao", AssistantExternalSignalType.APP_FOREGROUND)
        AssistantSignalWaitStore.bind("s1", "com.jingdong.app.mall", AssistantExternalSignalType.APP_FOREGROUND)
        assertEquals(1, AssistantSignalWaitStore.readActive().count { it.sessionId == "s1" })
        val resolved =
            AssistantSignalWaitStore.resolve(AssistantExternalSignalType.APP_FOREGROUND, "com.jingdong.app.mall")
        assertNotNull(resolved)
        assertEquals("com.jingdong.app.mall", resolved!!.targetPackageName)
    }

    @Test
    fun `clearSession disables bindings`() {
        AssistantSignalWaitStore.bind("s1", "com.taobao.taobao", AssistantExternalSignalType.APP_FOREGROUND)
        AssistantSignalWaitStore.clearSession("s1")
        assertNull(AssistantSignalWaitStore.resolve(AssistantExternalSignalType.APP_FOREGROUND, "com.taobao.taobao"))
    }

    @Test
    fun `bind ignores blank session or package`() {
        assertNull(AssistantSignalWaitStore.bind("", "com.x", AssistantExternalSignalType.APP_FOREGROUND))
        assertNull(AssistantSignalWaitStore.bind("s", "", AssistantExternalSignalType.APP_FOREGROUND))
    }
}
