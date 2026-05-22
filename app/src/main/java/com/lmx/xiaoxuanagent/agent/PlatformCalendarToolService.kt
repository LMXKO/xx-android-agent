package com.lmx.xiaoxuanagent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.CalendarContract
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal data class CalendarEventWindow(
    val beginAtMs: Long,
    val endAtMs: Long,
    val normalizedLabel: String,
)

object PlatformCalendarToolService {
    private const val DEFAULT_DURATION_MINUTES = 60L

    fun insertEvent(
        service: AccessibilityService,
        title: String,
        details: String,
        whenLabel: String,
    ): AgentExecutionResult {
        if (title.isBlank()) {
            return AgentExecutionResult(
                message = "日历事件标题为空，无法创建日程。",
                keepRunning = false,
            )
        }

        val eventWindow = resolveEventWindow(whenLabel)
        val intent =
            Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title.take(80))
                buildDescription(details, whenLabel, eventWindow).takeIf { it.isNotBlank() }?.let {
                    putExtra(CalendarContract.Events.DESCRIPTION, it.take(400))
                }
                eventWindow?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.beginAtMs)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.endAtMs)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        val detailLines = receiptLines(title = title, whenLabel = whenLabel, eventWindow = eventWindow)
        val successMessage =
            buildString {
                append("已打开日历新建事件：").append(title.take(24))
                append(if (eventWindow != null) "，已带入时间。" else "，请确认时间。")
            }
        return runCatching {
            service.startActivity(intent)
            AgentExecutionResult(
                message = successMessage,
                keepRunning = true,
                requiresObservationCheck = true,
                recommendedWaitMs = 1_000L,
                groundingSource = "system_calendar",
                toolRuntimeDetailLines = detailLines,
            ).also {
                UtilityExecutionReceiptStore.record(
                    toolName = "system.insert_calendar_event",
                    summary = successMessage,
                    detailLines = detailLines,
                    handoffRequired = true,
                )
            }
        }.getOrElse { error ->
            AgentExecutionResult("打开日历新建事件失败：${error.message.orEmpty()}".trim(), keepRunning = false)
        }
    }

    internal fun resolveEventWindow(
        whenLabel: String,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): CalendarEventWindow? {
        val normalized = normalizeLabel(whenLabel)
        if (normalized.isBlank()) return null

        val timeRange = parseTimeRange(normalized)
        val startTime = timeRange.start ?: periodDefaultTime(normalized)
        val date =
            parseExplicitDate(normalized, now)
                ?: parseRelativeDate(normalized, now)
                ?: if (timeRange.start != null || hasPeriodHint(normalized)) now.toLocalDate() else return null

        var start = ZonedDateTime.of(date, startTime, now.zone)
        if (date == now.toLocalDate() && !hasRelativeTodayHint(normalized) && start.isBefore(now.minusMinutes(1))) {
            start = start.plusDays(1)
        }
        val end =
            when {
                timeRange.end != null -> {
                    var candidate = ZonedDateTime.of(start.toLocalDate(), timeRange.end, now.zone)
                    if (!candidate.isAfter(start)) candidate = candidate.plusDays(1)
                    candidate
                }
                timeRange.durationMinutes != null -> start.plusMinutes(timeRange.durationMinutes)
                else -> start.plusMinutes(DEFAULT_DURATION_MINUTES)
            }

        return CalendarEventWindow(
            beginAtMs = start.toInstant().toEpochMilli(),
            endAtMs = end.toInstant().toEpochMilli(),
            normalizedLabel =
                listOf(
                    start.toLocalDate().toString(),
                    start.toLocalTime().toString().take(5),
                    end.toLocalTime().toString().take(5),
                ).joinToString(" "),
        )
    }

    internal fun receiptLines(
        title: String,
        whenLabel: String,
        eventWindow: CalendarEventWindow?,
    ): List<String> =
        buildList {
            add("receipt=calendar_event_draft_opened")
            add("title=${title.take(48)}")
            add("when_label=${whenLabel.ifBlank { "-" }.take(48)}")
            eventWindow?.let {
                add("begin_at_ms=${it.beginAtMs}")
                add("end_at_ms=${it.endAtMs}")
                add("normalized_when=${it.normalizedLabel}")
            } ?: add("time_parse=unresolved")
            add("mode=user_confirm_save")
            add("completion=user_save_required")
        }

    private fun buildDescription(
        details: String,
        whenLabel: String,
        eventWindow: CalendarEventWindow?,
    ): String {
        val trimmedDetails = details.trim()
        if (whenLabel.isBlank() || eventWindow != null) return trimmedDetails
        val timeHint = "时间线索：${whenLabel.trim().take(80)}"
        return listOf(trimmedDetails, timeHint).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun normalizeLabel(
        raw: String,
    ): String {
        val compact =
            raw
            .trim()
            .replace('：', ':')
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.getDefault())
        return replaceChineseHourTokens(compact)
    }

    private fun replaceChineseHourTokens(
        label: String,
    ): String =
        Regex("""([零〇一二两三四五六七八九十]{1,3})\s*点""").replace(label) { match ->
            val hour = parseChineseHour(match.groupValues[1])
            if (hour == null) match.value else "${hour}点"
        }

    private fun parseChineseHour(
        raw: String,
    ): Int? {
        val normalized = raw.replace('两', '二').replace('〇', '零')
        val digitMap =
            mapOf(
                '零' to 0,
                '一' to 1,
                '二' to 2,
                '三' to 3,
                '四' to 4,
                '五' to 5,
                '六' to 6,
                '七' to 7,
                '八' to 8,
                '九' to 9,
            )
        val value =
            when {
                normalized == "十" -> 10
                normalized.startsWith("十") ->
                    10 + (digitMap[normalized.getOrNull(1)] ?: return null)
                normalized.endsWith("十") ->
                    (digitMap[normalized.firstOrNull()] ?: return null) * 10
                normalized.contains("十") -> {
                    val parts = normalized.split("十", limit = 2)
                    val tens = digitMap[parts.firstOrNull()?.firstOrNull()] ?: return null
                    val ones = parts.getOrNull(1)?.firstOrNull()?.let { digitMap[it] } ?: 0
                    tens * 10 + ones
                }
                normalized.length == 1 -> digitMap[normalized.first()]
                else -> null
            } ?: return null
        return value.takeIf { it in 0..23 }
    }

    private fun parseExplicitDate(
        label: String,
        now: ZonedDateTime,
    ): LocalDate? {
        Regex("""(\d{4})[年/\-.](\d{1,2})[月/\-.](\d{1,2})""").find(label)?.let { match ->
            return buildDate(
                year = match.groupValues[1].toIntOrNull(),
                month = match.groupValues[2].toIntOrNull(),
                day = match.groupValues[3].toIntOrNull(),
            )
        }
        Regex("""(\d{1,2})[月/\-.](\d{1,2})(?:日|号)?""").find(label)?.let { match ->
            val date =
                buildDate(
                    year = now.year,
                    month = match.groupValues[1].toIntOrNull(),
                    day = match.groupValues[2].toIntOrNull(),
                ) ?: return null
            return if (date.isBefore(now.toLocalDate().minusDays(1))) date.plusYears(1) else date
        }
        return null
    }

    private fun parseRelativeDate(
        label: String,
        now: ZonedDateTime,
    ): LocalDate? {
        val today = now.toLocalDate()
        return when {
            label.contains("后天") -> today.plusDays(2)
            label.contains("明天") || label.contains("tomorrow") -> today.plusDays(1)
            hasRelativeTodayHint(label) -> today
            weekdayFrom(label) != null -> resolveWeekday(label, today, weekdayFrom(label) ?: return null)
            else -> null
        }
    }

    private fun buildDate(
        year: Int?,
        month: Int?,
        day: Int?,
    ): LocalDate? =
        runCatching {
            LocalDate.of(year ?: return null, month ?: return null, day ?: return null)
        }.getOrNull()

    private fun resolveWeekday(
        label: String,
        today: LocalDate,
        weekday: DayOfWeek,
    ): LocalDate {
        if (label.contains("下周") || label.contains("next week")) {
            val nextWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
            return nextWeekStart.plusDays((weekday.value - DayOfWeek.MONDAY.value).toLong())
        }
        val candidate = today.with(TemporalAdjusters.nextOrSame(weekday))
        return if (candidate == today && !label.contains("本周")) candidate else candidate
    }

    private fun weekdayFrom(
        label: String,
    ): DayOfWeek? {
        val mappings =
            listOf(
                "周一" to DayOfWeek.MONDAY,
                "星期一" to DayOfWeek.MONDAY,
                "monday" to DayOfWeek.MONDAY,
                "周二" to DayOfWeek.TUESDAY,
                "星期二" to DayOfWeek.TUESDAY,
                "tuesday" to DayOfWeek.TUESDAY,
                "周三" to DayOfWeek.WEDNESDAY,
                "星期三" to DayOfWeek.WEDNESDAY,
                "wednesday" to DayOfWeek.WEDNESDAY,
                "周四" to DayOfWeek.THURSDAY,
                "星期四" to DayOfWeek.THURSDAY,
                "thursday" to DayOfWeek.THURSDAY,
                "周五" to DayOfWeek.FRIDAY,
                "星期五" to DayOfWeek.FRIDAY,
                "friday" to DayOfWeek.FRIDAY,
                "周六" to DayOfWeek.SATURDAY,
                "星期六" to DayOfWeek.SATURDAY,
                "saturday" to DayOfWeek.SATURDAY,
                "周日" to DayOfWeek.SUNDAY,
                "星期日" to DayOfWeek.SUNDAY,
                "星期天" to DayOfWeek.SUNDAY,
                "sunday" to DayOfWeek.SUNDAY,
            )
        return mappings.firstOrNull { (token, _) -> label.contains(token) }?.second
    }

    private data class ParsedTimeRange(
        val start: LocalTime?,
        val end: LocalTime?,
        val durationMinutes: Long?,
    )

    private fun parseTimeRange(
        label: String,
    ): ParsedTimeRange {
        val times = parseTimes(label)
        return ParsedTimeRange(
            start = times.firstOrNull(),
            end = times.drop(1).firstOrNull(),
            durationMinutes = parseDurationMinutes(label),
        )
    }

    private fun parseTimes(
        label: String,
    ): List<LocalTime> {
        val matches = mutableListOf<Pair<Int, LocalTime>>()
        Regex("""(\d{1,2})[:](\d{1,2})\s*(am|pm)?""").findAll(label).forEach { match ->
            buildTime(
                hour = match.groupValues[1].toIntOrNull(),
                minute = match.groupValues[2].toIntOrNull(),
                label = label,
                suffix = match.groupValues.getOrNull(3).orEmpty(),
            )?.let { matches += match.range.first to it }
        }
        Regex("""(\d{1,2})\s*点\s*(半|(\d{1,2})\s*分?)?""").findAll(label).forEach { match ->
            val minute =
                when {
                    match.groupValues[2] == "半" -> 30
                    match.groupValues[3].isNotBlank() -> match.groupValues[3].toIntOrNull()
                    else -> 0
                }
            buildTime(
                hour = match.groupValues[1].toIntOrNull(),
                minute = minute,
                label = label,
                suffix = "",
            )?.let { matches += match.range.first to it }
        }
        Regex("""\b(\d{1,2})\s*(am|pm)\b""").findAll(label).forEach { match ->
            buildTime(
                hour = match.groupValues[1].toIntOrNull(),
                minute = 0,
                label = label,
                suffix = match.groupValues[2],
            )?.let { matches += match.range.first to it }
        }
        return matches.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun buildTime(
        hour: Int?,
        minute: Int?,
        label: String,
        suffix: String,
    ): LocalTime? {
        var resolvedHour = hour ?: return null
        val resolvedMinute = minute ?: return null
        if (resolvedHour !in 0..23 || resolvedMinute !in 0..59) return null
        val lowerSuffix = suffix.lowercase(Locale.US)
        if (lowerSuffix == "pm" && resolvedHour in 1..11) resolvedHour += 12
        if (lowerSuffix == "am" && resolvedHour == 12) resolvedHour = 0
        if (lowerSuffix.isBlank() && shouldTreatAsAfternoon(label, resolvedHour)) {
            resolvedHour += 12
        }
        return runCatching { LocalTime.of(resolvedHour, resolvedMinute) }.getOrNull()
    }

    private fun shouldTreatAsAfternoon(
        label: String,
        hour: Int,
    ): Boolean =
        hour in 1..11 &&
            (label.contains("下午") || label.contains("晚上") || label.contains("今晚") || label.contains("傍晚"))

    private fun parseDurationMinutes(
        label: String,
    ): Long? {
        Regex("""(\d{1,2})\s*(小时|hour|hours|h)""").find(label)?.let { match ->
            return (match.groupValues[1].toLongOrNull() ?: return null) * 60L
        }
        Regex("""(\d{1,3})\s*(分钟|分|min|mins|m)""").find(label)?.let { match ->
            return match.groupValues[1].toLongOrNull()
        }
        return null
    }

    private fun periodDefaultTime(
        label: String,
    ): LocalTime =
        when {
            label.contains("凌晨") -> LocalTime.of(6, 0)
            label.contains("早上") || label.contains("上午") || label.contains("morning") -> LocalTime.of(9, 0)
            label.contains("中午") || label.contains("noon") -> LocalTime.of(12, 0)
            label.contains("下午") || label.contains("afternoon") -> LocalTime.of(14, 0)
            label.contains("傍晚") -> LocalTime.of(18, 0)
            label.contains("晚上") || label.contains("今晚") || label.contains("evening") -> LocalTime.of(19, 0)
            else -> LocalTime.of(9, 0)
        }

    private fun hasPeriodHint(
        label: String,
    ): Boolean =
        listOf("凌晨", "早上", "上午", "中午", "下午", "傍晚", "晚上", "今晚", "morning", "noon", "afternoon", "evening")
            .any { label.contains(it) }

    private fun hasRelativeTodayHint(
        label: String,
    ): Boolean = label.contains("今天") || label.contains("今日") || label.contains("today") || label.contains("今晚")
}
