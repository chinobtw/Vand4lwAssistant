package com.vand4lw.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * Servicio de accesibilidad: dibuja la bola flotante, escucha tu voz,
 * habla las respuestas y toca/escribe en pantalla.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
        @Volatile var lastLog: String = "Esperando..."
        @Volatile var logListener: ((String) -> Unit)? = null

        private val COLOR_IDLE = Color.parseColor("#1E88E5")
        private val COLOR_LISTENING = Color.parseColor("#E53935")
        private val COLOR_THINKING = Color.parseColor("#FFB300")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var assistant: Assistant

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private var wm: WindowManager? = null
    private var bubble: ImageView? = null
    private val bubbleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

    // ───────────────────────── Ciclo de vida ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        assistant = Assistant(this)
        tts = TextToSpeech(this) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                var r = t.setLanguage(Locale("es", "AR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    r = t.setLanguage(Locale("es"))
                }
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("bubble", true)) showBubble()
        log("Servicio activo. Tocá la bola y hablá.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        job?.cancel()
        scope.cancel()
        hideBubble(save = false)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        if (instance === this) instance = null
    }

    fun log(msg: String) {
        lastLog = msg
        logListener?.invoke(msg)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ───────────────────────── Bola flotante ─────────────────────────

    private fun setBubbleColor(color: Int) = bubbleBg.setColor(color)

    fun showBubble() {
        if (bubble != null) return
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()
        val pad = (14 * dp).toInt()

        val view = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = bubbleBg
            setPadding(pad, pad, pad, pad)
            setColorFilter(Color.WHITE)
            alpha = 0.92f
            setOnClickListener { onBubbleTap() }
        }
        setBubbleColor(COLOR_IDLE)

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (200 * dp).toInt()
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm?.updateViewLayout(v, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            wm?.addView(view, lp)
            bubble = view
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("bubble", true).apply()
        } catch (e: Exception) {
            log("No pude mostrar la bola: ${e.message}")
        }
    }

    fun hideBubble(save: Boolean = true) {
        bubble?.let {
            try { wm?.removeView(it) } catch (e: Exception) { }
        }
        bubble = null
        if (save) getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("bubble", false).apply()
    }

    /** Devuelve true si la bola quedó visible. */
    fun toggleBubble(): Boolean {
        if (bubble == null) showBubble() else hideBubble()
        return bubble != null
    }

    private fun onBubbleTap() {
        if (listening) {
            recognizer?.cancel()
            listening = false
            setBubbleColor(COLOR_IDLE)
        } else {
            listen()
        }
    }

    // ───────────────────────── Voz ─────────────────────────

    fun listen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Falta el permiso de micrófono: dáselo desde la app")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Este celu no tiene reconocimiento de voz")
            return
        }
        job?.cancel()
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        setBubbleColor(COLOR_LISTENING)
        recognizer?.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            setBubbleColor(COLOR_IDLE)
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                log("Error de voz (código $error)")
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                setBubbleColor(COLOR_IDLE)
            } else {
                runCommand(text)
            }
        }
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    // ───────────────────────── Órdenes ─────────────────────────

    /** Punto de entrada único: voz, texto de la app o lo que venga. */
    fun runCommand(text: String) {
        job?.cancel()
        log("Escuché: $text")
        setBubbleColor(COLOR_THINKING)
        job = scope.launch {
            try {
                val plan = assistant.ask(text)
                log("IA: ${plan.say}\n" + plan.actions.joinToString("\n") { it.toString() })
                if (plan.say.isNotBlank()) speak(plan.say)
                assistant.execute(plan.actions) { log(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Error: ${e.message}")
                speak("Hubo un error")
            } finally {
                setBubbleColor(COLOR_IDLE)
            }
        }
    }

    // ───────────────────────── Control de pantalla ─────────────────────────

    fun global(type: String) {
        val action = when (type) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw RuntimeException("acción global desconocida: $type")
        }
        performGlobalAction(action)
    }

    fun scroll(direction: String) {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val top = m.heightPixels * 0.3f
        val bottom = m.heightPixels * 0.7f
        val path = Path()
        if (direction == "up") {
            path.moveTo(x, top); path.lineTo(x, bottom)
        } else {
            path.moveTo(x, bottom); path.lineTo(x, top)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun clickOnText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (n in root.findAccessibilityNodeInfosByText(text)) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }
}
