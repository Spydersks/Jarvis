package com.jarvis.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.mobile.R
import com.jarvis.mobile.memory.MemoryEngine
import org.json.JSONObject

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val keyInput  = findViewById<EditText>(R.id.api_key_input)
        val provGroup = findViewById<RadioGroup>(R.id.provider_group)
        val pcHost    = findViewById<EditText>(R.id.pc_host_input)
        val saveBtn   = findViewById<Button>(R.id.save_btn)
        val skipBtn   = findViewById<Button>(R.id.skip_btn)

        saveBtn.setOnClickListener {
            val key  = keyInput.text.toString().trim()
            val prov = when (provGroup.checkedRadioButtonId) {
                R.id.rb_gemini -> "gemini"
                R.id.rb_claude -> "claude"
                R.id.rb_openai -> "openai"
                R.id.rb_groq   -> "groq"
                else           -> "gemini"
            }
            val host = pcHost.text.toString().trim()

            val config = JSONObject().apply {
                put("api_key",      key)
                put("api_provider", prov)
                put("pc_host",      host)
                put("pc_port",      8765)
                put("setup_done",   true)
            }
            MemoryEngine.saveConfig(this, config)
            Toast.makeText(this, "Saved! Starting JARVIS...", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        skipBtn.setOnClickListener {
            val config = JSONObject().apply { put("setup_done", true) }
            MemoryEngine.saveConfig(this, config)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
