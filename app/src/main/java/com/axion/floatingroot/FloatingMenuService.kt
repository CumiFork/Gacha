package com.axion.floatingroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.axion.floatingroot.databinding.OverlayBubbleBinding
import com.axion.floatingroot.databinding.OverlayPanelBinding
import com.google.android.material.button.MaterialButton
import com.topjohnwu.superuser.Shell
import kotlin.math.abs
import kotlin.math.min

class FloatingMenuService : Service() {

    private data class RootAction(val label: String, val command: String)

    private val actions = listOf(
        RootAction("Drop RAM cache", "sync; echo 3 > /proc/sys/vm/drop_caches"),
        RootAction("SELinux status", "getenforce 2>/dev/null || echo 'getenforce unavailable'"),
        RootAction(
            "CPU and battery",
            "getprop ro.product.model; head -n 3 /proc/stat; dumpsys battery | head -n 12"
        )
    )

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleBinding: OverlayBubbleBinding
    private lateinit var panelBinding: OverlayPanelBinding
    private lateinit var bubbleView: View
    private lateinit var panelView: View

    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        measureScreen()

        val inflater = LayoutInflater.from(this)
        bubbleBinding = OverlayBubbleBinding.inflate(inflater)
        panelBinding = OverlayPanelBinding.inflate(inflater)
        bubbleView = bubbleBinding.root
        panelView = panelBinding.root

        buildActionButtons()
        attachDrag(bubbleBinding.bubbleRoot)
        attachDrag(panelBinding.panelHeader)

        bubbleBinding.bubbleRoot.setOnClickListener { showPanel() }
        panelBinding.btnMinimize.setOnClickListener { showBubble() }
        panelBinding.btnClose.setOnClickListener { stopSelf() }
        panelBinding.btnRun.setOnClickListener { runCustomCommand() }
        panelBinding.etCommand.setOnClickListener { setPanelFocused(true) }
        panelBinding.etCommand.setOnFocusChangeListener { _, hasFocus -> setPanelFocused(hasFocus) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is not granted", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        showBubble()
        checkRoot()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        currentView = null
        currentParams = null
        super.onDestroy()
    }

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun buildActionButtons() {
        panelBinding.actionsContainer.removeAllViews()
        actions.forEach { action ->
            val button = MaterialButton(this).apply {
                text = action.label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp(this@FloatingMenuService) }
                setOnClickListener { runCommand(action.label, action.command) }
            }
            panelBinding.actionsContainer.addView(button)
        }
    }

    private fun bubbleParams(): WindowManager.LayoutParams {
        val size = 64.dp(this)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - size - 16.dp(this@FloatingMenuService)).coerceAtLeast(0)
            y = (screenHeight * 0.25).toInt()
        }
    }

    private fun panelParams(): WindowManager.LayoutParams {
        val width = min(360.dp(this), (screenWidth - 24.dp(this)).coerceAtLeast(200.dp(this)))
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth - width) / 2).coerceAtLeast(0)
            y = (screenHeight * 0.18).toInt()
        }
    }

    private fun swapView(view: View, params: WindowManager.LayoutParams) {
        currentView?.let { old ->
            runCatching { windowManager.removeViewImmediate(old) }
        }
        currentView = view
        currentParams = params
        windowManager.addView(view, params)
    }

    private fun showBubble() {
        panelBinding.etCommand.clearFocus()
        swapView(bubbleView, bubbleParams())
    }

    private fun showPanel() {
        swapView(panelView, panelParams())
        checkRoot()
    }

    private fun setPanelFocused(focused: Boolean) {
        val params = currentParams ?: return
        val view = currentView ?: return
        if (focused) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun attachDrag(view: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { target, event ->
            val params = currentParams ?: return@setOnTouchListener false
            val attached = currentView ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) + abs(dy) > 8f) moved = true

                    val viewWidth = if (params.width > 0) params.width else attached.width
                    val viewHeight = if (params.height > 0) params.height else attached.height
                    val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                    val maxY = (screenHeight - viewHeight).coerceAtLeast(0)

                    params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                    params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(attached, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) target.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }
    }

    private fun checkRoot() {
        runCatching {
            Shell.getShell { shell ->
                panelBinding.tvServiceStatus.text = if (shell.isRoot) {
                    "Root shell ready"
                } else {
                    "No root shell, commands may fail"
                }
            }
        }.onFailure { error ->
            panelBinding.tvServiceStatus.text = "Shell check failed: ${error.message}"
        }
    }

    private fun runCustomCommand() {
        val command = panelBinding.etCommand.text.toString().trim()
        if (command.isEmpty()) {
            panelBinding.tvOutput.text = "Enter a shell command first."
            return
        }
        runCommand("custom", command)
    }

    private fun runCommand(label: String, command: String) {
        panelBinding.tvOutput.text = "$label\nrunning..."
        Shell.cmd(command).submit { result ->
            val output = buildString {
                append("[").append(label).append("] exit=").append(result.code).append('\n')
                if (result.out.isNotEmpty()) append(result.out.joinToString("\n"))
                if (result.err.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("stderr: ").append(result.err.joinToString("\n"))
                }
                if (isEmpty()) append("(no output)")
            }
            panelBinding.tvOutput.text = output
            RootLog.last = output
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "floating_root_control"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Floating root control",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Floating root menu")
            .setContentText("Overlay control is running")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}
