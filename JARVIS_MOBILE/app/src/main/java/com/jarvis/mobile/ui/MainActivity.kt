package com.jarvis.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.mobile.R
import com.jarvis.mobile.agent.CommandEngine
import com.jarvis.mobile.memory.MemoryEngine
import com.jarvis.mobile.service.JarvisService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val VOICE_REQUEST = 100
    private lateinit var logView:  TextView
    private lateinit var inputBox: EditText
    private lateinit var sendBtn:  Button
    private lateinit var micBtn:   ImageButton
    private lateinit var statusDot:View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView   = findViewById(R.id.log_view)
        inputBox  = findViewById(R.id.input_box)
        sendBtn   = findViewById(R.id.send_btn)
        micBtn    = findViewById(R.id.mic_btn)
        statusDot = findViewById(R.id.status_dot)

        // Check first run
        val config = MemoryEngine.getConfig(this)
        if (!config.optBoolean("setup_done")) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }

        startJarvisService()
        setupUI()
        appendLog("JARVIS V3 online. Say 'Jarvis' + command or type below.")
    }

    private fun setupUI() {
        sendBtn.setOnClickListener {
            val cmd = inputBox.text.toString().trim()
            if (cmd.isNotBlank()) {
                appendLog("You: $cmd")
                inputBox.text.clear()
                runCommand(cmd)
            }
        }

        micBtn.setOnClickListener { startVoiceInput() }

        inputBox.setOnEditorActionListener { _, _, _ ->
            sendBtn.performClick()
            true
        }
    }

    private fun runCommand(command: String) {
        CommandEngine.execute(
            rawCommand = command,
            onSpeak    = { text -> runOnUiThread { appendLog("JARVIS: $text") } },
            onResult   = { result ->
                runOnUiThread {
                    if (!result.success) appendLog("[Error] ${result.output.take(80)}")
                }
            }
        )
    }

    private fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your command...")
            }
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (e: Exception) {
            appendLog("Voice input not available.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text    = results?.firstOrNull() ?: return
            appendLog("You: $text")
            runCommand(text)
        }
    }

    private fun appendLog(text: String) {
        val current = logView.text.toString()
        val lines   = current.split("\n").takeLast(80)
        logView.text = (lines + text).joinToString("\n")
        // Scroll to bottom
        val scroll = logView.parent as? ScrollView
        scroll?.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java).apply {
            action = "WAKE_WORD_ON"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
