package com.lmx.xiaoxuanagent.runtime

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilityApprovalStoreTest {
    @Test
    fun `session grant can authorize future request and be revoked`() {
        val root = Files.createTempDirectory("capability-approval-store-test").toFile()
        AppRuntimeContext.init(testContext(root))
        PlatformCapabilityApprovalStore.resetForTest()
        SessionCapabilityPolicyStore.resetForTest()

        val approval =
            PlatformCapabilityApprovalRequest(
                approvalId = "approval_1",
                capability = SessionCapabilityKey.START_SESSION,
                permissionFamily = "session_control",
                riskLevel = "medium",
                sessionId = "session_a",
                entrySource = "external_signal:notification",
                summary = "允许从通知恢复当前 session",
                status = PlatformCapabilityApprovalStatus.APPROVED,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            )

        val grant =
            PlatformCapabilityApprovalStore.issueGrant(
                approval = approval,
                scope = "session",
                ttlMinutes = 30,
                note = "session scoped allow",
            )
        assertNotNull(grant)
        assertEquals("session", grant?.scope)

        val matched =
            PlatformCapabilityApprovalStore.resolveGrant(
                SessionCapabilityRequest(
                    key = SessionCapabilityKey.START_SESSION,
                    sessionId = "session_a",
                    entrySource = "external_signal:notification",
                ),
            )
        assertNotNull(matched)

        val policy =
            SessionCapabilityPolicyStore.resolve(
                SessionCapabilityRequest(
                    key = SessionCapabilityKey.START_SESSION,
                    sessionId = "session_a",
                    entrySource = "external_signal:notification",
                ),
            )
        assertEquals(SessionCapabilityPolicyDecision.ALLOW, policy.decision)
        assertTrue(policy.reason.startsWith("authorization_grant"))

        val revoked = PlatformCapabilityApprovalStore.revokeGrant(grant!!.grantId)
        assertNotNull(revoked)
        val blockedPolicy =
            SessionCapabilityPolicyStore.resolve(
                SessionCapabilityRequest(
                    key = SessionCapabilityKey.START_SESSION,
                    sessionId = "session_a",
                    entrySource = "external_signal:notification",
                ),
            )
        assertEquals(SessionCapabilityPolicyDecision.REVIEW, blockedPolicy.decision)

        root.deleteRecursively()
    }

    @Test
    fun `session grant follows session graph root and expires with root session`() {
        val root = Files.createTempDirectory("capability-approval-store-graph-test").toFile()
        AppRuntimeContext.init(testContext(root))
        PlatformCapabilityApprovalStore.resetForTest()
        SessionCapabilityPolicyStore.resetForTest()
        SessionSessionGraphStore.resetForTest()

        SessionSessionGraphStore.importJson(
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("session_id", "session_root")
                        put("root_session_id", "session_root")
                        put("status", AgentUiStatus.RUNNING)
                    },
                )
                put(
                    JSONObject().apply {
                        put("session_id", "session_child_a")
                        put("parent_session_id", "session_root")
                        put("root_session_id", "session_root")
                        put("status", AgentUiStatus.RUNNING)
                    },
                )
                put(
                    JSONObject().apply {
                        put("session_id", "session_child_b")
                        put("parent_session_id", "session_root")
                        put("root_session_id", "session_root")
                        put("status", AgentUiStatus.RUNNING)
                    },
                )
            },
        )

        val grant =
            PlatformCapabilityApprovalStore.issueGrant(
                approval =
                    PlatformCapabilityApprovalRequest(
                        approvalId = "approval_graph",
                        capability = SessionCapabilityKey.START_SESSION,
                        permissionFamily = "session_control",
                        riskLevel = "medium",
                        sessionId = "session_child_a",
                        entrySource = "external_signal:notification",
                        summary = "允许当前任务树继续调度",
                        status = PlatformCapabilityApprovalStatus.APPROVED,
                        createdAtMs = 1L,
                        updatedAtMs = 1L,
                    ),
                scope = "session",
                ttlMinutes = 30,
                note = "root scoped session grant",
            )
        assertEquals("session_root", grant?.rootSessionId)

        val matched =
            PlatformCapabilityApprovalStore.resolveGrant(
                SessionCapabilityRequest(
                    key = SessionCapabilityKey.START_SESSION,
                    sessionId = "session_child_b",
                    entrySource = "external_signal:notification",
                ),
            )
        assertNotNull(matched)

        val expired = PlatformCapabilityApprovalStore.expireSessionGrantsForSession("session_root")
        assertEquals(1, expired.size)
        assertEquals(PlatformCapabilityGrantStatus.EXPIRED, expired.first().status)

        val afterExpire =
            PlatformCapabilityApprovalStore.resolveGrant(
                SessionCapabilityRequest(
                    key = SessionCapabilityKey.START_SESSION,
                    sessionId = "session_child_b",
                    entrySource = "external_signal:notification",
                ),
            )
        assertNull(afterExpire)

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
