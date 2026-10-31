package com.ozil.omnivoice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Foreground service that shows the floating right-edge side panel (over every app),
 * the ongoing notification with Speak / Stop buttons, and runs speech recognition.
 */
class OverlayService : Service() {

    companion object {
        @Volatile
        var running = false

        const val ACTION_STOP = "com.ozil.omnivoice.STOP"
        const val ACTION_TOGGLE_MIC = "com.ozil.omnivoice.TOGGLE_MIC"
        private const val CHANNEL_ID = "omnivoice_service"
        private const val NOTIF_ID = 42
    }

    private class Palette(
        val panel: Int,
        val border: Int,
        val text: Int,
        val sub: Int,
        val accent: Int,
        val accentFg: Int,
        val subtle: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var rootView: View? = null
    private var winParams: WindowManager.LayoutParams? = null

    private var drawerOpen = true
    private var listening = false
    private var recognizer: SpeechRecognizer? = null

    private var micBtn: TextView? = null
    private var previewTv: TextView? = null
    private var dotView: View? = null
    private var lastPreview = "Tap the mic, then speak"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        winParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the keyboard + cursor in the app you are typing in.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (resources.displayMetrics.heightPixels * 0.30f).toInt()
        }
        buildUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_MIC -> toggleMic()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopListening()
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun actionPi(action: String, req: Int): PendingIntent {
        val i = Intent(this, OverlayService::class.java).setAction(action)
        return PendingIntent.getService(
            this, req, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(): Notification {
        val speak = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_mic),
            "Speak / Stop mic",
            actionPi(ACTION_TOGGLE_MIC, 1)
        ).build()
        val stop = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_mic),
            "Stop all",
            actionPi(ACTION_STOP, 2)
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("OmniVoice is active")
            .setContentText("Use Speak here, or the side panel on the screen edge")
            .setOngoing(true)
            .addAction(speak)
            .addAction(stop)
            .build()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OmniVoice service", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun palette(): Palette =
        if (Prefs.dark(this)) {
            Palette(
                panel = Color.parseColor("#F0161618"),
                border = Color.parseColor("#3DFFFFFF"),
                text = Color.parseColor("#F5F5F7"),
                sub = Color.parseColor("#A1A1A6"),
                accent = Color.parseColor("#F5F5F7"),
                accentFg = Color.BLACK,
                subtle = Color.parseColor("#2C2C2E")
            )
        } else {
            Palette(
                panel = Color.parseColor("#F5FFFFFF"),
                border = Color.parseColor("#2E000000"),
                text = Color.parseColor("#1D1D1F"),
                sub = Color.parseColor("#6E6E73"),
                accent = Color.parseColor("#1D1D1F"),
                accentFg = Color.WHITE,
                subtle = Color.parseColor("#EDEDF0")
            )
        }

    private fun lp(top: Int = 8): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun pill(
        label: String,
        bg: Int,
        fg: Int,
        sizeSp: Float = 12f,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(fg)
        textSize = sizeSp
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(16).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun rebuildUi() {
        rootView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        buildUi()
    }

    private fun buildUi() {
        val c = palette()
        val r = dp(24).toFloat()
        // order: top-left, top-right, bottom-right, bottom-left (x,y each)
        val corners = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)

        fun panelBg() = GradientDrawable().apply {
            setColor(c.panel)
            cornerRadii = corners
            setStroke(dp(1), c.border)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // ---- drawer
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = panelBg()
            visibility = if (drawerOpen) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(this).apply {
            text = "OmniVoice"
            setTextColor(c.text)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val modeLine = TextView(this).apply {
            text = "${Prefs.LANGS[Prefs.lang(this@OverlayService)].second} • " +
                Prefs.ENGINES[Prefs.engine(this@OverlayService)]
            setTextColor(c.sub)
            textSize = 11f
        }
        drawer.addView(title, lp(0))
        drawer.addView(modeLine, lp(2))

        val mic = pill("🎙  Tap & Speak", c.accent, c.accentFg, 13f) { toggleMic() }
        micBtn = mic
        drawer.addView(mic, lp(10))

        // ---- settings row: language / engine / theme
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowLp(end: Int) = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(end) }

        row.addView(
            pill(Prefs.LANGS[Prefs.lang(this)].first, c.subtle, c.text, 11f) {
                Prefs.setLang(this, (Prefs.lang(this) + 1) % Prefs.LANGS.size)
                afterSettingChange()
            }, rowLp(6)
        )
        row.addView(
            pill(Prefs.ENGINES[Prefs.engine(this)], c.subtle, c.text, 11f) {
                Prefs.setEngine(this, (Prefs.engine(this) + 1) % Prefs.ENGINES.size)
                afterSettingChange()
            }, rowLp(6)
        )
        row.addView(
            pill(if (Prefs.dark(this)) "☾ Black" else "☀ White", c.subtle, c.text, 11f) {
                Prefs.setDark(this, !Prefs.dark(this))
                afterSettingChange()
            }, rowLp(0)
        )
        drawer.addView(row, lp(8))

        val preview = TextView(this).apply {
            text = lastPreview
            setTextColor(c.sub)
            textSize = 11f
            maxLines = 2
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(c.subtle)
                cornerRadius = dp(12).toFloat()
            }
        }
        previewTv = preview
        drawer.addView(preview, lp(8))

        val close = pill("⏹  Close all", Color.parseColor("#1AFF3B30"), Color.parseColor("#FF3B30"), 11f) {
            stopSelf()
        }
        drawer.addView(close, lp(8))

        // ---- edge handle (tap = open/close, drag = move up/down)
        val handle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(9), dp(16), dp(9), dp(16))
            background = panelBg()
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34C759"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        }
        dotView = dot
        handle.addView(dot)
        handle.addView(TextView(this).apply {
            text = "🎙"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(2))
        })
        handle.addView(TextView(this).apply {
            text = "Voice"
            textSize = 9f
            setTextColor(c.text)
            typeface = Typeface.DEFAULT_BOLD
        })

        handle.setOnTouchListener(object : View.OnTouchListener {
            var startRawY = 0f
            var startParamY = 0
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = winParams ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = e.rawY
                        startParamY = p.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - startRawY
                        if (abs(dy) > dp(6)) moved = true
                        if (moved) {
                            p.y = (startParamY + dy).toInt().coerceAtLeast(0)
                            rootView?.let {
                                try {
                                    wm.updateViewLayout(it, p)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            drawerOpen = !drawerOpen
                            drawer.visibility = if (drawerOpen) View.VISIBLE else View.GONE
                        }
                        return true
                    }
                }
                return false
            }
        })

        container.addView(drawer)
        container.addView(handle)

        rootView = container
        wm.addView(container, winParams!!)
        refreshListeningUi()
    }

    private fun afterSettingChange() {
        val wasListening = listening
        if (wasListening) stopListening()
        rebuildUi()
        if (wasListening) startListening()
    }

    private fun setPreview(text: String) {
        lastPreview = text
        previewTv?.text = text
    }

    private fun refreshListeningUi() {
        val c = palette()
        (dotView?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (listening) "#FF3B30" else "#34C759")
        )
        micBtn?.let {
            it.text = if (listening) "⏹  Listening… tap to stop" else "🎙  Tap & Speak"
            (it.background as? GradientDrawable)?.setColor(
                if (listening) Color.parseColor("#FF3B30") else c.accent
            )
            it.setTextColor(if (listening) Color.WHITE else c.accentFg)
        }
    }

    // ---------------------------------------------------------------- speech

    private fun toggleMic() {
        if (listening) stopListening() else startListening()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setPreview("Mic permission missing — open the app icon once")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setPreview("No speech recognition service on this phone")
            Toast.makeText(this, "Install / enable Google voice typing", Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        refreshListeningUi()
        createAndStart()
    }

    private fun stopListening() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.stopListening()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
        refreshListeningUi()
    }

    private fun createAndStart() {
        if (!listening) return
        recognizer?.let {
            try {
                it.destroy()
            } catch (_: Exception) {
            }
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr.setRecognitionListener(listener)
        recognizer = sr

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.LANGS[Prefs.lang(this@OverlayService)].first)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // "Offline" mode asks the phone to use on-device recognition when it is installed.
            if (Prefs.engine(this@OverlayService) == 1) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            sr.startListening(i)
        } catch (e: Exception) {
            setPreview("Could not start mic: ${e.message}")
            restartSoon(1200)
        }
    }

    private fun restartSoon(delayMs: Long) {
        handler.postDelayed({ if (listening) createAndStart() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setPreview("Listening… speak now")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!t.isNullOrBlank()) setPreview("Hearing: $t")
        }

        override fun onResults(results: Bundle?) {
            val best = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!best.isNullOrBlank()) {
                deliver(VoiceText.format(best, Prefs.punctuation(this@OverlayService)))
            }
            if (listening) restartSoon(250)
        }

        override fun onError(error: Int) {
            if (!listening) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setPreview("Mic permission missing")
                    stopListening()
                }
                12, 13 -> { // language not supported / unavailable
                    setPreview("This language is not available offline — switch engine or language")
                    stopListening()
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(150)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon(1000)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    setPreview("No internet — try the Offline engine")
                    restartSoon(2000)
                }
                else -> restartSoon(700)
            }
        }
    }

    // ---------------------------------------------------------------- output

    private fun deliver(text: String) {
        if (text.isEmpty()) return
        val typed = TypeAccessibilityService.instance?.insert(text) ?: false
        if (typed) {
            setPreview("Typed: $text")
        } else {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OmniVoice", text))
            setPreview("Copied (tap a text box first, or enable accessibility): $text")
        }
    }
}
