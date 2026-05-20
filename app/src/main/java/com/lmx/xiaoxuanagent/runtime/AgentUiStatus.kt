package com.lmx.xiaoxuanagent.runtime

enum class AgentUiStatusCategory {
    IDLE,
    BLOCKED_INPUT,
    ACTIVE_EXECUTION,
    TAKEOVER,
    TERMINAL,
}

enum class AgentUiBlockedReason {
    NONE,
    INPUT_REQUIRED,
    ACCESSIBILITY_REQUIRED,
    ROUTING,
    ROUTING_FAILED,
    APP_UNAVAILABLE,
}

enum class AgentUiExecutionPhase {
    NONE,
    STARTING,
    PLANNING,
    RUNNING,
    WAITING,
}

enum class AgentUiTakeoverReason {
    NONE,
    PAUSED,
    AWAITING_CONFIRMATION,
    WAITING_EXTERNAL,
}

enum class AgentUiTerminalReason {
    NONE,
    STOPPED,
    FAILED,
    COMPLETED,
    MAX_TURNS_REACHED,
}

sealed interface AgentUiMode {
    data object Idle : AgentUiMode

    data class BlockedInput(
        val reason: AgentUiBlockedReason,
    ) : AgentUiMode

    data class Executing(
        val phase: AgentUiExecutionPhase,
    ) : AgentUiMode

    data class Takeover(
        val reason: AgentUiTakeoverReason,
    ) : AgentUiMode

    data class Terminal(
        val reason: AgentUiTerminalReason,
    ) : AgentUiMode
}

data class AgentUiStatusModel(
    val code: String,
    val category: AgentUiStatusCategory,
    val stickyAcrossProjection: Boolean = false,
    val blockedReason: AgentUiBlockedReason = AgentUiBlockedReason.NONE,
    val executionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.NONE,
    val takeoverReason: AgentUiTakeoverReason = AgentUiTakeoverReason.NONE,
    val terminalReason: AgentUiTerminalReason = AgentUiTerminalReason.NONE,
) {
    fun toMode(): AgentUiMode =
        when (category) {
            AgentUiStatusCategory.IDLE -> AgentUiMode.Idle
            AgentUiStatusCategory.BLOCKED_INPUT -> AgentUiMode.BlockedInput(blockedReason)
            AgentUiStatusCategory.ACTIVE_EXECUTION -> AgentUiMode.Executing(executionPhase)
            AgentUiStatusCategory.TAKEOVER -> AgentUiMode.Takeover(takeoverReason)
            AgentUiStatusCategory.TERMINAL -> AgentUiMode.Terminal(terminalReason)
        }
}

data class AgentUiStatusSnapshot(
    val code: String = AgentUiStatus.IDLE,
    val category: AgentUiStatusCategory = AgentUiStatusCategory.IDLE,
    val blockedReason: AgentUiBlockedReason = AgentUiBlockedReason.NONE,
    val executionPhase: AgentUiExecutionPhase = AgentUiExecutionPhase.NONE,
    val takeoverReason: AgentUiTakeoverReason = AgentUiTakeoverReason.NONE,
    val terminalReason: AgentUiTerminalReason = AgentUiTerminalReason.NONE,
)

fun AgentUiStatusModel.toSnapshot(): AgentUiStatusSnapshot =
    AgentUiStatusSnapshot(
        code = code,
        category = category,
        blockedReason = blockedReason,
        executionPhase = executionPhase,
        takeoverReason = takeoverReason,
        terminalReason = terminalReason,
    )

fun resolveAgentUiStatusModel(
    status: String,
    snapshot: AgentUiStatusSnapshot? = null,
): AgentUiStatusModel {
    if (snapshot == null) return AgentUiStatus.resolve(status)
    return when {
        status.isBlank() || snapshot.code == status -> AgentUiStatus.resolve(snapshot)
        else -> AgentUiStatus.resolve(snapshot.copy(code = status))
    }
}

fun AgentUiStatusSnapshot.resolveModel(
    fallbackStatus: String = code,
): AgentUiStatusModel = resolveAgentUiStatusModel(fallbackStatus, this)

fun AgentUiStatusModel.isExecutionPhase(
    vararg phases: AgentUiExecutionPhase,
): Boolean = category == AgentUiStatusCategory.ACTIVE_EXECUTION && executionPhase in phases

fun AgentUiStatusModel.isTakeoverReason(
    vararg reasons: AgentUiTakeoverReason,
): Boolean = category == AgentUiStatusCategory.TAKEOVER && takeoverReason in reasons

fun AgentUiStatusModel.isTerminalReason(
    vararg reasons: AgentUiTerminalReason,
): Boolean = category == AgentUiStatusCategory.TERMINAL && terminalReason in reasons

object AgentUiStatus {
    const val IDLE = "IDLE"
    const val INPUT_REQUIRED = "INPUT_REQUIRED"
    const val ACCESSIBILITY_REQUIRED = "ACCESSIBILITY_REQUIRED"
    const val ROUTING = "ROUTING"
    const val ROUTING_FAILED = "ROUTING_FAILED"
    const val APP_UNAVAILABLE = "APP_UNAVAILABLE"
    const val RUNNING = "RUNNING"
    const val PLANNING = "PLANNING"
    const val PAUSED = "PAUSED"
    const val AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"
    const val STARTING = "STARTING"
    const val WAITING = "WAITING"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
    const val MAX_TURNS_REACHED = "MAX_TURNS_REACHED"
    const val WAITING_EXTERNAL = "WAITING_EXTERNAL"

    private val definitions =
        listOf(
            AgentUiStatusModel(IDLE, AgentUiStatusCategory.IDLE),
            AgentUiStatusModel(
                INPUT_REQUIRED,
                AgentUiStatusCategory.BLOCKED_INPUT,
                stickyAcrossProjection = true,
                blockedReason = AgentUiBlockedReason.INPUT_REQUIRED,
            ),
            AgentUiStatusModel(
                ACCESSIBILITY_REQUIRED,
                AgentUiStatusCategory.BLOCKED_INPUT,
                stickyAcrossProjection = true,
                blockedReason = AgentUiBlockedReason.ACCESSIBILITY_REQUIRED,
            ),
            AgentUiStatusModel(
                ROUTING,
                AgentUiStatusCategory.BLOCKED_INPUT,
                stickyAcrossProjection = true,
                blockedReason = AgentUiBlockedReason.ROUTING,
            ),
            AgentUiStatusModel(
                ROUTING_FAILED,
                AgentUiStatusCategory.BLOCKED_INPUT,
                stickyAcrossProjection = true,
                blockedReason = AgentUiBlockedReason.ROUTING_FAILED,
            ),
            AgentUiStatusModel(
                APP_UNAVAILABLE,
                AgentUiStatusCategory.BLOCKED_INPUT,
                stickyAcrossProjection = true,
                blockedReason = AgentUiBlockedReason.APP_UNAVAILABLE,
            ),
            AgentUiStatusModel(RUNNING, AgentUiStatusCategory.ACTIVE_EXECUTION, executionPhase = AgentUiExecutionPhase.RUNNING),
            AgentUiStatusModel(PLANNING, AgentUiStatusCategory.ACTIVE_EXECUTION, executionPhase = AgentUiExecutionPhase.PLANNING),
            AgentUiStatusModel(PAUSED, AgentUiStatusCategory.TAKEOVER, takeoverReason = AgentUiTakeoverReason.PAUSED),
            AgentUiStatusModel(
                AWAITING_CONFIRMATION,
                AgentUiStatusCategory.TAKEOVER,
                takeoverReason = AgentUiTakeoverReason.AWAITING_CONFIRMATION,
            ),
            AgentUiStatusModel(STARTING, AgentUiStatusCategory.ACTIVE_EXECUTION, executionPhase = AgentUiExecutionPhase.STARTING),
            AgentUiStatusModel(WAITING, AgentUiStatusCategory.ACTIVE_EXECUTION, executionPhase = AgentUiExecutionPhase.WAITING),
            AgentUiStatusModel(STOPPED, AgentUiStatusCategory.TERMINAL, terminalReason = AgentUiTerminalReason.STOPPED),
            AgentUiStatusModel(FAILED, AgentUiStatusCategory.TERMINAL, terminalReason = AgentUiTerminalReason.FAILED),
            AgentUiStatusModel(COMPLETED, AgentUiStatusCategory.TERMINAL, terminalReason = AgentUiTerminalReason.COMPLETED),
            AgentUiStatusModel(
                MAX_TURNS_REACHED,
                AgentUiStatusCategory.TERMINAL,
                terminalReason = AgentUiTerminalReason.MAX_TURNS_REACHED,
            ),
            AgentUiStatusModel(
                WAITING_EXTERNAL,
                AgentUiStatusCategory.TAKEOVER,
                takeoverReason = AgentUiTakeoverReason.WAITING_EXTERNAL,
            ),
        )
    private val definitionsByCode = definitions.associateBy(AgentUiStatusModel::code)

    val terminalStatuses =
        definitions
            .filter { it.category == AgentUiStatusCategory.TERMINAL }
            .mapTo(linkedSetOf(), AgentUiStatusModel::code)

    fun resolve(status: String): AgentUiStatusModel =
        definitionsByCode[status] ?: AgentUiStatusModel(code = status.ifBlank { IDLE }, category = AgentUiStatusCategory.IDLE)

    fun resolve(snapshot: AgentUiStatusSnapshot?): AgentUiStatusModel {
        if (snapshot == null) return resolve(IDLE)
        val resolved = resolve(snapshot.code)
        return if (
            resolved.category == snapshot.category &&
            resolved.blockedReason == snapshot.blockedReason &&
            resolved.executionPhase == snapshot.executionPhase &&
            resolved.takeoverReason == snapshot.takeoverReason &&
            resolved.terminalReason == snapshot.terminalReason
        ) {
            resolved
        } else {
            AgentUiStatusModel(
                code = snapshot.code.ifBlank { IDLE },
                category = snapshot.category,
                blockedReason = snapshot.blockedReason,
                executionPhase = snapshot.executionPhase,
                takeoverReason = snapshot.takeoverReason,
                terminalReason = snapshot.terminalReason,
            )
        }
    }

    fun isTerminal(status: String): Boolean = resolve(status).category == AgentUiStatusCategory.TERMINAL

    fun isBlockingInput(status: String): Boolean = resolve(status).category == AgentUiStatusCategory.BLOCKED_INPUT

    fun isTakeoverLike(status: String): Boolean = resolve(status).category == AgentUiStatusCategory.TAKEOVER

    fun isActiveExecution(status: String): Boolean = resolve(status).category == AgentUiStatusCategory.ACTIVE_EXECUTION

    fun isProjectionSticky(status: String): Boolean = resolve(status).stickyAcrossProjection

    fun isPaused(status: String): Boolean = resolve(status).takeoverReason == AgentUiTakeoverReason.PAUSED

    fun isWaitingExternal(status: String): Boolean =
        resolve(status).takeoverReason == AgentUiTakeoverReason.WAITING_EXTERNAL

    fun isCompleted(status: String): Boolean =
        resolve(status).terminalReason == AgentUiTerminalReason.COMPLETED

    fun isFailed(status: String): Boolean =
        resolve(status).terminalReason == AgentUiTerminalReason.FAILED

    fun isStopped(status: String): Boolean =
        resolve(status).terminalReason == AgentUiTerminalReason.STOPPED

    fun isMaxTurnsReached(status: String): Boolean =
        resolve(status).terminalReason == AgentUiTerminalReason.MAX_TURNS_REACHED

    fun fromTakeoverReason(reason: AgentUiTakeoverReason): String =
        when (reason) {
            AgentUiTakeoverReason.NONE -> IDLE
            AgentUiTakeoverReason.PAUSED -> PAUSED
            AgentUiTakeoverReason.AWAITING_CONFIRMATION -> AWAITING_CONFIRMATION
            AgentUiTakeoverReason.WAITING_EXTERNAL -> WAITING_EXTERNAL
        }

    fun modelForBlockedReason(reason: AgentUiBlockedReason): AgentUiStatusModel =
        when (reason) {
            AgentUiBlockedReason.NONE -> resolve(IDLE)
            AgentUiBlockedReason.INPUT_REQUIRED -> resolve(INPUT_REQUIRED)
            AgentUiBlockedReason.ACCESSIBILITY_REQUIRED -> resolve(ACCESSIBILITY_REQUIRED)
            AgentUiBlockedReason.ROUTING -> resolve(ROUTING)
            AgentUiBlockedReason.ROUTING_FAILED -> resolve(ROUTING_FAILED)
            AgentUiBlockedReason.APP_UNAVAILABLE -> resolve(APP_UNAVAILABLE)
        }

    fun modelForTakeoverReason(reason: AgentUiTakeoverReason): AgentUiStatusModel =
        resolve(fromTakeoverReason(reason))

    fun fromTerminalReason(reason: AgentUiTerminalReason): String =
        when (reason) {
            AgentUiTerminalReason.NONE -> IDLE
            AgentUiTerminalReason.STOPPED -> STOPPED
            AgentUiTerminalReason.FAILED -> FAILED
            AgentUiTerminalReason.COMPLETED -> COMPLETED
            AgentUiTerminalReason.MAX_TURNS_REACHED -> MAX_TURNS_REACHED
        }

    fun modelForTerminalReason(reason: AgentUiTerminalReason): AgentUiStatusModel =
        resolve(fromTerminalReason(reason))

    fun fromExecutionPhase(phase: AgentUiExecutionPhase): String =
        when (phase) {
            AgentUiExecutionPhase.NONE -> IDLE
            AgentUiExecutionPhase.STARTING -> STARTING
            AgentUiExecutionPhase.PLANNING -> PLANNING
            AgentUiExecutionPhase.RUNNING -> RUNNING
            AgentUiExecutionPhase.WAITING -> WAITING
        }

    fun modelForExecutionPhase(phase: AgentUiExecutionPhase): AgentUiStatusModel =
        resolve(fromExecutionPhase(phase))

    fun deriveProjectedStatus(
        explicitStatus: String,
        runtimeStatus: String,
        sessionRunning: Boolean,
        awaitingConfirmation: Boolean,
        hasActiveRuntimeSession: Boolean,
    ): String {
        if (awaitingConfirmation) return AWAITING_CONFIRMATION
        val resolvedRuntimeStatus = resolve(runtimeStatus)
        val resolvedExplicitStatus = resolve(explicitStatus)
        return when {
            hasActiveRuntimeSession && resolvedRuntimeStatus.code != IDLE -> resolvedRuntimeStatus.code
            hasActiveRuntimeSession && resolvedExplicitStatus.stickyAcrossProjection -> resolvedExplicitStatus.code
            hasActiveRuntimeSession && sessionRunning -> RUNNING
            hasActiveRuntimeSession -> IDLE
            else -> resolvedExplicitStatus.code
        }
    }
}
