package com.lmx.xiaoxuanagent.assistantos

import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object AssistantProductShellStore {
    private const val PRODUCT_SHELL_DIR = "assistant_os"
    private const val PRODUCT_SHELL_FILE = "product_shell.json"
    internal const val MAX_LEDGER_SIZE = 32
    private val lock = Any()
    @Volatile
    private var hydrated = false
    private var snapshot = AssistantProductShellSnapshot()
    private val snapshotFlow = MutableStateFlow(snapshot)

    fun read(): AssistantProductShellSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot
        }

    fun observe(): StateFlow<AssistantProductShellSnapshot> =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshotFlow.asStateFlow()
        }

    fun update(
        reducer: (AssistantProductShellSnapshot) -> AssistantProductShellSnapshot,
    ): AssistantProductShellSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = snapshot
            val reduced = reducer(current)
            if (isEquivalent(current, reduced)) {
                return@synchronized current
            }
            val next = reduced.copy(updatedAtMs = System.currentTimeMillis())
            writeUnlocked(next)
            next
        }

    fun replace(
        snapshot: AssistantProductShellSnapshot,
    ): AssistantProductShellSnapshot =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            val current = this.snapshot
            if (isEquivalent(current, snapshot)) {
                return@synchronized current
            }
            val next = snapshot.copy(updatedAtMs = System.currentTimeMillis())
            writeUnlocked(next)
            next
        }

    internal fun isEquivalent(
        left: AssistantProductShellSnapshot,
        right: AssistantProductShellSnapshot,
    ): Boolean = left.semanticComparable() == right.semanticComparable()

    fun exportJson(): JSONObject =
        synchronized(lock) {
            hydrateIfNeededUnlocked()
            snapshot.toJson()
        }

    fun importJson(
        json: JSONObject?,
    ): AssistantProductShellSnapshot =
        replace(json?.toAssistantProductShellSnapshot() ?: AssistantProductShellSnapshot())

    fun acknowledgeTip(
        tipId: String,
        action: String,
        note: String = "",
    ): AssistantProductShellSnapshot =
        update { current ->
            val now = System.currentTimeMillis()
            val currentLedger = AssistantTipScheduler.ledgerForProductShell(current)
            val nextLedger =
                currentLedger.map { tip ->
                    if (tip.id != tipId) {
                        tip
                    } else {
                        when (AssistantTipScheduler.normalizeAction(action)) {
                            "dismiss" ->
                                tip.copy(
                                    status = "dismissed",
                                    note = note.ifBlank { "dismissed" },
                                    nextEligibleAtMs = now + AssistantTipScheduler.cooldownFor(tip.source),
                                    dismissedAtMs = now,
                                    updatedAtMs = now,
                                )

                            "complete" ->
                                tip.copy(
                                    status = "completed",
                                    note = note.ifBlank { "completed" },
                                    nextEligibleAtMs = Long.MAX_VALUE,
                                    completedAtMs = now,
                                    updatedAtMs = now,
                                )

                            else ->
                                tip.copy(
                                    status = "active",
                                    note = note,
                                    nextEligibleAtMs = 0L,
                                    updatedAtMs = now,
                                )
                        }
                    }
                }
            AssistantAnalyticsStore.logEvent(
                name = "tip_acknowledged",
                source = "product_shell",
                metadata = mapOf("tip_id" to tipId, "action" to action),
            )
            val presented = AssistantTipScheduler.present(nextLedger, now, AssistantProductShellController.MAX_TIPS)
            current.copy(
                tips = presented.visible,
                tipLedger = AssistantTipScheduler.compactLedger(presented.ledger, MAX_LEDGER_SIZE),
            )
        }

    fun updateOnboardingStep(
        stepId: String,
        action: String,
        note: String = "",
    ): AssistantProductShellSnapshot =
        update { current ->
            val now = System.currentTimeMillis()
            val nextSteps =
                current.onboarding.steps.map { step ->
                    if (step.id != stepId) {
                        step
                    } else {
                        when (normalizeProductOnboardingAction(action)) {
                            "complete" ->
                                step.copy(
                                    status = "completed",
                                    manualState = "completed",
                                    note = note,
                                    completedAtMs = if (step.completedAtMs > 0L) step.completedAtMs else now,
                                    updatedAtMs = now,
                                )

                            "skip" ->
                                step.copy(
                                    status = "skipped",
                                    manualState = "skipped",
                                    note = note,
                                    updatedAtMs = now,
                                )

                            else ->
                                step.copy(
                                    manualState = "",
                                    note = note,
                                    status = if (step.requirementMet) "completed" else "pending",
                                    completedAtMs =
                                        if (step.requirementMet) {
                                            step.completedAtMs.takeIf { it > 0L } ?: now
                                        } else {
                                            0L
                                        },
                                    updatedAtMs = now,
                                )
                        }
                    }
                }
            AssistantAnalyticsStore.logEvent(
                name = "onboarding_updated",
                source = "product_shell",
                metadata = mapOf("step_id" to stepId, "action" to action),
            )
            current.copy(
                onboarding = rebuildProductOnboardingState(nextSteps, now),
            )
        }

    fun updateRoutinePolicy(
        reducer: (AssistantRoutinePolicySnapshot) -> AssistantRoutinePolicySnapshot,
    ): AssistantProductShellSnapshot =
        update { current ->
            current.copy(routinePolicy = reducer(current.routinePolicy).copy(updatedAtMs = System.currentTimeMillis()))
        }

    fun updateDigestPolicy(
        reducer: (AssistantDigestPolicySnapshot) -> AssistantDigestPolicySnapshot,
    ): AssistantProductShellSnapshot =
        update { current ->
            current.copy(digestPolicy = reducer(current.digestPolicy).copy(updatedAtMs = System.currentTimeMillis()))
        }

    fun updateQuietHours(
        reducer: (AssistantQuietHoursSnapshot) -> AssistantQuietHoursSnapshot,
    ): AssistantProductShellSnapshot =
        update { current ->
            current.copy(quietHours = reducer(current.quietHours).copy(updatedAtMs = System.currentTimeMillis()))
        }

    fun updateInterruptPolicy(
        reducer: (AssistantInterruptPolicySnapshot) -> AssistantInterruptPolicySnapshot,
    ): AssistantProductShellSnapshot =
        update { current ->
            current.copy(interruptPolicy = reducer(current.interruptPolicy).copy(updatedAtMs = System.currentTimeMillis()))
        }

    private fun hydrateIfNeededUnlocked() {
        if (hydrated) return
        hydrated = true
        val file = snapshotFile()
        snapshot =
            if (file == null || !file.exists()) {
                AssistantProductShellSnapshot()
            } else {
                runCatching {
                    JSONObject(file.readText()).toAssistantProductShellSnapshot()
                }.getOrDefault(AssistantProductShellSnapshot())
            }
        snapshotFlow.value = snapshot
    }

    private fun writeUnlocked(
        snapshot: AssistantProductShellSnapshot,
    ) {
        this.snapshot = snapshot
        snapshotFlow.value = snapshot
        val file = snapshotFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText(snapshot.toJson().toString(2))
    }

    private fun snapshotFile(): File? {
        val context = AppRuntimeContext.get() ?: return null
        return File(File(context.filesDir, PRODUCT_SHELL_DIR), PRODUCT_SHELL_FILE)
    }

    private fun AssistantProductShellSnapshot.semanticComparable(): AssistantProductShellSnapshot =
        copy(
            lastSyncReason = "",
            updatedAtMs = 0L,
        )
}
