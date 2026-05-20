package com.lmx.xiaoxuanagent.assistantos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
class AssistantOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var expanded: Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        ensureOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ensureOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching {
                windowManager?.removeView(view)
            }
        }
        overlayView = null
        super.onDestroy()
    }

    private fun ensureOverlay() {
        if (!AssistantOsController.canDrawOverlays()) {
            stopSelf()
            return
        }
        val manager = windowManager ?: return
        val snapshot = AssistantOsController.snapshot()
        val presentation =
            AssistantOverlayPresentationFactory.build(
                snapshot = snapshot,
                productShell = AssistantProductShellStore.read(),
            )
        val existingView = overlayView
        if (existingView == null) {
            val view = buildOverlayView(presentation)
            overlayView = view
            manager.addView(view, createLayoutParams())
            return
        }
        existingView.findViewWithTag<TextView>("overlay_title")?.text = presentation.title
        existingView.findViewWithTag<TextView>("overlay_subtitle")?.text = presentation.subtitle
        existingView.findViewWithTag<TextView>("overlay_context")?.let { view ->
            view.text = presentation.contextLine
            view.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        existingView.findViewWithTag<LinearLayout>("overlay_chips")?.let { container ->
            container.visibility = if (expanded && presentation.statusChips.isNotEmpty()) View.VISIBLE else View.GONE
            renderStatusChips(container, presentation.statusChips)
        }
        existingView.findViewWithTag<LinearLayout>("overlay_glance_lines")?.let { container ->
            container.visibility = if (expanded && presentation.glanceLines.isNotEmpty()) View.VISIBLE else View.GONE
            renderGlanceLines(container, presentation.glanceLines)
        }
        existingView.findViewWithTag<LinearLayout>("overlay_actions")?.let { container ->
            renderActionButtons(container, presentation.actions)
        }
        existingView.findViewWithTag<LinearLayout>("overlay_secondary_actions")?.let { container ->
            container.visibility = if (expanded) View.VISIBLE else View.GONE
            renderActionButtons(container, presentation.secondaryActions)
        }
    }

    private fun buildOverlayView(
        presentation: AssistantOverlayPresentation,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD11243CL.toInt())
            setPadding(28, 22, 28, 22)
            elevation = 12f
            addView(
                TextView(context).apply {
                    tag = "overlay_title"
                    text = presentation.title
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setOnClickListener {
                        expanded = !expanded
                        ensureOverlay()
                    }
                },
            )
            addView(
                TextView(context).apply {
                    tag = "overlay_subtitle"
                    text = presentation.subtitle
                    setTextColor(0xFFE2E8F0.toInt())
                    textSize = 11f
                },
            )
            addView(
                TextView(context).apply {
                    tag = "overlay_context"
                    text = presentation.contextLine
                    setTextColor(0xFFB6C2CF.toInt())
                    textSize = 10f
                    setPadding(0, 6, 0, 0)
                    visibility = if (expanded) View.VISIBLE else View.GONE
                },
            )
            addView(
                LinearLayout(context).apply {
                    tag = "overlay_chips"
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 0)
                    visibility = if (expanded && presentation.statusChips.isNotEmpty()) View.VISIBLE else View.GONE
                    renderStatusChips(this, presentation.statusChips)
                },
            )
            addView(
                LinearLayout(context).apply {
                    tag = "overlay_glance_lines"
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 10, 0, 0)
                    visibility = if (expanded && presentation.glanceLines.isNotEmpty()) View.VISIBLE else View.GONE
                    renderGlanceLines(this, presentation.glanceLines)
                },
            )
            addView(
                LinearLayout(context).apply {
                    tag = "overlay_actions"
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 14, 0, 0)
                    renderActionButtons(this, presentation.actions)
                },
            )
            addView(
                LinearLayout(context).apply {
                    tag = "overlay_secondary_actions"
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 0)
                    visibility = if (expanded) View.VISIBLE else View.GONE
                    renderActionButtons(this, presentation.secondaryActions)
                },
            )
        }

    private fun renderStatusChips(
        container: LinearLayout,
        chips: List<String>,
    ) {
        container.removeAllViews()
        chips.forEachIndexed { index, chip ->
            container.addView(
                TextView(container.context).apply {
                    text = chip
                    textSize = 10f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(18, 8, 18, 8)
                    setBackgroundColor(0x3345A6FF)
                    if (index > 0) {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                marginStart = 8
                            }
                    }
                },
            )
        }
    }

    private fun renderGlanceLines(
        container: LinearLayout,
        lines: List<String>,
    ) {
        container.removeAllViews()
        lines.forEach { line ->
            container.addView(
                TextView(container.context).apply {
                    text = line
                    textSize = 10.5f
                    setTextColor(0xFFE2E8F0.toInt())
                    setPadding(0, 0, 0, 6)
                },
            )
        }
    }

    private fun renderActionButtons(
        container: LinearLayout,
        actions: List<AssistantOverlayAction>,
    ) {
        container.removeAllViews()
        actions.forEachIndexed { index, action ->
            container.addView(
                Button(container.context).apply {
                    text = action.label
                    textSize = 11f
                    isAllCaps = false
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(20, 10, 20, 10)
                    setOnClickListener {
                        AssistantOsController.recordEntry(
                            surface = AssistantEntrySurface.OVERLAY,
                            action = "overlay_${action.action}",
                            summary = "悬浮入口快捷动作 ${action.label}",
                        )
                        startActivity(
                            resolveOverlayIntent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    if (index > 0) {
                        val params =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                marginStart = 10
                            }
                        layoutParams = params
                    }
                },
            )
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 180
        }

    private fun resolveOverlayIntent(
        action: AssistantOverlayAction,
    ): Intent =
        if (action.page.isNotBlank()) {
            AssistantShellIntentRouter.createPageIntent(this, action.page, source = "overlay")
        } else {
            AssistantShellIntentRouter.createActionIntent(this, action.action, source = "overlay")
        }

    companion object {
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, AssistantOverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssistantOverlayService::class.java))
        }
    }
}
