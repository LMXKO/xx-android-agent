package com.lmx.xiaoxuanagent.memory

import android.content.Context
import android.content.ContextWrapper
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignal
import com.lmx.xiaoxuanagent.assistantos.AssistantExternalSignalType
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryStoreExternalSignalTest {
    @Test
    fun `record external signal memory persists phone communication context`() {
        val root = Files.createTempDirectory("memory-signal-test").toFile()
        AppRuntimeContext.init(testContext(root))

        PersonalMemoryStore.recordExternalSignalMemory(
            AssistantExternalSignal(
                id = "signal_call",
                type = AssistantExternalSignalType.CALL_LOG,
                capability = SessionCapabilityKey.START_SESSION,
                title = "未接来电",
                summary = "张三 | 32s",
                payload =
                    mapOf(
                        "caller_name" to "张三",
                        "caller_number" to "13800000000",
                        "call_type" to "未接来电",
                    ),
                fireAtMs = 1_000L,
                createdAtMs = 1_000L,
                updatedAtMs = 1_000L,
            ),
        )

        val governance = PersonalMemoryStore.readGovernanceSnapshot(limit = 12, auditLimit = 12)
        val userModel = PersonalMemoryStore.readUserModelSnapshot(limit = 4)
        val insight = PersonalMemoryStore.readInsightSnapshot(limit = 4)

        assertTrue(governance.entries.any { it.type == PersonalMemoryEntryType.CONTACT && it.title == "张三" })
        assertTrue(governance.entries.any { it.type == PersonalMemoryEntryType.FACT && it.title.contains("近期通话") })
        assertTrue(userModel.relationshipSummary.contains("张三"))
        assertTrue(insight.dreamSummary.isNotBlank() || insight.suggestionSummary.isNotBlank())

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
