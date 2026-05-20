package com.lmx.xiaoxuanagent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWorkspaceGovernanceTest {
    @Test
    fun `build recall lines prefers daily and generated entries`() {
        val lines =
            MemoryWorkspaceGovernance.buildRecallLines(
                snapshot =
                    MemoryWorkspaceSnapshot(
                        totalFiles = 3,
                        summary = "files=3 fresh=2 aging=1 stale=0",
                        entries =
                            listOf(
                                MemoryWorkspaceEntry(
                                    name = "memory.md",
                                    path = "/tmp/memory.md",
                                    type = MemoryWorkspaceEntryType.CORE,
                                    summary = "核心摘要",
                                ),
                                MemoryWorkspaceEntry(
                                    name = "2026-04-09.md",
                                    path = "/tmp/daily/2026-04-09.md",
                                    type = MemoryWorkspaceEntryType.DAILY,
                                    summary = "今天处理了会话恢复",
                                ),
                                MemoryWorkspaceEntry(
                                    name = "artifact_notes.md",
                                    path = "/tmp/generated/artifact_notes.md",
                                    type = MemoryWorkspaceEntryType.GENERATED,
                                    summary = "整理 artifact 生命周期治理",
                                ),
                            ),
                    ),
                limit = 4,
            )

        assertEquals("记忆工作区: files=3 fresh=2 aging=1 stale=0", lines.first())
        assertTrue(lines.any { it.contains("2026-04-09.md") })
        assertTrue(lines.any { it.contains("artifact_notes.md") })
        assertTrue(lines.none { it.contains("memory.md") })
    }
}
