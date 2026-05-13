package com.jarvis.mobile.utils

import android.content.Context
import com.jarvis.mobile.memory.MemoryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AIClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()

    private val SYSTEM_PROMPT = """
You are J.A.R.V.I.S — mobile AI assistant. You control an Android phone completely.
You know how to do ANYTHING the user asks.

RULES:
1. Always respond in the same language the user uses (Hindi/English/Hinglish).
2. If you need to run code, include it in the "code" field.
3. Be concise in "speak" — short answers.
4. For unknown tasks, explain how you would do it step by step.
5. Never say you can't do something.

RESPONSE FORMAT (JSON):
{
  "speak": "what to say to user",
  "action": "tool_name or none",
  "args": {},
  "code": "optional kotlin/python code to execute",
  "learn": true/false
}
""".trimIndent()

    suspend fun execute(
        ctx: Context,
        command: String,
        context: String = ""
    ): JSONObject = withContext(Dispatchers.IO) {
        val config = MemoryEngine.getConfig(ctx)
        val key    = config.optString("api_key", "")
        val prov   = config.optString("api_provider", "gemini")

        if (key.isBlank()) {
            return@withContext JSONObject().apply {
                put("speak", "No API key set. Please configure in settings.")
            }
        }

        return@withContext try {
            when (prov) {
                "claude"  -> callClaude(key, command, context)
                "openai"  -> callOpenAI(key, command, context)
                "groq"    -> callGroq(key, command, context)
                else      -> callGemini(key, command, context)
            }
        } catch (e: Exception) {
            JSONObject().apply { put("speak", "AI error: ${e.message?.take(80)}") }
        }
    }

    private fun callGemini(key: String, command: String, context: String): JSONObject {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "$SYSTEM_PROMPT\n\nContext:\n$context\n\nCommand: $command")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 1000)
                put("temperature", 0.7)
            })
        }

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=$key")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        val resp = client.newCall(req).execute()
        val text = JSONObject(resp.body!!.string())
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        return parseResponse(text)
    }

    private fun callClaude(key: String, command: String, context: String): JSONObject {
        val msgs = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Context:\n$context\n\nCommand: $command")
            })
        }
        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 1000)
            put("system", SYSTEM_PROMPT)
            put("messages", msgs)
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()

        val resp = client.newCall(req).execute()
        val text = JSONObject(resp.body!!.string())
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
        return parseResponse(text)
    }

    private fun callOpenAI(key: String, command: String, context: String): JSONObject {
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role","system"); put("content", SYSTEM_PROMPT) })
            put(JSONObject().apply { put("role","user");   put("content","Context:\n$context\n\nCommand: $command") })
        }
        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", msgs)
            put("max_tokens", 1000)
        }
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()
        val resp  = client.newCall(req).execute()
        val text  = JSONObject(resp.body!!.string())
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        return parseResponse(text)
    }

    private fun callGroq(key: String, command: String, context: String): JSONObject {
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role","system"); put("content", SYSTEM_PROMPT) })
            put(JSONObject().apply { put("role","user");   put("content","Context:\n$context\n\nCommand: $command") })
        }
        val body = JSONObject().apply {
            put("model", "llama-3.1-70b-versatile")
            put("messages", msgs)
            put("max_tokens", 1000)
        }
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON_MT))
            .build()
        val resp = client.newCall(req).execute()
        val text = JSONObject(resp.body!!.string())
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        return parseResponse(text)
    }

    private fun parseResponse(text: String): JSONObject {
        return try {
            var clean = text.trim()
            if (clean.startsWith("```")) {
                clean = clean.substringAfter("\n").substringBeforeLast("```").trim()
            }
            JSONObject(clean)
        } catch (e: Exception) {
            JSONObject().apply { put("speak", text.take(500)) }
        }
    }
}
