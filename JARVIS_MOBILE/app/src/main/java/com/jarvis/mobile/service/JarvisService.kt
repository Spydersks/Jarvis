package com.jarvis.mobile.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.mobile.JarvisApp
import com.jarvis.mobile.agent.CommandEngine
import com.jarvis.mobile.bridge.BridgeService
import com.jarvis.mobile.ui.MainActivity
import kotlinx.coroutines.*
import java.util.Locale

class JarvisService : Service() {

    private val TAG     = "JarvisService"
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var sr: SpeechRecognizer? = null
    private var isListening = false
    private var wakeWordActive = true
    private var WAKE_WORD = "jarvis"

    companion object {
        var instance: JarvisService? = null
        fun speak(text: String) { instance?.speakText(text) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initTTS()
        startForegroundNotification()
        startBridge()
        Log.i(TAG, "JarvisService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_LISTENING"  -> startListening()
            "STOP_LISTENING"   -> stopListening()
            "EXECUTE"          -> {
                val cmd = intent.getStringExtra("command") ?: return START_STICKY
                executeCommand(cmd)
            }
            "WAKE_WORD_ON"  -> { wakeWordActive = true; startWakeWordLoop() }
            "WAKE_WORD_OFF" -> { wakeWordActive = false; stopListening() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────
    //  TTS
    // ─────────────────────────────────────────────────────────────────────
    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)
                Log.i(TAG, "TTS ready")
            }
        }
    }

    fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_$System.currentTimeMillis()")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  VOICE RECOGNITION — continuous wake-word detection
    // ─────────────────────────────────────────────────────────────────────
    private fun startWakeWordLoop() {
        scope.launch(Dispatchers.Main) {
            startListening()
        }
    }

    private fun startListening() {
        if (isListening) return
        sr = SpeechRecognizer.createSpeechRecognizer(this)
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p0: android.os.Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onResults(bundle: android.os.Bundle?) {
                isListening = false
                val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text    = results?.firstOrNull()?.lowercase() ?: ""
                Log.d(TAG, "Heard: $text")

                if (text.contains(WAKE_WORD)) {
                    val command = text.substringAfter(WAKE_WORD).trim()
                    if (command.isNotBlank()) {
                        executeCommand(command)
                    } else {
                        speakText("Yes sir?")
                        startCommandListening()
                        return
                    }
                }
                if (wakeWordActive) { delay(500); startListening() }
            }

            override fun onError(error: Int) {
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH       -> "no_match"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    SpeechRecognizer.ERROR_NETWORK        -> "network"
                    else -> "error_$error"
                }
                Log.d(TAG, "SR error: $msg")
                if (wakeWordActive) { scope.launch { delay(1000); startListening() } }
            }

            override fun onPartialResults(p0: android.os.Bundle?) {}
            override fun onEvent(p0: Int, p0b: android.os.Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        scope.launch(Dispatchers.Main) {
            sr?.startListening(intent)
        }
    }

    private fun startCommandListening() {
        // One-shot deeper listening for command after wake word detected
        startListening()
    }

    fun stopListening() {
        isListening = false
        sr?.stopListening()
        sr?.destroy()
        sr = null
    }

    // ─────────────────────────────────────────────────────────────────────
    //  COMMAND EXECUTION
    // ─────────────────────────────────────────────────────────────────────
    fun executeCommand(command: String) {
        Log.i(TAG, "Execute: $command")
        CommandEngine.execute(
            rawCommand = command,
            onSpeak    = { text -> speakText(text) },
            onResult   = { result ->
                Log.i(TAG, "Result: ${result.output.take(100)}")
                if (!result.success) {
                    Log.w(TAG, "Command failed: ${result.output}")
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    //  FOREGROUND NOTIFICATION
    // ─────────────────────────────────────────────────────────────────────
    private fun startForegroundNotification() {
        val intent    = Intent(this, MainActivity::class.java)
        val pendingInt = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, JarvisApp.NOTIF_CHANNEL_WAKE)
            .setContentTitle("J.A.R.V.I.S Active")
            .setContentText("Listening for 'Jarvis'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingInt)
            .addAction(android.R.drawable.ic_media_pause, "Stop",
                PendingIntent.getService(this, 1,
                    Intent(this, JarvisService::class.java).setAction("STOP_LISTENING"),
                    PendingIntent.FLAG_IMMUTABLE))
            .build()

        startForeground(1, notification)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  BRIDGE
    // ─────────────────────────────────────────────────────────────────────
    private fun startBridge() {
        val intent = Intent(this, BridgeService::class.java)
        startService(intent)
    }

    private fun delay(ms: Long) {
        scope.launch { kotlinx.coroutines.delay(ms) }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        stopListening()
        scope.cancel()
        instance = null
        // Auto-restart
        val restart = Intent(this, JarvisService::class.java)
        startService(restart)
    }
}
