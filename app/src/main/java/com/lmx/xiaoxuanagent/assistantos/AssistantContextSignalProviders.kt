package com.lmx.xiaoxuanagent.assistantos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.lmx.xiaoxuanagent.runtime.AppForegroundTracker
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import java.util.Locale
import kotlin.math.floor

object AssistantContextSignalProviders {
    private const val CALENDAR_PROVIDER_ID = "calendar_agenda"
    private const val LOCATION_PROVIDER_ID = "passive_location"
    private const val CONTACT_PROVIDER_ID = "contact_directory"
    private const val SMS_PROVIDER_ID = "sms_inbox"
    private const val CALL_PROVIDER_ID = "call_log"
    private const val CALENDAR_LOOKAHEAD_MS = 12L * 60L * 60L * 1000L
    private const val LOCATION_ZONE_SCALE = 0.02
    private const val LOCATION_SIGNIFICANT_MOVE_METERS = 800f
    private const val LOCATION_MAJOR_MOVE_METERS = 5000f
    private const val PROVIDER_PROBE_COOLDOWN_MS = 5L * 60L * 1000L
    private const val CALENDAR_POLL_INTERVAL_MS = 5L * 60L * 1000L
    private const val LOCATION_POLL_INTERVAL_MS = 2L * 60L * 1000L
    private const val CONTACT_POLL_INTERVAL_MS = 15L * 60L * 1000L
    private const val SMS_POLL_INTERVAL_MS = 60L * 1000L
    private const val CALL_POLL_INTERVAL_MS = 60L * 1000L

    @Volatile
    private var lastCalendarSignature: String = ""

    @Volatile
    private var lastLocationSignature: String = ""

    @Volatile
    private var lastContactSignature: String = ""

    @Volatile
    private var lastSmsSignature: String = ""

    @Volatile
    private var lastCallSignature: String = ""
    @Volatile
    private var lastCalendarPollAtMs: Long = 0L
    @Volatile
    private var lastLocationPollAtMs: Long = 0L
    @Volatile
    private var lastContactPollAtMs: Long = 0L
    @Volatile
    private var lastSmsPollAtMs: Long = 0L
    @Volatile
    private var lastCallPollAtMs: Long = 0L

    @Volatile
    private var calendarObserverRegistered = false

    @Volatile
    private var lastLocationSample: LocationSignalSample? = null

    private val calendarObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(
                selfChange: Boolean,
            ) {
                AppRuntimeContext.get()?.let { context ->
                    pollCalendar(context, reason = "calendar_observer")
                }
            }
        }

    fun ensureRegistered(
        context: Context,
    ) {
        AssistantSignalProviderStore.markRegistered(
            providerId = CALENDAR_PROVIDER_ID,
            source = CALENDAR_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "calendar",
            supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
            supportedSignalTypes = listOf(AssistantExternalSignalType.CALENDAR),
            routingTags = listOf("calendar", "agenda", "context"),
            preferredEntrySource = "external_signal:calendar",
            deliveryMode = "event+poll",
            routingPriority = 55,
        )
        AssistantSignalProviderStore.markRegistered(
            providerId = LOCATION_PROVIDER_ID,
            source = LOCATION_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "location",
            supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
            supportedSignalTypes = listOf(AssistantExternalSignalType.LOCATION),
            routingTags = listOf("location", "context"),
            preferredEntrySource = "external_signal:location",
            deliveryMode = "poll+transition",
            routingPriority = 50,
        )
        AssistantSignalProviderStore.markRegistered(
            providerId = CONTACT_PROVIDER_ID,
            source = CONTACT_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "contacts",
            supportedCapabilities = listOf(SessionCapabilityKey.REFRESH_ASSISTANT_OS),
            supportedSignalTypes = listOf(AssistantExternalSignalType.CONTACT),
            routingTags = listOf("contact", "relationship", "memory"),
            preferredEntrySource = "external_signal:contact",
            deliveryMode = "poll",
            routingPriority = 36,
        )
        AssistantSignalProviderStore.markRegistered(
            providerId = SMS_PROVIDER_ID,
            source = SMS_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "sms",
            supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
            supportedSignalTypes = listOf(AssistantExternalSignalType.MESSAGE),
            routingTags = listOf("sms", "message", "follow_up"),
            preferredEntrySource = "external_signal:sms",
            deliveryMode = "poll",
            routingPriority = 62,
        )
        AssistantSignalProviderStore.markRegistered(
            providerId = CALL_PROVIDER_ID,
            source = CALL_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "call_log",
            supportedCapabilities = listOf(SessionCapabilityKey.START_SESSION),
            supportedSignalTypes = listOf(AssistantExternalSignalType.CALL_LOG),
            routingTags = listOf("call", "follow_up", "relationship"),
            preferredEntrySource = "external_signal:call_log",
            deliveryMode = "poll",
            routingPriority = 60,
        )
        ensureRuntimeBindings(context.applicationContext)
    }

    fun poll(
        context: Context,
        reason: String,
    ) {
        if (!AppForegroundTracker.isAppInForeground()) {
            return
        }
        pollCalendar(context, reason)
        pollLocation(context, reason)
        pollContacts(context, reason)
        pollSms(context, reason)
        pollCallLog(context, reason)
    }

    private fun pollCalendar(
        context: Context,
        reason: String,
    ) {
        if (!isInteractivePollingAllowed()) {
            return
        }
        if (shouldSkipPoll(lastCalendarPollAtMs, CALENDAR_POLL_INTERVAL_MS, reason, bypassReason = "calendar_observer")) {
            return
        }
        lastCalendarPollAtMs = System.currentTimeMillis()
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALENDAR_PROVIDER_ID,
                success = false,
                reason = "calendar_permission_missing",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = CALENDAR_PROVIDER_ID,
                source = CALENDAR_PROVIDER_ID,
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        val now = System.currentTimeMillis()
        val uri =
            CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
                ContentUris.appendId(builder, now)
                ContentUris.appendId(builder, now + CALENDAR_LOOKAHEAD_MS)
            }.build()
        val projection =
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
        val eventsResult =
            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < 3) {
                            add(
                                CalendarSignalEvent(
                                    eventId = cursor.getLong(0),
                                    title = cursor.getString(1).orEmpty(),
                                    beginAtMs = cursor.getLong(2),
                                    endAtMs = cursor.getLong(3),
                                    location = cursor.getString(4).orEmpty(),
                                    calendarName = cursor.getString(5).orEmpty(),
                                ),
                            )
                        }
                    }
                }
            }
        val events = eventsResult.getOrNull().orEmpty()
        if (eventsResult.isFailure) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALENDAR_PROVIDER_ID,
                success = false,
                reason = "calendar_query_failed",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        if (events.isEmpty()) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALENDAR_PROVIDER_ID,
                success = true,
                reason = "calendar_idle",
            )
            return
        }
        val event = events.firstOrNull() ?: return
        val signature = "${event.eventId}:${event.beginAtMs}"
        if (signature == lastCalendarSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALENDAR_PROVIDER_ID,
                success = true,
                reason = "calendar_unchanged",
            )
            return
        }
        lastCalendarSignature = signature
        val summary =
            buildString {
                append(event.title.ifBlank { "即将到来的日程" })
                event.location.takeIf { it.isNotBlank() }?.let { append(" @ ").append(it.take(24)) }
                if (events.size > 1) append(" +").append(events.size - 1).append(" 项")
            }.take(96)
        val query =
            buildString {
                append(event.title.ifBlank { "日程提醒" })
                append("，开始时间=").append(event.beginAtMs)
                event.location.takeIf { it.isNotBlank() }?.let { append("，地点=").append(it) }
                event.calendarName.takeIf { it.isNotBlank() }?.let { append("，日历=").append(it) }
                if (events.size > 1) {
                    append("，后续日程=")
                    append(events.drop(1).joinToString(" | ") { candidate -> candidate.title.ifBlank { "日程" } })
                }
            }.take(240)
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.CALENDAR,
            capability = SessionCapabilityKey.START_SESSION,
            title = event.title.ifBlank { "日程提醒" },
            summary = summary,
            task = "跟进即将到来的日程：${event.title.ifBlank { "日程提醒" }.take(48)}",
            query = query,
            source = CALENDAR_PROVIDER_ID,
            signalKey = signature,
            payload =
                mapOf(
                    "event_id" to event.eventId.toString(),
                    "begin_at_ms" to event.beginAtMs.toString(),
                    "end_at_ms" to event.endAtMs.toString(),
                    "event_location" to event.location,
                    "calendar_name" to event.calendarName,
                    "agenda_count" to events.size.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = CALENDAR_PROVIDER_ID,
            source = CALENDAR_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = summary,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = CALENDAR_PROVIDER_ID,
            success = true,
            reason = "calendar_signal_observed",
        )
    }

    private fun pollLocation(
        context: Context,
        reason: String,
    ) {
        if (!isInteractivePollingAllowed()) {
            return
        }
        if (shouldSkipPoll(lastLocationPollAtMs, LOCATION_POLL_INTERVAL_MS, reason)) {
            return
        }
        lastLocationPollAtMs = System.currentTimeMillis()
        val hasFine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = LOCATION_PROVIDER_ID,
                success = false,
                reason = "location_permission_missing",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = LOCATION_PROVIDER_ID,
                source = LOCATION_PROVIDER_ID,
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val locationResult =
            runCatching {
                manager.getProviders(true)
                    .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                    .maxWithOrNull(compareBy<Location> { it.time }.thenBy { -it.accuracy })
            }
        val bestLocation = locationResult.getOrNull()
        if (locationResult.isFailure || bestLocation == null) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = LOCATION_PROVIDER_ID,
                success = false,
                reason = "location_unavailable",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        val locationAgeMinutes = ((System.currentTimeMillis() - bestLocation.time).coerceAtLeast(0L) / 60_000L).toInt()
        if (locationAgeMinutes >= 90) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = LOCATION_PROVIDER_ID,
                success = false,
                reason = "location_stale",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        val roundedLatitude = roundCoordinate(bestLocation.latitude)
        val roundedLongitude = roundCoordinate(bestLocation.longitude)
        val zoneKey = zoneKey(bestLocation.latitude, bestLocation.longitude)
        val sample =
            LocationSignalSample(
                provider = bestLocation.provider.orEmpty(),
                latitude = roundedLatitude,
                longitude = roundedLongitude,
                zoneKey = zoneKey,
                accuracyMeters = bestLocation.accuracy,
                timeMs = bestLocation.time,
            )
        val previousSample = lastLocationSample
        val movementMeters = previousSample?.let { distanceBetweenMeters(it, bestLocation) } ?: 0f
        val transition = deriveTransition(previousSample, sample, movementMeters)
        val signature = "${sample.provider}:${sample.zoneKey}:${transition.id}"
        if (signature == lastLocationSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = LOCATION_PROVIDER_ID,
                success = true,
                reason = "location_unchanged",
            )
            return
        }
        lastLocationSignature = signature
        lastLocationSample = sample
        val summary = "${transition.title} ${sample.zoneKey} | ${roundedLatitude}, ${roundedLongitude}"
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.LOCATION,
            capability = SessionCapabilityKey.START_SESSION,
            title = transition.title,
            summary = summary,
            task = "根据当前位置变化继续跟进：${transition.title} ${sample.zoneKey}",
            query = "当前位置 ${roundedLatitude}, ${roundedLongitude}，transition=${transition.id}",
            source = LOCATION_PROVIDER_ID,
            signalKey = signature,
            payload =
                mapOf(
                    "latitude" to roundedLatitude,
                    "longitude" to roundedLongitude,
                    "zone_key" to zoneKey,
                    "transition" to transition.id,
                    "movement_m" to movementMeters.toInt().toString(),
                    "provider" to bestLocation.provider.orEmpty(),
                    "accuracy_m" to bestLocation.accuracy.toInt().toString(),
                    "location_time_ms" to bestLocation.time.toString(),
                    "location_age_min" to locationAgeMinutes.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = LOCATION_PROVIDER_ID,
            source = LOCATION_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = summary,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = LOCATION_PROVIDER_ID,
            success = true,
            reason = "location_${transition.id}",
        )
    }

    private fun pollContacts(
        context: Context,
        reason: String,
    ) {
        if (!isInteractivePollingAllowed()) {
            return
        }
        if (shouldSkipPoll(lastContactPollAtMs, CONTACT_POLL_INTERVAL_MS, reason)) {
            return
        }
        lastContactPollAtMs = System.currentTimeMillis()
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CONTACT_PROVIDER_ID,
                success = false,
                reason = "contact_permission_missing",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = CONTACT_PROVIDER_ID,
                source = CONTACT_PROVIDER_ID,
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        val queryResult =
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER,
                        ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                    ),
                    "${ContactsContract.Contacts.HAS_PHONE_NUMBER} > 0",
                    null,
                    "${ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP} DESC",
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val contactId = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val updatedAtMs = cursor.getLong(3)
                    val phonePreview =
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId.toString()),
                            null,
                        )?.use { phoneCursor ->
                            if (!phoneCursor.moveToFirst()) null else phoneCursor.getString(0).orEmpty()
                        }.orEmpty()
                    ContactSignalEvent(contactId = contactId, name = name, phonePreview = phonePreview, updatedAtMs = updatedAtMs)
                }
            }
        val contact = queryResult.getOrNull()
        if (queryResult.isFailure || contact == null) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CONTACT_PROVIDER_ID,
                success = false,
                reason = "contact_unavailable",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        val signature = "${contact.contactId}:${contact.updatedAtMs}"
        if (signature == lastContactSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CONTACT_PROVIDER_ID,
                success = true,
                reason = "contact_unchanged",
            )
            return
        }
        lastContactSignature = signature
        val summary =
            buildString {
                append(contact.name.ifBlank { "联系人" })
                contact.phonePreview.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.takeLast(4).padStart(it.length.coerceAtMost(4), '*')) }
                append(" | 最近更新")
            }
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.CONTACT,
            capability = SessionCapabilityKey.REFRESH_ASSISTANT_OS,
            title = contact.name.ifBlank { "联系人更新" },
            summary = summary.take(96),
            query = "联系人=${contact.name} 电话=${contact.phonePreview}",
            source = CONTACT_PROVIDER_ID,
            signalKey = signature,
            payload =
                mapOf(
                    "contact_id" to contact.contactId.toString(),
                    "contact_name" to contact.name,
                    "contact_phone" to contact.phonePreview,
                    "updated_at_ms" to contact.updatedAtMs.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = CONTACT_PROVIDER_ID,
            source = CONTACT_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = contact.name,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = CONTACT_PROVIDER_ID,
            success = true,
            reason = "contact_signal_observed",
        )
    }

    private fun pollSms(
        context: Context,
        reason: String,
    ) {
        if (!isInteractivePollingAllowed()) {
            return
        }
        if (shouldSkipPoll(lastSmsPollAtMs, SMS_POLL_INTERVAL_MS, reason)) {
            return
        }
        lastSmsPollAtMs = System.currentTimeMillis()
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = SMS_PROVIDER_ID,
                success = false,
                reason = "sms_permission_missing",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = SMS_PROVIDER_ID,
                source = SMS_PROVIDER_ID,
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        val queryResult =
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.THREAD_ID,
                    ),
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    SmsSignalEvent(
                        address = cursor.getString(0).orEmpty(),
                        body = cursor.getString(1).orEmpty(),
                        dateMs = cursor.getLong(2),
                        threadId = cursor.getLong(3),
                    )
                }
            }
        val sms = queryResult.getOrNull()
        if (queryResult.isFailure || sms == null) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = SMS_PROVIDER_ID,
                success = false,
                reason = "sms_unavailable",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        val signature = "${sms.address}:${sms.dateMs}"
        if (signature == lastSmsSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = SMS_PROVIDER_ID,
                success = true,
                reason = "sms_unchanged",
            )
            return
        }
        lastSmsSignature = signature
        val sender = sms.address.ifBlank { "未知号码" }
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.MESSAGE,
            capability = SessionCapabilityKey.START_SESSION,
            title = "短信来信",
            summary = "$sender | ${sms.body.replace('\n', ' ').take(72)}",
            task = "查看并决定是否需要跟进来自 $sender 的短信",
            query = "短信发送方=$sender，内容=${sms.body.take(160)}",
            source = SMS_PROVIDER_ID,
            signalKey = signature,
            payload =
                mapOf(
                    "sender" to sender,
                    "body_preview" to sms.body.take(160),
                    "thread_id" to sms.threadId.toString(),
                    "message_date_ms" to sms.dateMs.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = SMS_PROVIDER_ID,
            source = SMS_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = sender,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = SMS_PROVIDER_ID,
            success = true,
            reason = "sms_signal_observed",
        )
    }

    private fun pollCallLog(
        context: Context,
        reason: String,
    ) {
        if (!isInteractivePollingAllowed()) {
            return
        }
        if (shouldSkipPoll(lastCallPollAtMs, CALL_POLL_INTERVAL_MS, reason)) {
            return
        }
        lastCallPollAtMs = System.currentTimeMillis()
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALL_PROVIDER_ID,
                success = false,
                reason = "call_log_permission_missing",
            )
            return
        }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = CALL_PROVIDER_ID,
                source = CALL_PROVIDER_ID,
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) return
        val queryResult =
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.DATE,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    CallSignalEvent(
                        name = cursor.getString(0).orEmpty(),
                        number = cursor.getString(1).orEmpty(),
                        dateMs = cursor.getLong(2),
                        type = cursor.getInt(3),
                        durationSeconds = cursor.getLong(4),
                    )
                }
            }
        val call = queryResult.getOrNull()
        if (queryResult.isFailure || call == null) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALL_PROVIDER_ID,
                success = false,
                reason = "call_log_unavailable",
                cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
            )
            return
        }
        val signature = "${call.number}:${call.dateMs}:${call.type}"
        if (signature == lastCallSignature) {
            AssistantSignalProviderStore.markProbeResult(
                providerId = CALL_PROVIDER_ID,
                success = true,
                reason = "call_log_unchanged",
            )
            return
        }
        lastCallSignature = signature
        val caller = call.name.ifBlank { call.number.ifBlank { "未知来电" } }
        val callType =
            when (call.type) {
                CallLog.Calls.INCOMING_TYPE -> "来电"
                CallLog.Calls.OUTGOING_TYPE -> "去电"
                CallLog.Calls.MISSED_TYPE -> "未接来电"
                else -> "通话"
            }
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.CALL_LOG,
            capability = SessionCapabilityKey.START_SESSION,
            title = callType,
            summary = "$caller | ${call.durationSeconds}s",
            task = "检查最近与 $caller 的通话并决定是否需要跟进",
            query = "通话对象=$caller，号码=${call.number}，时长=${call.durationSeconds}s",
            source = CALL_PROVIDER_ID,
            signalKey = signature,
            payload =
                mapOf(
                    "caller_name" to call.name,
                    "caller_number" to call.number,
                    "call_date_ms" to call.dateMs.toString(),
                    "call_type" to callType,
                    "duration_seconds" to call.durationSeconds.toString(),
                    "poll_reason" to reason,
                ),
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = CALL_PROVIDER_ID,
            source = CALL_PROVIDER_ID,
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = caller,
        )
        AssistantSignalProviderStore.markProbeResult(
            providerId = CALL_PROVIDER_ID,
            success = true,
            reason = "call_signal_observed",
        )
    }

    private fun ensureRuntimeBindings(
        context: Context,
    ) {
        if (calendarObserverRegistered) return
        synchronized(this) {
            if (calendarObserverRegistered) return
            runCatching {
                context.contentResolver.registerContentObserver(
                    CalendarContract.Events.CONTENT_URI,
                    true,
                    calendarObserver,
                )
            }.onSuccess {
                calendarObserverRegistered = true
                AssistantSignalProviderStore.markProbeResult(
                    providerId = CALENDAR_PROVIDER_ID,
                    success = true,
                    reason = "calendar_observer_registered",
                )
            }.onFailure {
                AssistantSignalProviderStore.markProbeResult(
                    providerId = CALENDAR_PROVIDER_ID,
                    success = false,
                    reason = "calendar_observer_register_failed",
                    cooldownMs = PROVIDER_PROBE_COOLDOWN_MS,
                )
            }
        }
    }

    private fun hasPermission(
        context: Context,
        permission: String,
    ): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun shouldSkipPoll(
        lastPollAtMs: Long,
        intervalMs: Long,
        reason: String,
        bypassReason: String = "",
    ): Boolean {
        if (bypassReason.isNotBlank() && reason == bypassReason) {
            return false
        }
        return System.currentTimeMillis() - lastPollAtMs < intervalMs
    }

    private fun isInteractivePollingAllowed(): Boolean = AppForegroundTracker.isAppInForeground()

    private fun roundCoordinate(
        value: Double,
    ): String = String.format(Locale.US, "%.3f", value)

    private fun zoneKey(
        latitude: Double,
        longitude: Double,
    ): String =
        "${floor(latitude / LOCATION_ZONE_SCALE).toInt()}:${floor(longitude / LOCATION_ZONE_SCALE).toInt()}"

    private fun distanceBetweenMeters(
        previous: LocationSignalSample,
        current: Location,
    ): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            previous.latitude.toDouble(),
            previous.longitude.toDouble(),
            current.latitude,
            current.longitude,
            result,
        )
        return result.firstOrNull() ?: 0f
    }

    private fun deriveTransition(
        previous: LocationSignalSample?,
        current: LocationSignalSample,
        movementMeters: Float,
    ): LocationTransition {
        if (previous == null) {
            return LocationTransition(
                id = "initial_fix",
                title = "位置初始化",
            )
        }
        return when {
            current.zoneKey != previous.zoneKey && movementMeters >= LOCATION_MAJOR_MOVE_METERS ->
                LocationTransition(id = "major_move", title = "显著移动")

            current.zoneKey != previous.zoneKey && movementMeters >= LOCATION_SIGNIFICANT_MOVE_METERS ->
                LocationTransition(id = "arrived_zone", title = "到达新区域")

            current.zoneKey == previous.zoneKey && movementMeters >= LOCATION_SIGNIFICANT_MOVE_METERS ->
                LocationTransition(id = "departing_zone", title = "离开原区域边界")

            else ->
                LocationTransition(id = "refresh", title = "位置刷新")
        }
    }

    private data class CalendarSignalEvent(
        val eventId: Long,
        val title: String,
        val beginAtMs: Long,
        val endAtMs: Long,
        val location: String,
        val calendarName: String,
    )

    private data class LocationSignalSample(
        val provider: String,
        val latitude: String,
        val longitude: String,
        val zoneKey: String,
        val accuracyMeters: Float,
        val timeMs: Long,
    )

    private data class LocationTransition(
        val id: String,
        val title: String,
    )

    private data class ContactSignalEvent(
        val contactId: Long,
        val name: String,
        val phonePreview: String,
        val updatedAtMs: Long,
    )

    private data class SmsSignalEvent(
        val address: String,
        val body: String,
        val dateMs: Long,
        val threadId: Long,
    )

    private data class CallSignalEvent(
        val name: String,
        val number: String,
        val dateMs: Long,
        val type: Int,
        val durationSeconds: Long,
    )
}
