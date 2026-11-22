package com.axion.floatingroot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.axion.floatingroot.databinding.ActivityMainBinding
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(this, "Cannot open overlay settings", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRefresh.setOnClickListener { refreshRootStatus() }

        binding.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                binding.tvLog.text = "[app] overlay permission is not granted"
                return@setOnClickListener
            }
            runCatching {
                startForegroundService(Intent(this, FloatingMenuService::class.java))
                binding.tvLog.text = "[app] floating menu starting"
            }.onFailure { error ->
                binding.tvLog.text = "[app] start failed: ${error.message}"
            }
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, FloatingMenuService::class.java))
            binding.tvLog.text = "[app] floating menu stopped"
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        refreshRootStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshRootStatus()
        binding.tvLog.text = RootLog.last.ifBlank { "No root command output yet." }
    }

    private fun refreshRootStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (overlayGranted) {
            "Overlay permission granted"
        } else {
            "Overlay permission missing"
        }
        binding.btnGrantOverlay.isEnabled = !overlayGranted

        binding.tvRootStatus.text = "Checking shell..."
        runCatching {
            Shell.getShell { shell ->
                binding.tvRootStatus.text = if (shell.isRoot) "Root shell available" else "Root shell unavailable"
                Shell.cmd("command -v su", "uname -r", "getprop ro.build.version.release").submit { result ->
                    val detail = result.out.joinToString("\n").ifBlank { "(no output)" }
                    binding.tvRootDetail.text = detail
                    RootLog.last = detail
                    binding.tvLog.text = detail
                }
            }
        }.onFailure { error ->
            binding.tvRootStatus.text = "Root check failed"
            binding.tvRootDetail.text = error.message ?: "unknown error"
        }
    }
}
