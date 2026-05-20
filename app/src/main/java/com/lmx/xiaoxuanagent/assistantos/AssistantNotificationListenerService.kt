package com.lmx.xiaoxuanagent.assistantos

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lmx.xiaoxuanagent.runtime.AppRuntimeContext
import com.lmx.xiaoxuanagent.runtime.SessionCapabilityKey
import com.lmx.xiaoxuanagent.taskprofile.TaskRegistry

class AssistantNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AppRuntimeContext.init(applicationContext)
        AssistantNotificationRuntimeStore.bind(this)
        AssistantSignalProviderStore.markRegistered(
            providerId = "notification_listener",
            source = "notification_listener",
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            providerType = "notification",
            supportedCapabilities =
                listOf(
                    SessionCapabilityKey.START_SESSION,
                    SessionCapabilityKey.RESUME_SESSION,
                    SessionCapabilityKey.REFRESH_ASSISTANT_OS,
                ),
            supportedSignalTypes = listOf(AssistantExternalSignalType.NOTIFICATION),
            routingTags = listOf("notification", "resume", "attention"),
            preferredEntrySource = "external_signal:notification",
            deliveryMode = "event",
            routingPriority = 90,
        )
    }

    override fun onListenerDisconnected() {
        AssistantNotificationRuntimeStore.unbind(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification?,
    ) {
        sbn ?: return
        AssistantNotificationRuntimeStore.recordPosted(sbn)
        val packageName = sbn.packageName.orEmpty()
        if (packageName.isBlank() || packageName == applicationContext.packageName) {
            return
        }
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text =
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                .ifBlank { extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty() }
        val knownProfile = TaskRegistry.allProfiles().firstOrNull { it.packageName == packageName }
        val capability =
            if (knownProfile != null) {
                SessionCapabilityKey.START_SESSION
            } else {
                SessionCapabilityKey.REFRESH_ASSISTANT_OS
            }
        val gate =
            AssistantSignalProviderStore.evaluateIngress(
                providerId = "notification_listener",
                source = "notification_listener",
                trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            )
        if (!gate.allows) {
            return
        }
        val displayName = knownProfile?.displayName ?: packageName.substringAfterLast('.')
        val task =
            if (capability == SessionCapabilityKey.START_SESSION) {
                listOf("处理来自 $displayName 的通知", title.takeIf { it.isNotBlank() }, text.takeIf { it.isNotBlank() })
                    .filterNotNull()
                    .joinToString("：")
            } else {
                ""
            }
        AssistantExternalSignalStore.recordProviderSignal(
            type = AssistantExternalSignalType.NOTIFICATION,
            capability = capability,
            title = "通知: $displayName",
            summary = listOf(title, text).filter { it.isNotBlank() }.joinToString(" | ").ifBlank { "收到来自 $displayName 的通知" },
            task = task,
            source = "notification_listener",
            signalKey = listOf(packageName, sbn.id.toString(), title, text).joinToString("|"),
            payload =
                buildMap {
                    put("package_name", packageName)
                    put("notification_id", sbn.id.toString())
                    put("post_time", sbn.postTime.toString())
                    title.takeIf { it.isNotBlank() }?.let { put("title", it) }
                    text.takeIf { it.isNotBlank() }?.let { put("text", it.take(240)) }
                },
        )
        AssistantSignalProviderStore.markSignalObserved(
            providerId = "notification_listener",
            source = "notification_listener",
            trustLevel = AssistantSignalProviderTrustLevel.HIGH,
            reason = displayName,
        )
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
    ) {
        sbn?.key?.let { AssistantNotificationRuntimeStore.recordRemoved(it) }
        super.onNotificationRemoved(sbn)
    }
}
