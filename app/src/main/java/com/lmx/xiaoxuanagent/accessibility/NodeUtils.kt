package com.lmx.xiaoxuanagent.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

internal fun AccessibilityNodeInfo.allNodes(): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()

    fun traverse(node: AccessibilityNodeInfo?) {
        if (node == null) return
        result += node
        repeat(node.childCount) { index ->
            traverse(node.getChild(index))
        }
    }

    traverse(this)
    return result
}

internal fun AccessibilityNodeInfo.readableText(): String {
    return when {
        !text.isNullOrBlank() -> text.toString()
        !contentDescription.isNullOrBlank() -> contentDescription.toString()
        !hintText.isNullOrBlank() -> hintText.toString()
        else -> ""
    }
}

internal fun AccessibilityNodeInfo.accessibilityLabel(): String =
    when {
        !contentDescription.isNullOrBlank() -> contentDescription.toString()
        !text.isNullOrBlank() -> text.toString()
        !hintText.isNullOrBlank() -> hintText.toString()
        !stateDescription.isNullOrBlank() -> stateDescription.toString()
        else -> ""
    }

internal fun AccessibilityNodeInfo.accessibilityUniqueId(): String =
    runCatching { uniqueId.orEmpty() }.getOrDefault("")

internal fun AccessibilityNodeInfo.accessibilityPaneTitle(): String =
    runCatching { paneTitle?.toString().orEmpty() }.getOrDefault("")

internal fun AccessibilityNodeInfo.accessibilityContainerTitle(): String =
    runCatching { containerTitle?.toString().orEmpty() }.getOrDefault("")

internal fun AccessibilityNodeInfo.accessibilityStateDescription(): String =
    runCatching { stateDescription?.toString().orEmpty() }.getOrDefault("")

internal fun AccessibilityNodeInfo.collectionPositionSignature(): String =
    collectionItemInfo?.let { info ->
        buildString {
            append("r=").append(info.rowIndex)
            append(",c=").append(info.columnIndex)
            if (info.isHeading) append(",heading")
        }
    }.orEmpty()

internal fun AccessibilityNodeInfo.boundsRect(): Rect {
    val rect = Rect()
    getBoundsInScreen(rect)
    return rect
}

internal fun AccessibilityNodeInfo.setTextValue(value: String): Boolean {
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
    }
    return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}

internal fun AccessibilityNodeInfo.supportsAction(actionId: Int): Boolean =
    actionList.any { it.id == actionId }

internal fun AccessibilityNodeInfo.firstDescendantOrSelf(
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    if (predicate(this)) return this
    repeat(childCount) { index ->
        val child = getChild(index) ?: return@repeat
        val match = child.firstDescendantOrSelf(predicate)
        if (match != null) return match
    }
    return null
}
