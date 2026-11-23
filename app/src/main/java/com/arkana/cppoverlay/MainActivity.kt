package com.arkana.cppoverlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var tvRootStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNativeInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvNativeInfo = findViewById(R.id.tvNativeInfo)

        findViewById<Button>(R.id.btnRequestOverlay).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startFloatingService()
        }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopFloatingService()
        }

        loadNativeInfo()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadNativeInfo() {
        try {
            tvNativeInfo.text = NativeBridge.getNativeSystemInfo()
        } catch (e: Throwable) {
            tvNativeInfo.text = "Native error: ${e.message}"
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        tvOverlayStatus.text = "Overlay: ${if (overlayOk) "GRANTED" else "NOT GRANTED"}"
        tvServiceStatus.text = "Service: ${if (FloatingMenuService.isRunning) "running" else "stopped"}"

        tvRootStatus.text = "Root: checking..."
        executor.execute {
            val root = try {
                Shell.getShell().isRoot
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                tvRootStatus.text = "Root: ${if (root) "GRANTED" else "DENIED / unavailable"}"
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            refreshStatus()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startFloatingService() {
        if (!Settings.canDrawOverlays(this)) {
            tvServiceStatus.text = "Service: overlay permission required"
            requestOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingMenuService::class.java)
        ContextCompat.startForegroundService(this, intent)
        tvServiceStatus.postDelayed({ refreshStatus() }, 500)
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingMenuService::class.java).apply {
            action = FloatingMenuService.ACTION_STOP
        }
        startService(intent)
        tvServiceStatus.postDelayed({ refreshStatus() }, 300)
    }
}
