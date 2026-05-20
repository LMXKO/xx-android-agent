package com.lmx.xiaoxuanagent.memory

import android.content.Context
import android.content.ContextWrapper
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryStoreGovernanceTest {
    @Test
    fun `upsert and delete publish governance snapshot and workspace files`() {
        val root = Files.createTempDirectory("personal-memory-store-test").toFile()
        AppRuntimeContext.init(testContext(root))
        PersonalMemoryStore.resetForTest()

        val observed = PersonalMemoryStore.observeGovernanceSnapshot()
        assertEquals(0, observed.value.totalEntries)

        val afterFact =
            PersonalMemoryStore.upsertGovernedEntry(
                typeWire = PersonalMemoryEntryType.FACT.wireName,
                primary = "用户习惯先看待办再处理消息",
            )
        assertEquals(1, afterFact.totalEntries)
        assertEquals(1, afterFact.factCount)
        assertTrue(observed.value.entries.any { it.title.contains("待办") })

        val afterContact =
            PersonalMemoryStore.upsertGovernedEntry(
                typeWire = PersonalMemoryEntryType.CONTACT.wireName,
                primary = "张三",
                secondary = "同事,朋友",
                note = "住在上海徐汇",
                profileId = "profile_wechat",
            )
        assertEquals(2, afterContact.totalEntries)
        assertEquals(1, afterContact.structuredCount)
        assertTrue(observed.value.entries.any { it.type == PersonalMemoryEntryType.CONTACT && it.title == "张三" })
        assertTrue(observed.value.auditTrail.any { it.action == "upsert" && it.type == PersonalMemoryEntryType.CONTACT })
        val insight = PersonalMemoryStore.readInsightSnapshot(limit = 3)
        assertTrue(insight.summary.contains("profiles="))
        assertTrue(insight.consolidationLines.isNotEmpty())

        val workspaceRoot = File(root, "memory_workspace")
        assertTrue(File(workspaceRoot, "memory.md").exists())
        assertTrue(File(workspaceRoot, "daily").listFiles().orEmpty().isNotEmpty())

        val afterDelete =
            PersonalMemoryStore.deleteGovernedEntry(
                typeWire = PersonalMemoryEntryType.CONTACT.wireName,
                entryRef = "张三",
            )
        assertEquals(1, afterDelete.totalEntries)
        assertTrue(afterDelete.entries.none { it.type == PersonalMemoryEntryType.CONTACT })
        assertTrue(observed.value.auditTrail.any { it.action == "delete" && it.type == PersonalMemoryEntryType.CONTACT })

        root.deleteRecursively()
    }

    @Test
    fun `imported insight snapshot becomes effective until local memory rewrites it`() {
        val root = Files.createTempDirectory("personal-memory-insight-import-test").toFile()
        AppRuntimeContext.init(testContext(root))
        PersonalMemoryStore.resetForTest()

        val imported =
            PersonalMemoryInsightSnapshot(
                summary = "profiles=2 facts=0 audits=1",
                consolidationSummary = "entries=2 structured=2",
                suggestionSummary = "建议先整理联系人与位置记忆",
                consolidationLines = listOf("entries=2 structured=2", "contact_anchor=张三"),
                recommendedCommands = listOf("/memory-governance"),
                updatedAtMs = 42L,
            )
        PersonalMemoryStore.importInsightSnapshot(imported)

        val restored = PersonalMemoryStore.readInsightSnapshot(limit = 4)
        assertEquals(imported.summary, restored.summary)
        assertTrue(restored.consolidationLines.contains("contact_anchor=张三"))

        PersonalMemoryStore.upsertGovernedEntry(
            typeWire = PersonalMemoryEntryType.FACT.wireName,
            primary = "用户现在更常先看消息收件箱",
        )
        val afterLocalWrite = PersonalMemoryStore.readInsightSnapshot(limit = 4)
        assertTrue(afterLocalWrite.summary.contains("profiles="))
        assertTrue(afterLocalWrite.summary != imported.summary)

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
