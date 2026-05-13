package com.jarvis.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.mobile.service.JarvisService

// ── Boot Receiver — auto-start JARVIS on reboot ───────────────────────────────
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON"
            )) {
            Log.i("BootReceiver", "Device booted — starting JARVIS")
            val svc = Intent(ctx, JarvisService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc)
            } else {
                ctx.startService(svc)
            }
        }
    }
}

// ── SMS Receiver — auto-process incoming SMS ──────────────────────────────────
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        try {
            val pdus    = intent.extras?.get("pdus") as? Array<*> ?: return
            val format  = intent.getStringExtra("format") ?: "3gpp"
            val msgs    = pdus.mapNotNull { pdu ->
                android.telephony.SmsMessage.createFromPdu(pdu as ByteArray, format)
            }
            val from    = msgs.firstOrNull()?.displayOriginatingAddress ?: "Unknown"
            val body    = msgs.joinToString("") { it.displayMessageBody ?: "" }
            Log.i("SmsReceiver", "SMS from $from: ${body.take(50)}")

            // Speak notification if JARVIS is running
            JarvisService.instance?.speakText("New message from $from.")

            // Check auto-reply rules
            com.jarvis.mobile.tools.AutomationTool.routines
                .filter { it["type"] == "auto_reply" }
                .forEach { rule ->
                    val trigger = rule["trigger"] ?: ""
                    val reply   = rule["reply"]   ?: ""
                    if (trigger.isNotBlank() && body.contains(trigger, ignoreCase = true)) {
                        com.jarvis.mobile.tools.SmsTool.send(ctx, from, reply) {}
                    }
                }
        } catch (e: Exception) {
            Log.e("SmsReceiver", e.message ?: "error")
        }
    }
}

// ── Call Receiver — handle incoming/outgoing calls ────────────────────────────
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_PHONE_STATE_CHANGED -> {
                    val state  = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            Log.i("CallReceiver", "Incoming: $number")
                            if (number.isNotBlank()) {
                                JarvisService.instance?.speakText("Incoming call from $number.")
                            }
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            Log.i("CallReceiver", "Call active")
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            Log.i("CallReceiver", "Call ended")
                        }
                    }
                }
                Intent.ACTION_NEW_OUTGOING_CALL -> {
                    val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
                    Log.i("CallReceiver", "Outgoing: $number")
                }
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", e.message ?: "error")
        }
    }
}
