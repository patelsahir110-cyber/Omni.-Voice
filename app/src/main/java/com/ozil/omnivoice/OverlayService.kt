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
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import android.widget.ScrollView
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
    private var lastEvent = 0L
    private enum class Phase { IDLE, LISTENING, PROCESSING, DONE }
    private var phase = Phase.IDLE
    private val transcript = StringBuilder()
    private var lastPartial = ""
    private var roundActive = false
    private var startedAt = 0L
    private var level = 0f
    private var resultBox: View? = null
    private var resultTv: TextView? = null
    private var sentenceOpen = true // next chunk starts a new sentence -> capitalize
    private val wordQueue = java.util.ArrayDeque<String>()
    private val typeHandler = Handler(Looper.getMainLooper())
    private var warnedCopy = false
    private val mutedStreams = mutableListOf<Int>()

    private var micBtn: TextView? = null
    private var previewTv: TextView? = null
    private var dotView: View? = null
    private var lastPreview = "Tap Start, speak, then tap Finish"

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
        typeHandler.removeCallbacksAndMessages(null)
        wordQueue.clear()
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
            "Start / Finish",
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
            layoutParams = LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT)
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

        val mic = pill("🎙  Start", c.accent, c.accentFg, 14f) { toggleMic() }
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
            maxLines = 3
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(c.subtle)
                cornerRadius = dp(12).toFloat()
            }
        }
        previewTv = preview
        drawer.addView(preview, lp(8))

        // ---- result card: appears after Finish, with Copy / Insert / Clear
        val resultView = TextView(this).apply {
            setTextColor(c.text)
            textSize = 13f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        resultTv = resultView
        val scroll = MaxHeightScroll(this, dp(160)).apply {
            background = GradientDrawable().apply {
                setColor(c.subtle)
                cornerRadius = dp(12).toFloat()
            }
            addView(resultView)
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(pill("📋 Copy", c.accent, c.accentFg, 12f) {
            val t = transcript.toString().trim()
            if (t.isNotEmpty()) {
                copyToClipboard(t)
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
        }, rowLp(6))
        btnRow.addView(pill("⌨ Insert", c.subtle, c.text, 12f) {
            val t = transcript.toString().trim()
            if (t.isNotEmpty()) deliver(t)
        }, rowLp(6))
        btnRow.addView(pill("🗑 Clear", c.subtle, c.text, 12f) {
            transcript.setLength(0)
            setPhase(Phase.IDLE)
            setPreview("Ready. Tap Start.")
        }, rowLp(0))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(scroll, lp(0))
        box.addView(btnRow, lp(6))
        resultBox = box
        drawer.addView(box, lp(8))

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
        if (phase == Phase.LISTENING || phase == Phase.PROCESSING) {
            stopListening()
            setPreview("Setting changed. Tap Start to record again.")
        }
        rebuildUi()
    }

    private fun setPreview(text: String) {
        lastPreview = text
        previewTv?.text = text
    }

    private fun refreshListeningUi() {
        val c = palette()
        val dotColor = when (phase) {
            Phase.LISTENING -> "#FF3B30"
            Phase.PROCESSING -> "#FF9F0A"
            else -> "#34C759"
        }
        (dotView?.background as? GradientDrawable)?.setColor(Color.parseColor(dotColor))
        micBtn?.let {
            it.text = when (phase) {
                Phase.LISTENING -> "⏹  Finish"
                Phase.PROCESSING -> "⏳  Processing…"
                Phase.DONE -> "🎙  New recording"
                else -> "🎙  Start"
            }
            val bg = when (phase) {
                Phase.LISTENING -> Color.parseColor("#FF3B30")
                Phase.PROCESSING -> Color.parseColor("#FF9F0A")
                else -> c.accent
            }
            (it.background as? GradientDrawable)?.setColor(bg)
            it.setTextColor(if (phase == Phase.LISTENING || phase == Phase.PROCESSING) Color.WHITE else c.accentFg)
        }
        resultBox?.visibility = if (phase == Phase.DONE) View.VISIBLE else View.GONE
        resultTv?.text = transcript.toString().trim()
    }

    // ---------------------------------------------------------------- speech
    // Flow: Start -> (only a "listening" sign, no words shown) -> Finish -> Processing -> result card.

    private fun setPhase(p: Phase) {
        phase = p
        listening = p == Phase.LISTENING
        refreshListeningUi()
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun toggleMic() {
        when (phase) {
            Phase.LISTENING -> finishListening()
            Phase.PROCESSING -> {}
            else -> startListening()
        }
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
        transcript.setLength(0)
        lastPartial = ""
        sentenceOpen = true
        level = 0f
        startedAt = now()
        muteBeeps()
        setPhase(Phase.LISTENING)
        handler.removeCallbacks(tick)
        handler.post(tick)
        createAndStart(true)
    }

    // Tap Finish: stop the mic, wait for the last words, then show everything with a Copy button.
    private fun finishListening() {
        if (phase != Phase.LISTENING) return
        setPhase(Phase.PROCESSING)
        setPreview("⏳ Processing your speech…")
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(tick)
        if (roundActive) {
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
            handler.postDelayed(finishTimeout, 3000)
        } else {
            commitPartial()
            completeFinish()
        }
    }

    private val finishTimeout = Runnable {
        if (phase == Phase.PROCESSING) {
            commitPartial()
            completeFinish()
        }
    }

    private fun completeFinish() {
        handler.removeCallbacks(finishTimeout)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(tick)
        roundActive = false
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
        unmuteBeeps()
        val text = transcript.toString().trim()
        if (text.isEmpty()) {
            setPhase(Phase.IDLE)
            setPreview("Nothing was heard. Check the language, speak closer to the mic, and try again.")
        } else {
            copyToClipboard(text)
            setPhase(Phase.DONE)
            setPreview("✓ Done. Copied to clipboard. Paste anywhere, or tap Insert.")
        }
    }

    // Hard stop (service closing, or a setting changed). Discards the recording.
    private fun stopListening() {
        handler.removeCallbacks(finishTimeout)
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(tick)
        roundActive = false
        lastPartial = ""
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        recognizer = null
        unmuteBeeps()
        transcript.setLength(0)
        setPhase(Phase.IDLE)
    }

    // Shows that the mic is alive (timer + sound level) without showing any words.
    private val tick = object : Runnable {
        override fun run() {
            if (phase != Phase.LISTENING) return
            val sec = (now() - startedAt) / 1000
            val bars = "▁▂▃▄▅▆▇"
            val idx = (level / 10f * (bars.length - 1)).toInt().coerceIn(0, bars.length - 1)
            setPreview(
                "● Listening  " + String.format("%02d:%02d", sec / 60, sec % 60) + "  " + bars[idx] +
                    "\nSpeak freely and pause as you like. Tap Finish when done."
            )
            handler.postDelayed(this, 300)
        }
    }

    private fun commitChunk(raw: String) {
        val text = VoiceText.format(raw, Prefs.punctuation(this), sentenceOpen)
        if (text.isEmpty()) return
        val t = text.trimEnd()
        sentenceOpen = t.isEmpty() || t.last() in ".?!" || text.endsWith("\n")
        if (transcript.isNotEmpty() && !transcript.last().isWhitespace() && text.first() !in ".,?!:;\n") {
            transcript.append(' ')
        }
        transcript.append(text)
    }

    // If a round ended without a final answer, keep the best partial guess instead of losing words.
    private fun commitPartial() {
        val t = lastPartial
        lastPartial = ""
        if (t.isNotBlank()) commitChunk(t)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OmniVoice", text))
    }

    // The system recognizer beeps every time it (re)starts. Mute those streams while recording.
    private fun muteBeeps() {
        if (mutedStreams.isNotEmpty()) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun unmuteBeeps() {
        if (mutedStreams.isEmpty()) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        for (st in mutedStreams) {
            try {
                am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {
            }
        }
        mutedStreams.clear()
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.LANGS[Prefs.lang(this@OverlayService)].first)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Prefs.LANGS[Prefs.lang(this@OverlayService)].first)
            // Partials are kept silently, only as a safety net so no words are lost between rounds.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 20000L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Prefs.engine(this@OverlayService) == 1) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun createAndStart(forceNew: Boolean = false) {
        if (!listening) return
        var sr = recognizer
        if (sr == null || forceNew) {
            commitPartial()
            sr?.let {
                try {
                    it.cancel()
                    it.destroy()
                } catch (_: Exception) {
                }
            }
            val created = SpeechRecognizer.createSpeechRecognizer(this)
            created.setRecognitionListener(listener)
            recognizer = created
            sr = created
        } else {
            try {
                sr.cancel()
            } catch (_: Exception) {
            }
        }
        val rec = sr ?: return
        lastEvent = now()
        roundActive = true
        try {
            rec.startListening(buildIntent())
        } catch (e: Exception) {
            roundActive = false
            setPreview("Could not start mic: ${e.message}")
            restartSoon(800, true)
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 2500)
    }

    private fun restartSoon(delayMs: Long, forceNew: Boolean = false) {
        handler.postDelayed({ if (listening) createAndStart(forceNew) }, delayMs)
    }

    // If the recognizer goes silent (no callbacks at all for 4 s), rebuild it so recording never just dies.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            if (now() - lastEvent > 4000) {
                createAndStart(true)
            } else {
                handler.postDelayed(this, 2500)
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastEvent = now()
            roundActive = true
        }

        override fun onBeginningOfSpeech() {
            lastEvent = now()
        }

        override fun onRmsChanged(rmsdB: Float) {
            lastEvent = now()
            level = level * 0.6f + rmsdB.coerceIn(0f, 10f) * 0.4f
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            lastEvent = now()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            lastEvent = now()
            val t = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!t.isNullOrBlank()) lastPartial = t
        }

        override fun onResults(results: Bundle?) {
            lastEvent = now()
            roundActive = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            var idx = 0
            if (list != null && conf != null && conf.size == list.size && conf.isNotEmpty()) {
                var bestScore = conf[0]
                for (k in conf.indices) {
                    if (conf[k] > bestScore) {
                        bestScore = conf[k]
                        idx = k
                    }
                }
            }
            val best = list?.getOrNull(idx)
            lastPartial = ""
            if (!best.isNullOrBlank()) commitChunk(best)
            when (phase) {
                Phase.PROCESSING -> completeFinish()
                Phase.LISTENING -> restartSoon(20)
                else -> {}
            }
        }

        override fun onError(error: Int) {
            lastEvent = now()
            roundActive = false
            // Keep what was heard even when the round ended without a final result.
            commitPartial()
            if (phase == Phase.PROCESSING) {
                completeFinish()
                return
            }
            if (phase != Phase.LISTENING) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    completeFinish()
                    setPreview("Mic permission missing")
                }
                12, 13 -> { // language not supported / unavailable
                    completeFinish()
                    setPreview("This language is not available offline. Switch engine or language.")
                }
                // Normal pause: nothing heard. Quietly start the next round.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartSoon(20)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> restartSoon(1200, true)
                // Recognizer got stuck: rebuild it.
                else -> restartSoon(300, true)
            }
        }
    }

    // ---------------------------------------------------------------- output

    private fun deliver(text: String) {
        if (text.isEmpty()) return
        for (w in text.split(" ")) {
            if (w.isNotEmpty()) wordQueue.add(w)
        }
        setPreview("Typing: $text")
        typeHandler.removeCallbacks(typer)
        typeHandler.post(typer)
    }

    // Types the queued words one by one (gradual, smooth). If no text box is focused,
    // keeps the words safely queued and retries, so nothing is lost.
    private val typer = object : Runnable {
        override fun run() {
            val w = wordQueue.peek() ?: return
            val ok = TypeAccessibilityService.instance?.insert(w) ?: false
            if (ok) {
                wordQueue.poll()
                warnedCopy = false
                if (wordQueue.isEmpty()) return
                typeHandler.postDelayed(this, if (wordQueue.size > 40) 20L else 55L)
            } else {
                if (!warnedCopy) {
                    warnedCopy = true
                    val all = wordQueue.joinToString(" ")
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OmniVoice", all))
                    setPreview("Tap a text box — your words are saved and also copied")
                }
                typeHandler.postDelayed(this, 1200L)
            }
        }
    }
}

// ScrollView that grows with its text but never taller than maxPx.
private class MaxHeightScroll(ctx: android.content.Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxPx, View.MeasureSpec.AT_MOST)
        )
    }
}
