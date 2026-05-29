package com.lmx.xiaoxuanagent.skills

import com.lmx.xiaoxuanagent.agent.AgentAction
import com.lmx.xiaoxuanagent.agent.AgentDecision
import com.lmx.xiaoxuanagent.agent.SkillLayer

/**
 * 提醒 / 闹钟 / 倒计时 / 秒表 / 日程 域技能（P0-4）。
 *
 * 把这类"要把事办成"的任务从脆弱的 GUI 翻找，引导到**确定性系统 Intent 工具**
 * （create_alarm / create_timer / open_stopwatch / insert_calendar_event）：
 * - 子类型足够清晰且能抽到必要参数时，maybePrePlan 直接发对应系统工具（按 history 去重，只发一次）。
 * - 参数不全或模糊时返回 null，由 spec.instructions 引导 planner 选系统工具并由 LLM 抽取参数。
 *
 * 注意：GUI 事务型域技能（下单 / 支付 / 结账 / 表单填写 / 邮件发送）依赖真实 App 界面与安全门控的
 * 真机校验，不在此技能内，留待真机阶段（#1）按真实页面构建，避免盲建未经验证的 GUI nudge。
 */
internal object ReminderSchedulingSkill : ExecutableSkill {
    override val spec: SkillSpec =
        SkillSpec(
            id = "reminder_scheduling",
            title = "提醒与日程",
            description = "适合设闹钟、提醒、倒计时、秒表与日历日程。",
            instructions =
                "这类任务优先用系统工具落地：闹钟用 create_alarm，倒计时用 create_timer，秒表用 open_stopwatch，" +
                    "日程 / 会议用 insert_calendar_event；不要在时钟 / 日历 App 里手动翻找界面。时间与标题参数从任务文本中抽取。",
            keywords = listOf("提醒", "闹钟", "叫我", "倒计时", "计时", "秒表", "日程", "日历", "安排", "预定", "会议"),
            priority = 9,
            layer = SkillLayer.DOMAIN,
            requiredTools =
                listOf(
                    "system.create_alarm",
                    "system.create_timer",
                    "system.open_stopwatch",
                    "system.insert_calendar_event",
                ),
        )

    override fun shouldActivate(
        context: SkillDecisionContext,
    ): Boolean = spec.keywords.any { context.task.contains(it) }

    override fun maybePrePlan(
        context: SkillDecisionContext,
    ): AgentDecision? {
        val task = context.task.trim()
        val subtype = classify(task) ?: return null
        // 已经发过对应系统动作则不再重复（避免循环 / 重复落地）。
        if (context.history.any { it.action.startsWith(subtype.toolPrefix) }) return null
        val action = subtype.buildAction(task) ?: return null
        return AgentDecision(
            action = action,
            reason = "技能预执行：识别为${subtype.label}任务，直接用系统工具 ${action.toolName} 办成，避免 GUI 翻找。",
            rawResponse =
                """{"skill":"reminder_scheduling","subtype":"${subtype.name.lowercase()}","action":"${action.label}"}""",
        )
    }

    private fun classify(task: String): Subtype? =
        when {
            task.contains("秒表") -> Subtype.STOPWATCH
            listOf("倒计时", "计时").any { task.contains(it) } -> Subtype.TIMER
            listOf("日程", "日历", "安排", "预定", "会议").any { task.contains(it) } -> Subtype.CALENDAR
            listOf("闹钟", "叫我", "提醒").any { task.contains(it) } -> Subtype.ALARM
            else -> null
        }

    private enum class Subtype(
        val label: String,
        val toolPrefix: String,
    ) {
        STOPWATCH("秒表", "open_stopwatch"),
        TIMER("倒计时", "create_timer"),
        ALARM("闹钟提醒", "create_alarm"),
        CALENDAR("日程", "insert_calendar_event"),
        ;

        fun buildAction(task: String): AgentAction? =
            when (this) {
                STOPWATCH -> AgentAction.OpenStopwatch
                TIMER -> extractDuration(task)?.let { AgentAction.CreateTimer(durationLabel = it, message = task.take(40)) }
                ALARM -> extractClockTime(task)?.let { AgentAction.CreateAlarm(timeLabel = it, message = task.take(40)) }
                CALENDAR ->
                    extractWhen(task).takeIf { it.isNotBlank() }?.let {
                        AgentAction.InsertCalendarEvent(title = task.take(40), whenLabel = it)
                    }
            }
    }

    private val durationPattern = Regex("""(\d+)\s*(个小时|小时|分钟|分|秒钟|秒|时)""")
    private val clockPattern = Regex("""(上午|下午|早上|早晨|晚上|中午|凌晨)?\s*(\d{1,2})\s*[:：点]\s*(\d{1,2})?\s*分?""")
    private val datePattern = Regex("""(今天|明天|后天|大后天|下周[一二三四五六日天]?|周[一二三四五六日天]|\d{1,2}月\d{1,2}日?)""")

    private fun extractDuration(task: String): String? = durationPattern.find(task)?.value?.trim()

    private fun extractClockTime(task: String): String? = clockPattern.find(task)?.value?.trim()

    private fun extractWhen(task: String): String =
        (extractClockTime(task) ?: datePattern.find(task)?.value).orEmpty().trim()
}
