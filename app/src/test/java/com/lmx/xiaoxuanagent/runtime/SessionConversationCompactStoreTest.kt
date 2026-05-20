package com.lmx.xiaoxuanagent.runtime

import android.content.Context
import android.content.ContextWrapper
import com.lmx.xiaoxuanagent.agent.AgentTurnRecord
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConversationCompactStoreTest {
    @Test
    fun `record turn builds reactive compact snapshot and final boundary`() {
        val root = Files.createTempDirectory("conversation-compact-store-test").toFile()
        AppRuntimeContext.init(testContext(root))
        SessionConversationCompactStore.resetForTest()

        repeat(6) { index ->
            SessionConversationCompactStore.recordTurn(
                sessionId = "session_1",
                task = "整理今天的收件箱",
                turn = index + 1,
                turnRecord =
                    AgentTurnRecord(
                        observationSignature = "sig_${index + 1}",
                        pageState = "inbox",
                        action = if (index < 3) "tap_message" else "reply_message",
                        result = "完成第 ${index + 1} 步",
                        recoveryCategory = if (index == 4) "retry" else "",
                        suggestedRecoveryAction = if (index == 4) "focus_input" else "",
                    ),
                keepRunning = index < 5,
            )
        }

        val snapshot = SessionConversationCompactStore.readSnapshot("session_1")
        requireNotNull(snapshot)
        assertEquals("post_boundary", snapshot.trigger)
        assertTrue(snapshot.mode == "reactive" || snapshot.mode == "auto")
        assertTrue(snapshot.toolUseSummary.contains("tap_message"))
        assertTrue(snapshot.recoverySummary.contains("retry") || snapshot.status == "completed")
        assertTrue(snapshot.boundaryDigests.isNotEmpty())

        val exported = SessionConversationCompactStore.exportJson(limit = 4)
        SessionConversationCompactStore.resetForTest()
        SessionConversationCompactStore.importJson(exported)
        val restored = SessionConversationCompactStore.readSnapshot("session_1")
        requireNotNull(restored)
        assertEquals(snapshot.trigger, restored.trigger)
        assertEquals(snapshot.boundaryDigests, restored.boundaryDigests)

        root.deleteRecursively()
    }

    private fun testContext(
        root: File,
    ): Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root
        }
}
