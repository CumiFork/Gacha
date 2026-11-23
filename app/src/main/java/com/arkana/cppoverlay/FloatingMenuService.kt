package com.arkana.cppoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingMenuService : Service() {

    companion object {
        const val ACTION_STOP = "com.arkana.cppoverlay.action.STOP"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID = 1001
        private const val AIMLOCK_DIR = "/data/local/tmp/arkana-aimlock"
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var panelHeader: View
    private lateinit var tvTelemetry: TextView
    private lateinit var tvOutput: TextView
    private lateinit var swTelemetry: Switch
    private lateinit var etCommand: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var telemetryRunning = false

    private var espProcess: Process? = null
    private var espThread: Thread? = null
    private var espOverlayView: EspOverlayView? = null
    private var espOverlayAdded = false

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (!telemetryRunning) return
            try {
                tvTelemetry.text = NativeBridge.getNativeCpuLoad()
            } catch (e: Throwable) {
                tvTelemetry.text = "Telemetry error: ${e.message}"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startAsForeground()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        telemetryRunning = false
        handler.removeCallbacks(telemetryRunnable)
        stopEspLine()
        try {
            if (espOverlayAdded && espOverlayView != null) {
                windowManager.removeView(espOverlayView)
            }
        } catch (_: Throwable) {}
        espOverlayAdded = false
        try {
            if (::overlayView.isInitialized) windowManager.removeView(overlayView)
        } catch (_: Throwable) {}
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.overlay_channel_desc)
            nm.createNotificationChannel(ch)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CppRootOverlay")
            .setContentText("Floating overlay active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_floating, null)
        bubbleView = overlayView.findViewById(R.id.bubbleView)
        panelView = overlayView.findViewById(R.id.panelView)
        panelHeader = overlayView.findViewById(R.id.panelHeader)
        tvTelemetry = overlayView.findViewById(R.id.tvTelemetry)
        tvOutput = overlayView.findViewById(R.id.tvOutput)
        swTelemetry = overlayView.findViewById(R.id.swTelemetry)
        etCommand = overlayView.findViewById(R.id.etCommand)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(overlayView, params)
        setupInteractions(params)
    }

    private fun setupInteractions(params: WindowManager.LayoutParams) {
        overlayView.findViewById<TextView>(R.id.btnMinimize).setOnClickListener { showBubble(params) }
        overlayView.findViewById<TextView>(R.id.btnClose).setOnClickListener { stopSelf() }

        swTelemetry.setOnCheckedChangeListener { _, checked ->
            telemetryRunning = checked
            handler.removeCallbacks(telemetryRunnable)
            if (checked) {
                tvTelemetry.text = "Loading..."
                handler.post(telemetryRunnable)
            } else {
                tvTelemetry.text = "Telemetry off"
            }
        }

        overlayView.findViewById<Button>(R.id.btnSelinux).setOnClickListener { runRootCommand("getenforce") }
        overlayView.findViewById<Button>(R.id.btnDropCache).setOnClickListener {
            runRootCommand("sync; echo 3 > /proc/sys/vm/drop_caches; echo dropped")
        }
        overlayView.findViewById<Button>(R.id.btnRunCmd).setOnClickListener {
            val cmd = etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) runRootCommand(cmd)
        }

        overlayView.findViewById<Button>(R.id.btnInstallAimlock).setOnClickListener { installAimlockScripts() }
        overlayView.findViewById<Button>(R.id.btnStartAimlock).setOnClickListener { startAimlock() }
        overlayView.findViewById<Button>(R.id.btnStopAimlock).setOnClickListener { stopAimlock() }
        overlayView.findViewById<Button>(R.id.btnToggleHitbox).setOnClickListener { toggleAimlockSetting("hitbox_expand") }
        overlayView.findViewById<Button>(R.id.btnToggleNoRecoil).setOnClickListener { toggleAimlockSetting("no_recoil") }
        overlayView.findViewById<Button>(R.id.btnToggleSniperOnly).setOnClickListener { toggleAimlockSetting("sniper_only") }
        overlayView.findViewById<Button>(R.id.btnStartEsp).setOnClickListener { startEspLine() }
        overlayView.findViewById<Button>(R.id.btnStopEsp).setOnClickListener { stopEspLine() }

        makeDraggable(bubbleView, params, onTap = { showPanel(params) })
        makeDraggable(panelHeader, params, onTap = null)
    }

    private fun showBubble(params: WindowManager.LayoutParams) {
        panelView.visibility = View.GONE
        bubbleView.visibility = View.VISIBLE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(overlayView, params)
    }

    private fun showPanel(params: WindowManager.LayoutParams) {
        bubbleView.visibility = View.GONE
        panelView.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager.updateViewLayout(overlayView, params)
    }

    private fun makeDraggable(target: View, params: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var downX = 0
        var downY = 0
        var startX = 0
        var startY = 0
        var moved = false

        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    if (!moved && (abs(dx) > 8 || abs(dy) > 8)) moved = true
                    if (moved) {
                        val dm = resources.displayMetrics
                        val maxX = (dm.widthPixels - overlayView.width).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - overlayView.height).coerceAtLeast(0)
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun runRootCommand(command: String) {
        tvOutput.text = "Running..."
        executor.execute {
            val result = try {
                val out = Shell.cmd(command).exec()
                val raw = (out.out + out.err).joinToString("\n")
                val formatted = NativeBridge.formatCommandOutput(raw)
                if (out.isSuccess) formatted else "EXIT ${out.code}\n$formatted"
            } catch (e: Throwable) {
                "Error: ${e.message}"
            }
            handler.post { tvOutput.text = result }
        }
    }

    private fun installAimlockScripts() {
        tvOutput.text = "Installing aimlock scripts..."
        executor.execute {
            val msg = try {
                val tmpDir = File(cacheDir, "aimlock")
                if (!tmpDir.exists()) tmpDir.mkdirs()
                val assetNames = assets.list("aimlock") ?: emptyArray()
                for (name in assetNames) {
                    assets.open("aimlock/$name").use { input ->
                        FileOutputStream(File(tmpDir, name)).use { output -> input.copyTo(output) }
                    }
                }
                val localPath = tmpDir.absolutePath
                val cmd = """
                    mkdir -p $AIMLOCK_DIR
                    cp $localPath/* $AIMLOCK_DIR/
                    chmod 755 $AIMLOCK_DIR/*.sh $AIMLOCK_DIR/*.py 2>/dev/null || true
                    ls -1 $AIMLOCK_DIR
                """.trimIndent()
                val out = Shell.cmd(cmd).exec()
                val files = out.out.joinToString(", ")
                "Installed to $AIMLOCK_DIR\nFiles: $files"
            } catch (e: Throwable) {
                "Install failed: ${e.message}"
            }
            handler.post { tvOutput.text = msg }
        }
    }

    private fun startAimlock() {
        tvOutput.text = "Starting aimlock..."
        executor.execute {
            val msg = try {
                val cmd = """
                    export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${'$'}PATH
                    cd $AIMLOCK_DIR || exit 1
                    pkill -f ff_aim.py 2>/dev/null || true
                    nohup sh run_aim.sh > $AIMLOCK_DIR/aim.log 2>&1 &
                    sleep 2
                    tail -n 40 $AIMLOCK_DIR/aim.log
                """.trimIndent()
                val out = Shell.cmd(cmd).exec()
                val raw = (out.out + out.err).joinToString("\n")
                NativeBridge.formatCommandOutput(raw)
            } catch (e: Throwable) {
                "Start failed: ${e.message}"
            }
            handler.post { tvOutput.text = msg }
        }
    }

    private fun stopAimlock() {
        tvOutput.text = "Stopping aimlock..."
        executor.execute {
            val msg = try {
                val out = Shell.cmd("pkill -f ff_aim.py 2>/dev/null; pkill -f run_aim.sh 2>/dev/null; echo stopped").exec()
                (out.out + out.err).joinToString("\n").ifBlank { "stopped" }
            } catch (e: Throwable) {
                "Stop failed: ${e.message}"
            }
            handler.post { tvOutput.text = msg }
        }
    }

    private fun toggleAimlockSetting(key: String) {
        tvOutput.text = "Toggling $key..."
        executor.execute {
            val msg = try {
                // Toggle boolean in ff_rvas.json settings using python3.
                val py = (
                    "export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:\$PATH; " +
                    "python3 -c \"import json,os; p='$AIMLOCK_DIR/ff_rvas.json'; " +
                    "d=json.load(open(p)); s=d.setdefault('settings',{}); " +
                    "k='$key'; s[k]=not bool(s.get(k,False)); json.dump(d,open(p,'w'),indent=2); " +
                    "print(k+'='+str(s[k]).lower())\""
                )
                val out = Shell.cmd(py).exec()
                var raw = (out.out + out.err).joinToString("\n").trim()
                if (!out.isSuccess || raw.contains("missing:")) {
                    raw = "Toggle failed. Install scripts first. ${raw}"
                }
                raw
            } catch (e: Throwable) {
                "Toggle failed: ${e.message}"
            }
            handler.post { tvOutput.text = msg }
        }
    }

    private fun ensureEspOverlay() {
        if (espOverlayAdded && espOverlayView != null) return
        if (!::windowManager.isInitialized) windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = EspOverlayView(this)
        windowManager.addView(view, params)
        espOverlayView = view
        espOverlayAdded = true
    }

    private fun startEspLine() {
        if (espProcess != null) {
            tvOutput.text = "ESP sudah berjalan"
            return
        }
        tvOutput.text = "Menjalankan ESP line..."
        executor.execute {
            try {
                ensureEspOverlayOnMain()
                val metrics = resources.displayMetrics
                val sw = metrics.widthPixels
                val sh = metrics.heightPixels

                val nativeDir = applicationInfo.nativeLibraryDir
                val src = File(nativeDir, "libespline.so")
                if (!src.exists()) {
                    handler.post { tvOutput.text = "libespline.so tidak ditemukan di $nativeDir" }
                    return@execute
                }

                // Install binary
                Shell.cmd("mkdir -p $AIMLOCK_DIR", "cp ${src.absolutePath} $AIMLOCK_DIR/libespline.so", "chmod 755 $AIMLOCK_DIR/libespline.so").exec()

                // Install/update config from assets, preserving existing settings toggles
                try {
                    val tmpCfg = File(cacheDir, "ff_rvas.json")
                    assets.open("aimlock/ff_rvas.json").use { input ->
                        FileOutputStream(tmpCfg).use { output -> input.copyTo(output) }
                    }
                    val mergeCmd = """
                        export PATH=/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${'$'}PATH
                        python3 - <<'PYM'
import json, os
src='${tmpCfg.absolutePath}'
dst='$AIMLOCK_DIR/ff_rvas.json'
try:
    d=json.load(open(src))
    if os.path.exists(dst):
        try:
            old=json.load(open(dst))
            if isinstance(old.get('settings'), dict):
                d.setdefault('settings',{}).update(old['settings'])
        except: pass
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    json.dump(d, open(dst,'w'), indent=2)
    print('ok')
except Exception as e:
    print('err',e)
PYM
                        chmod 644 $AIMLOCK_DIR/ff_rvas.json 2>/dev/null || true
                    """.trimIndent()
                    val m = Shell.cmd(mergeCmd).exec()
                    if (m.out.none { it.contains("ok") }) {
                        // fallback: plain copy
                        Shell.cmd("cp ${tmpCfg.absolutePath} $AIMLOCK_DIR/ff_rvas.json", "chmod 644 $AIMLOCK_DIR/ff_rvas.json").exec()
                    }
                } catch (_: Throwable) {
                    Shell.cmd("test -f $AIMLOCK_DIR/ff_rvas.json || echo missing").exec()
                }

                // Stop stale esp
                Shell.cmd("pkill -f libespline.so 2>/dev/null || true").exec()
                Thread.sleep(200)

                val cmd = arrayOf("su", "-c", "cd $AIMLOCK_DIR && ./libespline.so $AIMLOCK_DIR/ff_rvas.json $sw $sh >> $AIMLOCK_DIR/esp.log 2>&1")
                // We need stdout for JSON frames, so don't redirect stdout. Redirect stderr only.
                val cmd2 = arrayOf("su", "-c", "cd $AIMLOCK_DIR && ./libespline.so $AIMLOCK_DIR/ff_rvas.json $sw $sh 2>>$AIMLOCK_DIR/esp.log")
                val proc = Runtime.getRuntime().exec(cmd2)
                espProcess = proc

                val readerThread = Thread {
                    var gotFrame = false
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                            var line: String?
                            while (br.readLine().also { line = it } != null) {
                                val l = line ?: continue
                                if (!l.startsWith("{")) continue
                                try {
                                    val obj = JSONObject(l)
                                    if (obj.optString("type") != "frame") continue
                                    if (!gotFrame) {
                                        gotFrame = true
                                        handler.post { tvOutput.text = "ESP aktif ($sw x $sh)" }
                                    }
                                    val arr = obj.optJSONArray("lines") ?: continue
                                    val parsed = ArrayList<EspOverlayView.Line>(arr.length())
                                    for (i in 0 until arr.length()) {
                                        val o = arr.getJSONObject(i)
                                        parsed.add(EspOverlayView.Line(
                                            o.optDouble("x1").toFloat(),
                                            o.optDouble("y1").toFloat(),
                                            o.optDouble("x2").toFloat(),
                                            o.optDouble("y2").toFloat()
                                        ))
                                    }
                                    handler.post { espOverlayView?.updateLines(parsed) }
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}
                    val exitCode = try { proc.waitFor() } catch (_: Throwable) { -1 }
                    handler.post {
                        espOverlayView?.clearLines()
                        tvOutput.text = if (gotFrame) "ESP line stopped" else "ESP berhenti (exit=$exitCode). Cek $AIMLOCK_DIR/esp.log"
                    }
                    espProcess = null
                }
                readerThread.isDaemon = true
                readerThread.start()
                espThread = readerThread
                handler.post { tvOutput.text = "ESP dimulai ($sw x $sh), menunggu frame..." }
            } catch (e: Throwable) {
                handler.post { tvOutput.text = "ESP start failed: ${e.message}" }
            }
        }
    }

    private fun ensureEspOverlayOnMain() {
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { ensureEspOverlay() } catch (_: Throwable) {}
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun stopEspLine() {
        try {
            espProcess?.destroy()
        } catch (_: Throwable) {}
        espProcess = null
        try {
            Shell.cmd("pkill -f libespline.so 2>/dev/null || true").exec()
        } catch (_: Throwable) {}
        handler.post {
            espOverlayView?.clearLines()
            tvOutput.text = "ESP line dimatikan"
        }
    }
}
