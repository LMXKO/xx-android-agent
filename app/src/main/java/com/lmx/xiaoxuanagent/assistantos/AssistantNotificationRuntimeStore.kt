package com.lmx.xiaoxuanagent.assistantos

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification

data class AssistantNotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val canReply: Boolean = false,
)

object AssistantNotificationRuntimeStore {
    private val lock = Any()
    private val active = LinkedHashMap<String, AssistantNotificationSnapshot>()
    @Volatile
    private var liveService: AssistantNotificationListenerService? = null

    fun bind(
        service: AssistantNotificationListenerService,
    ) {
        liveService = service
        synchronized(lock) {
            active.clear()
            service.activeNotifications.orEmpty().forEach { putSnapshot(it) }
        }
    }

    fun unbind(
        service: AssistantNotificationListenerService,
    ) {
        if (liveService === service) {
            liveService = null
        }
    }

    fun recordPosted(
        sbn: StatusBarNotification,
    ) {
        synchronized(lock) {
            putSnapshot(sbn)
        }
    }

    fun recordRemoved(
        key: String,
    ) {
        synchronized(lock) {
            active.remove(key)
        }
    }

    fun readActive(
        packageName: String = "",
        limit: Int = 6,
    ): List<AssistantNotificationSnapshot> =
        synchronized(lock) {
            active.values
                .asSequence()
                .filter { packageName.isBlank() || it.packageName == packageName }
                .sortedByDescending { it.postTime }
                .take(limit.coerceAtLeast(1))
                .toList()
        }

    fun findSnapshot(
        notificationKey: String,
    ): AssistantNotificationSnapshot? =
        synchronized(lock) {
            active[notificationKey]
        }

    fun findPackageName(
        notificationKey: String,
    ): String = findSnapshot(notificationKey)?.packageName.orEmpty()

    fun reply(
        notificationKey: String,
        replyText: String,
        actionHint: String = "",
    ): String {
        val service = liveService ?: return "通知监听器当前不可用。"
        val sbn =
            service.activeNotifications
                ?.firstOrNull { it.key == notificationKey }
                ?: return "未找到目标通知：${notificationKey.take(32)}"
        val action =
            sbn.notification.actions
                ?.firstOrNull { candidate ->
                    val title = candidate.title?.toString().orEmpty()
                    val supportsReply = !candidate.remoteInputs.isNullOrEmpty()
                    supportsReply && (actionHint.isBlank() || title.contains(actionHint, ignoreCase = true))
                }
                ?: sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                ?: return "该通知不支持直接回复。"
        val remoteInputs = action.remoteInputs ?: return "该通知缺少 RemoteInput。"
        val intent = Intent()
        val bundle = Bundle().apply {
            remoteInputs.forEach { input ->
                putCharSequence(input.resultKey, replyText)
            }
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        return runCatching {
            action.actionIntent.send(service, 0, intent)
            "已通过通知回复发送内容。"
        }.getOrElse { error ->
            "通知回复失败：${error.message.orEmpty()}".trim()
        }
    }

    private fun putSnapshot(
        sbn: StatusBarNotification,
    ) {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text =
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                .ifBlank { extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty() }
        active[sbn.key] =
            AssistantNotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName.orEmpty(),
                title = title,
                text = text,
                postTime = sbn.postTime,
                canReply = sbn.notification.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true,
            )
    }
}
