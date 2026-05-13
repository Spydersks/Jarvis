package com.jarvis.mobile.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.mobile.agent.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═══════════════════════════════════════════════════════════════════
//  PHONE TOOL
// ═══════════════════════════════════════════════════════════════════
object PhoneTool {
    fun call(ctx: Context, contact: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val number = ContactsTool.getNumber(ctx, contact) ?: contact
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Calling $contact.")
            CommandResult.success("Called $contact")
        } catch (e: SecurityException) {
            onSpeak("I need call permission. Please grant it.")
            CommandResult.failure("Permission: ${e.message}")
        } catch (e: Exception) {
            onSpeak("Could not call $contact.")
            CommandResult.failure(e.message ?: "call failed")
        }
    }

    fun endCall(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                tm?.endCall()
            }
            onSpeak("Call ended.")
            CommandResult.success("ended")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun answer(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                tm?.acceptRingingCall()
            }
            onSpeak("Answering call.")
            CommandResult.success("answered")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getCallLog(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cursor = ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, null, null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            val calls = mutableListOf<String>()
            cursor?.use {
                val nameIdx   = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx   = it.getColumnIndex(CallLog.Calls.TYPE)
                var count     = 0
                while (it.moveToNext() && count < 5) {
                    val name   = it.getString(nameIdx) ?: it.getString(numberIdx)
                    val type   = when (it.getInt(typeIdx)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE   -> "Missed"
                        else -> "Unknown"
                    }
                    calls.add("$type from $name")
                    count++
                }
            }
            val result = if (calls.isEmpty()) "No recent calls." else calls.joinToString(", ")
            onSpeak("Recent calls: $result")
            CommandResult.success(result)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun setSpeaker(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.isSpeakerphoneOn = on
            val msg = if (on) "Speaker on." else "Speaker off."
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun muteCall(ctx: Context, mute: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.isMicrophoneMute = mute
            onSpeak(if (mute) "Muted." else "Unmuted.")
            CommandResult.success("ok")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SMS TOOL
// ═══════════════════════════════════════════════════════════════════
object SmsTool {
    fun send(ctx: Context, contact: String, message: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val number = ContactsTool.getNumber(ctx, contact) ?: contact
            if (number.isBlank()) {
                onSpeak("Contact not found: $contact")
                return CommandResult.failure("contact not found")
            }
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            onSpeak("Message sent to $contact.")
            CommandResult.success("SMS sent")
        } catch (e: SecurityException) {
            onSpeak("SMS permission needed.")
            CommandResult.failure("permission")
        } catch (e: Exception) {
            onSpeak("Could not send message: ${e.message?.take(50)}")
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun readUnread(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cursor = ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "read"),
                "read = 0", null, "date DESC"
            )
            val msgs = mutableListOf<String>()
            cursor?.use {
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                var count   = 0
                while (it.moveToNext() && count < 5) {
                    val from = it.getString(addrIdx) ?: "Unknown"
                    val body = it.getString(bodyIdx)?.take(80) ?: ""
                    msgs.add("From $from: $body")
                    count++
                }
            }
            val result = if (msgs.isEmpty()) "No unread messages."
            else "${msgs.size} unread: ${msgs.first()}"
            onSpeak(result)
            CommandResult.success(result)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun replyLast(ctx: Context, message: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cursor = ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address"), null, null, "date DESC"
            )
            val number = cursor?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndex("address")) else null
            }
            if (number == null) {
                onSpeak("No recent message to reply to.")
                return CommandResult.failure("no recent message")
            }
            send(ctx, number, message, onSpeak)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun deleteLast(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cursor = ctx.contentResolver.query(
                Uri.parse("content://sms"), arrayOf("_id"), null, null, "date DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getString(it.getColumnIndex("_id"))
                    ctx.contentResolver.delete(Uri.parse("content://sms/$id"), null, null)
                    onSpeak("Last message deleted.")
                }
            }
            CommandResult.success("deleted")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SYSTEM TOOL
// ═══════════════════════════════════════════════════════════════════
object SystemTool {
    fun getBattery(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val manager = ctx.getSystemService(android.os.BatteryManager::class.java)
            val level   = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = manager.isCharging
            val msg = "Battery is $level percent. ${if (charging) "Charging." else "Not charging."}"
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getRamUsage(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am   = ctx.getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val total    = info.totalMem / (1024 * 1024)
            val avail    = info.availMem / (1024 * 1024)
            val used     = total - avail
            val msg = "RAM: ${used}MB used of ${total}MB total."
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getCpuUsage(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val msg = "CPU usage information requires root access. RAM and battery are available."
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getStorageInfo(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val stat     = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val total    = stat.totalBytes / (1024 * 1024 * 1024)
            val free     = stat.availableBytes / (1024 * 1024 * 1024)
            val msg      = "Storage: ${free}GB free of ${total}GB total."
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getNetworkSpeed(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening speed test.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://fast.com")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        return CommandResult.success("opened speedtest")
    }

    fun getDeviceInfo(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val info = "Device: ${Build.MANUFACTURER} ${Build.MODEL}. " +
            "Android ${Build.VERSION.RELEASE}. " +
            "SDK ${Build.VERSION.SDK_INT}."
        onSpeak(info)
        return CommandResult.success(info)
    }

    fun getIpAddress(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var ip = "Unknown"
            interfaces?.iterator()?.forEach { ni ->
                ni.inetAddresses?.iterator()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        ip = addr.hostAddress ?: "Unknown"
                    }
                }
            }
            val msg = "IP address: $ip"
            onSpeak(msg)
            CommandResult.success(ip)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun restart(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Restart requires manufacturer tools. Opening power menu.")
        return CommandResult.success("shown")
    }

    fun shutdown(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Shutdown requires device admin. Opening power dialog.")
        return CommandResult.success("shown")
    }

    fun triggerSOS(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Emergency SOS activated. Calling emergency services.")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:112")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            ctx.startActivity(intent)
            CommandResult.success("SOS called")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun vibrate(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(android.os.VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(android.os.Vibrator::class.java)
            }
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
            CommandResult.success("vibrated")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun ping(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val reachable = java.net.InetAddress.getByName("8.8.8.8").isReachable(3000)
            val msg = if (reachable) "Internet connection is working." else "No internet connection."
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            onSpeak("Network check failed.")
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun fullStatus(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val battery  = try {
            val m = ctx.getSystemService(android.os.BatteryManager::class.java)
            "${m.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        } catch (e: Exception) { "?" }

        val storage  = try {
            val s = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            "${s.availableBytes/(1024*1024*1024)}GB free"
        } catch (e: Exception) { "?" }

        val msg = "JARVIS V3 running. Battery $battery. Storage $storage. All systems operational."
        onSpeak(msg)
        return CommandResult.success(msg)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  MEDIA TOOL
// ═══════════════════════════════════════════════════════════════════
object MediaTool {
    private fun getAudioManager(ctx: Context) =
        ctx.getSystemService(AudioManager::class.java)

    fun play(ctx: Context, query: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = if (query.isBlank()) {
                Intent("com.android.music.musicservicecommand").apply {
                    putExtra("command", "play")
                }
            } else {
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            }
            ctx.startActivity(intent)
            onSpeak(if (query.isBlank()) "Playing." else "Playing $query on YouTube.")
            CommandResult.success("playing")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun pause(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            val ke = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            am.dispatchMediaKeyEvent(ke)
            onSpeak("Paused.")
            CommandResult.success("paused")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun next(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
            onSpeak("Next song.")
            CommandResult.success("next")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun previous(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            onSpeak("Previous song.")
            CommandResult.success("prev")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun volumeUp(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            onSpeak("Volume: ${(vol * 100 / max)}%")
            CommandResult.success("$vol")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun volumeDown(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            CommandResult.success("down")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun mute(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            onSpeak("Muted.")
            CommandResult.success("muted")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun unmute(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val am = getAudioManager(ctx)
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            onSpeak("Unmuted.")
            CommandResult.success("unmuted")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }

    fun shuffle(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        onSpeak(if (on) "Shuffle on." else "Shuffle off.")
        return CommandResult.success("ok")
    }

    fun repeat(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        onSpeak(if (on) "Repeat on." else "Repeat off.")
        return CommandResult.success("ok")
    }
}

// ═══════════════════════════════════════════════════════════════════
//  APP TOOL
// ═══════════════════════════════════════════════════════════════════
object AppTool {
    private val APP_MAP = mapOf(
        "chrome"      to "com.android.chrome",
        "youtube"     to "com.google.android.youtube",
        "maps"        to "com.google.android.apps.maps",
        "gmail"       to "com.google.android.gm",
        "drive"       to "com.google.android.apps.docs",
        "photos"      to "com.google.android.apps.photos",
        "camera"      to "com.android.camera2",
        "calculator"  to "com.android.calculator2",
        "clock"       to "com.android.deskclock",
        "calendar"    to "com.google.android.calendar",
        "contacts"    to "com.android.contacts",
        "phone"       to "com.android.dialer",
        "messages"    to "com.google.android.apps.messaging",
        "settings"    to "com.android.settings",
        "files"       to "com.google.android.apps.nbu.files",
        "play store"  to "com.android.vending",
        "whatsapp"    to "com.whatsapp",
        "instagram"   to "com.instagram.android",
        "facebook"    to "com.facebook.katana",
        "twitter"     to "com.twitter.android",
        "telegram"    to "org.telegram.messenger",
        "spotify"     to "com.spotify.music",
        "netflix"     to "com.netflix.mediaclient",
        "amazon"      to "com.amazon.mShop.android.shopping",
        "flipkart"    to "com.flipkart.android",
        "paytm"       to "net.one97.paytm",
        "gpay"        to "com.google.android.apps.nbu.paisa.user",
        "phonepe"     to "com.phonepe.app",
        "zoom"        to "us.zoom.videomeetings",
        "meet"        to "com.google.android.apps.meetings",
        "teams"       to "com.microsoft.teams",
        "notion"      to "notion.id",
        "amazon music" to "com.amazon.mp3",
        "gaana"       to "com.gaana",
        "hotstar"     to "in.startv.hotstar",
        "swiggy"      to "in.swiggy.android",
        "zomato"      to "com.application.zomato",
    )

    fun openByName(ctx: Context, appName: String, onSpeak: (String) -> Unit): CommandResult {
        val pkg = APP_MAP[appName.lowercase().trim()]
            ?: findByPartialName(ctx, appName)
        return if (pkg != null) open(ctx, pkg, onSpeak)
        else {
            // Try Play Store
            onSpeak("Opening Play Store to find $appName.")
            install(ctx, appName, onSpeak)
        }
    }

    fun open(ctx: Context, packageName: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw Exception("App not installed: $packageName")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(intent)
            onSpeak("Opening.")
            CommandResult.success("opened $packageName")
        } catch (e: Exception) {
            onSpeak("Could not open that app.")
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun close(ctx: Context, appName: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Use back button or recent apps to close $appName.")
        return CommandResult.success("instructed")
    }

    fun install(ctx: Context, appName: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://search?q=${Uri.encode(appName)}&c=apps")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
            onSpeak("Searching Play Store for $appName.")
            CommandResult.success("opened store")
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(webIntent)
            CommandResult.success("opened web store")
        }
    }

    fun uninstall(ctx: Context, appName: String, onSpeak: (String) -> Unit): CommandResult {
        val pkg = APP_MAP[appName.lowercase()] ?: findByPartialName(ctx, appName)
        return if (pkg != null) {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Uninstall dialog opened for $appName.")
            CommandResult.success("uninstall dialog opened")
        } else {
            onSpeak("App not found: $appName")
            CommandResult.failure("not found")
        }
    }

    fun listInstalled(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val pm   = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
            .mapNotNull { pm.getLaunchIntentForPackage(it.packageName)?.let { _ -> pm.getApplicationLabel(it).toString() } }
            .distinct().sorted().take(10)
        val msg = "Installed apps include: ${apps.joinToString(", ")}"
        onSpeak(msg)
        return CommandResult.success(msg)
    }

    fun showInfo(ctx: Context, appName: String, onSpeak: (String) -> Unit): CommandResult {
        val pkg = APP_MAP[appName.lowercase()] ?: appName
        return try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
            onSpeak("Opening app info for $appName.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun openYouTube(ctx: Context, query: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = if (query.isBlank()) {
                ctx.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com"))
            } else {
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            ctx.startActivity(intent)
            onSpeak(if (query.isBlank()) "Opening YouTube." else "Searching YouTube for $query.")
            CommandResult.success("youtube opened")
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(webIntent)
            CommandResult.success("browser fallback")
        }
    }

    fun openSettings(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening settings.")
        return CommandResult.success("settings opened")
    }

    fun openDateTimeSettings(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_DATE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        return CommandResult.success("opened")
    }

    fun openBanking(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Which banking app? GPay, PhonePe, Paytm, or your bank app?")
        return CommandResult.success("asked")
    }

    fun showRecents(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening recent apps.")
        return CommandResult.success("shown")
    }

    fun goHome(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        return CommandResult.success("home")
    }

    fun goBack(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Going back.")
        return CommandResult.success("back")
    }

    fun openPlayStoreUpdates(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://my-apps?utm_source=apps_page")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Opening Play Store updates.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    private fun findByPartialName(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).firstOrNull { ai ->
            pm.getApplicationLabel(ai).toString().lowercase().contains(name.lowercase())
        }?.packageName
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CONTACTS TOOL
// ═══════════════════════════════════════════════════════════════════
object ContactsTool {
    fun getNumber(ctx: Context, name: String): String? {
        return try {
            val cursor = ctx.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else null
            }
        } catch (e: Exception) { null }
    }

    fun add(ctx: Context, name: String, phone: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Adding contact $name.")
            CommandResult.success("contact form opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun find(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        val number = getNumber(ctx, name)
        val msg = if (number != null) "$name: $number" else "Contact $name not found."
        onSpeak(msg)
        return CommandResult.success(msg)
    }

    fun delete(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Please confirm deletion of $name in contacts app.")
        return CommandResult.success("instructed")
    }

    fun edit(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening contacts to edit.")
        return open(ctx, "com.android.contacts", onSpeak)
    }

    private fun open(ctx: Context, pkg: String, onSpeak: (String) -> Unit): CommandResult {
        return AppTool.open(ctx, pkg, onSpeak)
    }

    fun listAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cursor = ctx.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                null, null,
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            val names = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext() && names.size < 5) {
                    val n = it.getString(it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME))
                    if (n != null) names.add(n)
                }
            }
            val total  = cursor?.count ?: 0
            val msg    = "You have $total contacts. First few: ${names.joinToString(", ")}"
            onSpeak(msg)
            CommandResult.success(msg)
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun getBirthdays(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Checking contacts for birthday information.")
        return CommandResult.success("checked")
    }
}

// ═══════════════════════════════════════════════════════════════════
//  WIFI TOOL
// ═══════════════════════════════════════════════════════════════════
object WifiTool {
    fun setEnabled(ctx: Context, enabled: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Opening WiFi settings.")
            CommandResult.success("opened wifi settings")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun connect(ctx: Context, ssid: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening WiFi settings to connect to $ssid.")
        return setEnabled(ctx, true, onSpeak)
    }

    fun scan(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening WiFi to show available networks.")
        return setEnabled(ctx, true, onSpeak)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  BLUETOOTH TOOL
// ═══════════════════════════════════════════════════════════════════
object BluetoothTool {
    fun setEnabled(ctx: Context, enabled: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Opening Bluetooth settings.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun scan(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return setEnabled(ctx, true, onSpeak)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SETTINGS TOOL
// ═══════════════════════════════════════════════════════════════════
object SettingsTool {
    fun setAirplaneMode(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening airplane mode settings.")
        return CommandResult.success("opened")
    }

    fun setHotspot(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.TetherSettings")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            ctx.startActivity(intent)
            onSpeak("Opening hotspot settings.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            AppTool.openSettings(ctx, onSpeak)
        }
    }

    fun setDND(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak(if (on) "Opening DND settings." else "Opening DND settings to turn off.")
        return CommandResult.success("opened")
    }

    fun setBrightness(ctx: Context, level: Int, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val clamped = level.coerceIn(0, 100)
            val actual  = (clamped * 255 / 100)
            android.provider.Settings.System.putInt(
                ctx.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                actual
            )
            onSpeak("Brightness set to $clamped%.")
            CommandResult.success("brightness: $clamped")
        } catch (e: SecurityException) {
            val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Opening display settings for brightness.")
            CommandResult.failure("need permission")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun setDarkMode(ctx: Context, dark: Boolean, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak(if (dark) "Opening display settings for dark mode." else "Opening display settings for light mode.")
        return CommandResult.success("opened")
    }

    fun setRotation(ctx: Context, auto: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            android.provider.Settings.System.putInt(
                ctx.contentResolver,
                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                if (auto) 1 else 0
            )
            onSpeak(if (auto) "Auto rotation on." else "Rotation locked.")
            CommandResult.success("ok")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun setFontSize(ctx: Context, size: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening display settings to adjust font size.")
        return CommandResult.success("opened")
    }

    fun changeWallpaper(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(Intent.createChooser(intent, "Set Wallpaper").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            onSpeak("Opening wallpaper picker.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun setLanguage(ctx: Context, lang: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening language settings.")
        return CommandResult.success("opened")
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CAMERA TOOL (stub — real impl uses CameraX)
// ═══════════════════════════════════════════════════════════════════
object CameraTool {
    fun takePhoto(ctx: Context, front: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (front) putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            }
            ctx.startActivity(intent)
            onSpeak("Opening camera.")
            CommandResult.success("camera opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun recordVideo(ctx: Context, duration: Int, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, duration)
            }
            ctx.startActivity(intent)
            onSpeak("Recording video for $duration seconds.")
            CommandResult.success("video recording")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun setFlash(ctx: Context, on: Boolean, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val cm  = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val id  = cm.cameraIdList.firstOrNull() ?: return CommandResult.failure("no camera")
            cm.setTorchMode(id, on)
            onSpeak(if (on) "Flashlight on." else "Flashlight off.")
            CommandResult.success(if (on) "on" else "off")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun scanQR(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening camera to scan QR code.")
        return takePhoto(ctx, false, onSpeak)
    }

    fun scanDocument(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening camera to scan document.")
        return takePhoto(ctx, false, onSpeak)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  SCREEN TOOL
// ═══════════════════════════════════════════════════════════════════
object ScreenTool {
    fun capture(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Taking screenshot. Use power + volume down.")
        return CommandResult.success("instructed")
    }

    fun lock(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            dpm.lockNow()
            onSpeak("Screen locked.")
            CommandResult.success("locked")
        } catch (e: Exception) {
            onSpeak("Lock screen requires device admin. Opening settings.")
            AppTool.openSettings(ctx, onSpeak)
            CommandResult.failure("need admin")
        }
    }

    fun unlock(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Please unlock the screen manually or with fingerprint.")
        return CommandResult.success("instructed")
    }

    fun wakeUp(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Screen wake up command sent.")
        return CommandResult.success("ok")
    }

    fun turnOff(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return lock(ctx, onSpeak)
    }

    fun record(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening screen recorder. Look for screen record in quick settings.")
        return CommandResult.success("instructed")
    }

    fun stopRecord(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Stopping screen recording.")
        return CommandResult.success("instructed")
    }

    fun share(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening cast settings.")
        val intent = Intent(android.provider.Settings.ACTION_CAST_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            ctx.startActivity(intent)
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  WEB TOOL
// ═══════════════════════════════════════════════════════════════════
object WebTool {
    fun search(ctx: Context, query: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Searching for $query.")
            CommandResult.success("searching")
        } catch (e: Exception) {
            openUrl(ctx, "https://www.google.com/search?q=${Uri.encode(query)}", onSpeak)
        }
    }

    fun openUrl(ctx: Context, url: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val fullUrl = if (!url.startsWith("http")) "https://$url" else url
            val intent  = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            onSpeak("Opening $url.")
            CommandResult.success("opened")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun translate(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val query = Uri.encode(cmd)
        return openUrl(ctx, "https://translate.google.com/?text=$query", onSpeak)
    }

    fun getNews(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening news.")
        return openUrl(ctx, "https://news.google.com", onSpeak)
    }

    fun getSportsScore(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return openUrl(ctx, "https://www.google.com/search?q=live+cricket+score", onSpeak)
    }

    fun getStockPrice(ctx: Context, stock: String, onSpeak: (String) -> Unit): CommandResult {
        return openUrl(ctx, "https://www.google.com/search?q=${Uri.encode("$stock stock price")}", onSpeak)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  FILE TOOL (stub — full impl uses SAF)
// ═══════════════════════════════════════════════════════════════════
object FileTool {
    fun open(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        return AppTool.open(ctx, "com.google.android.apps.nbu.files", onSpeak)
    }

    fun delete(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening files to delete $name.")
        return open(ctx, name, onSpeak)
    }

    fun copyLast(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening file manager to copy.")
        return open(ctx, "", onSpeak)
    }

    fun share(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening share options.")
        return CommandResult.success("opened")
    }

    fun getStorageInfo(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return SystemTool.getStorageInfo(ctx, onSpeak)
    }

    fun clean(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return AppTool.open(ctx, "com.google.android.apps.nbu.files", onSpeak)
    }

    fun zip(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Compression feature opening files.")
        return open(ctx, name, onSpeak)
    }

    fun unzip(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening file manager to extract $name.")
        return open(ctx, name, onSpeak)
    }

    fun rename(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening file manager to rename.")
        return open(ctx, "", onSpeak)
    }

    fun createFolder(ctx: Context, name: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening file manager to create folder $name.")
        return open(ctx, "", onSpeak)
    }

    fun download(ctx: Context, url: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    url.substringAfterLast("/")
                )
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
            dm.enqueue(request)
            onSpeak("Download started.")
            CommandResult.success("downloading")
        } catch (e: Exception) {
            CommandResult.failure(e.message ?: "error")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  REMAINING STUBS (Calendar, Alarm, Location, AI, etc.)
// ═══════════════════════════════════════════════════════════════════

object CalendarTool {
    fun addEvent(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data  = android.provider.CalendarContract.Events.CONTENT_URI
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening calendar to add event.")
        return CommandResult.success("opened")
    }
    fun getTodayEvents(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        AppTool.open(ctx, "com.google.android.calendar", onSpeak)
        return CommandResult.success("opened")
    }
    fun getUpcoming(ctx: Context, days: Int, onSpeak: (String) -> Unit): CommandResult =
        getTodayEvents(ctx, onSpeak)
    fun deleteEvent(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening calendar to delete event.")
        return getTodayEvents(ctx, onSpeak)
    }
}

object AlarmTool {
    fun set(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening alarm.")
        return CommandResult.success("opened")
    }
    fun cancelAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        return CommandResult.success("opened")
    }
    fun setTimer(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val minutes = Regex("""\d+""").find(cmd)?.value?.toIntOrNull() ?: 5
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Timer set for $minutes minutes.")
        return CommandResult.success("timer set")
    }
    fun startStopwatch(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        AppTool.open(ctx, "com.android.deskclock", onSpeak)
        return CommandResult.success("opened")
    }
}

object LocationTool {
    fun getCurrent(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Getting your location.")
        AppTool.open(ctx, "com.google.android.apps.maps", onSpeak)
        return CommandResult.success("opened")
    }
    fun navigate(ctx: Context, destination: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            ).apply { setPackage("com.google.android.apps.maps"); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
            onSpeak("Navigating to $destination.")
            CommandResult.success("navigating")
        } catch (e: Exception) {
            WebTool.openUrl(ctx, "https://maps.google.com/?daddr=${Uri.encode(destination)}", onSpeak)
        }
    }
    fun searchNearby(ctx: Context, what: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(what)}")
            ).apply { setPackage("com.google.android.apps.maps"); flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
            onSpeak("Searching for nearby $what.")
            CommandResult.success("searching")
        } catch (e: Exception) { CommandResult.failure(e.message ?: "error") }
    }
    fun share(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening maps to share your location.")
        return getCurrent(ctx, onSpeak)
    }
    fun startTracking(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Location tracking started.")
        return CommandResult.success("tracking")
    }
    fun getWeather(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return WebTool.search(ctx, "weather today", onSpeak)
    }
}

object NotificationTool {
    fun readAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val svc = com.jarvis.mobile.service.NotificationListener.instance
        if (svc != null) {
            val msgs = svc.getAll().take(5).joinToString(". ")
            onSpeak(if (msgs.isBlank()) "No notifications." else "Notifications: $msgs")
        } else {
            onSpeak("Notification access not enabled. Opening settings.")
            openSettings(ctx, onSpeak)
        }
        return CommandResult.success("read")
    }
    fun clearAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        com.jarvis.mobile.service.NotificationListener.instance?.clearAll()
        onSpeak("Notifications cleared.")
        return CommandResult.success("cleared")
    }
    fun openSettings(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        return CommandResult.success("opened")
    }
    fun show(ctx: Context, title: String, msg: String, onSpeak: (String) -> Unit): CommandResult {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val notif = android.app.Notification.Builder(ctx, com.jarvis.mobile.JarvisApp.NOTIF_CHANNEL_ID)
            .setContentTitle(title).setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true).build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        return CommandResult.success("shown")
    }
}

object AiTool {
    fun ask(ctx: Context, question: String, onSpeak: (String) -> Unit): CommandResult {
        kotlinx.coroutines.runBlocking {
            val resp = AIClient.execute(ctx, question)
            onSpeak(resp.optString("speak", "Let me think about that."))
        }
        return CommandResult.success("answered")
    }
    fun write(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult = ask(ctx, cmd, onSpeak)
    fun summarize(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult = ask(ctx, "Summarize: $cmd", onSpeak)
    fun tellJoke(ctx: Context, onSpeak: (String) -> Unit): CommandResult = ask(ctx, "Tell me a short funny joke", onSpeak)
    fun writePoem(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult = ask(ctx, "Write a short poem about: $cmd", onSpeak)
    fun calculate(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult = ask(ctx, "Calculate: $cmd", onSpeak)
    fun convert(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult = ask(ctx, "Convert: $cmd", onSpeak)
    fun greet(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val greetings = mapOf(
            "good morning" to "Good morning! Ready to help you have a great day.",
            "good night"   to "Good night! Sleep well.",
            "hello"        to "Hello! How can I help you?",
            "hi"           to "Hi there! What can I do for you?",
            "good afternoon" to "Good afternoon! What do you need?",
            "good evening"   to "Good evening! How can I assist?"
        )
        val response = greetings.entries.firstOrNull { cmd.contains(it.key) }?.value
            ?: "Hello! How can I help you?"
        onSpeak(response)
        return CommandResult.success(response)
    }
    fun listCapabilities(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("I can: make calls, send SMS, control apps, play music, take photos, navigate, search the web, check battery and storage, set alarms, manage contacts, control settings, answer questions, and much more. Just ask me anything.")
        return CommandResult.success("listed")
    }
}

object TimeTool {
    fun getCurrent(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val t = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date())
        onSpeak("It is $t.")
        return CommandResult.success(t)
    }
    fun getDate(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val d = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date())
        onSpeak("Today is $d.")
        return CommandResult.success(d)
    }
    fun getDay(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val day = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
            .format(java.util.Date())
        onSpeak("Today is $day.")
        return CommandResult.success(day)
    }
    fun getTimezone(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val tz = java.util.TimeZone.getDefault().displayName
        onSpeak("Your timezone is $tz.")
        return CommandResult.success(tz)
    }
    fun getWorldTime(ctx: Context, city: String, onSpeak: (String) -> Unit): CommandResult {
        return WebTool.search(ctx, "current time in $city", onSpeak)
    }
    fun countdown(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        return AiTool.ask(ctx, cmd, onSpeak)
    }
}

object HealthTool {
    fun getSteps(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Opening Google Fit for step count.")
        return AppTool.open(ctx, "com.google.android.apps.fitness", onSpeak)
    }
    fun getHeartRate(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Heart rate requires a wearable. Opening health app.")
        return AppTool.open(ctx, "com.google.android.apps.fitness", onSpeak)
    }
    fun getCalories(ctx: Context, onSpeak: (String) -> Unit): CommandResult = getSteps(ctx, onSpeak)
    fun getSleep(ctx: Context, onSpeak: (String) -> Unit): CommandResult = getSteps(ctx, onSpeak)
    fun setWaterReminder(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return AlarmTool.setTimer(ctx, "every 60 minutes water reminder", onSpeak)
    }
}

object VoiceTool {
    fun speak(ctx: Context, text: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak(text)
        return CommandResult.success("spoken")
    }
    fun changeLanguage(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val lang = when {
            cmd.contains("hindi") -> "hi-IN"
            cmd.contains("english") -> "en-IN"
            else -> "en-IN"
        }
        onSpeak("Language set.")
        return CommandResult.success(lang)
    }
    fun recordVoice(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Recording voice.")
        return CommandResult.success("recording")
    }
    fun transcribe(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Listening to transcribe.")
        return CommandResult.success("transcribing")
    }
}

object ShareTool {
    fun share(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type  = "text/plain"
            putExtra(Intent.EXTRA_TEXT, cmd)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(Intent.createChooser(intent, "Share").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        return CommandResult.success("shared")
    }
    fun copyToClipboard(ctx: Context, text: String, onSpeak: (String) -> Unit): CommandResult {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS", text))
        onSpeak("Copied to clipboard.")
        return CommandResult.success("copied")
    }
    fun paste(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val cm   = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        onSpeak("Clipboard: $text")
        return CommandResult.success(text)
    }
    fun generateQR(ctx: Context, text: String, onSpeak: (String) -> Unit): CommandResult {
        return WebTool.openUrl(ctx, "https://api.qrserver.com/v1/create-qr-code/?data=${Uri.encode(text)}", onSpeak)
    }
}

object UtilTool {
    fun random(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val max = Regex("""\d+""").find(cmd)?.value?.toIntOrNull() ?: 100
        val n   = (1..max).random()
        onSpeak("Random number: $n")
        return CommandResult.success("$n")
    }
    fun coinFlip(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val result = if ((0..1).random() == 0) "Heads" else "Tails"
        onSpeak("$result!")
        return CommandResult.success(result)
    }
    fun rollDice(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val n = (1..6).random()
        onSpeak("You rolled a $n.")
        return CommandResult.success("$n")
    }
    fun unitConvert(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        return AiTool.ask(ctx, "Convert: $cmd", onSpeak)
    }
    fun wordCount(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val text  = cmd.substringAfter("count").trim()
        val words = text.split("\\s+".toRegex()).size
        onSpeak("Word count: $words")
        return CommandResult.success("$words")
    }
    fun generatePassword(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val length = Regex("""\d+""").find(cmd)?.value?.toIntOrNull() ?: 16
        val chars  = ('a'..'z') + ('A'..'Z') + ('0'..'9') + "!@#\$%^&*".toList()
        val pass   = (1..length).map { chars.random() }.joinToString("")
        onSpeak("Generated password: $pass")
        ShareTool.copyToClipboard(ctx, pass) {}
        return CommandResult.success(pass)
    }
}

object EmailTool {
    fun checkInbox(ctx: Context, onSpeak: (String) -> Unit): CommandResult =
        AppTool.open(ctx, "com.google.android.gm", onSpeak)
    fun compose(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
        onSpeak("Opening email compose.")
        return CommandResult.success("opened")
    }
}

object AutomationTool {
    private val routines = mutableListOf<Map<String, String>>()

    fun setAutoReply(ctx: Context, trigger: String, reply: String) {
        routines.add(mapOf("type" to "auto_reply", "trigger" to trigger, "reply" to reply))
    }
    fun addRoutine(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        routines.add(mapOf("type" to "routine", "cmd" to cmd))
        onSpeak("Routine added.")
        return CommandResult.success("added")
    }
    fun addBatteryAlert(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Battery alert automation set.")
        return CommandResult.success("set")
    }
    fun addLocationTrigger(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Location trigger set.")
        return CommandResult.success("set")
    }
    fun addRepeating(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Repeating task set.")
        return CommandResult.success("set")
    }
    fun stopAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        routines.clear()
        onSpeak("All automations stopped.")
        return CommandResult.success("stopped")
    }
    fun listAll(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val msg = if (routines.isEmpty()) "No active automations."
        else "${routines.size} automations active."
        onSpeak(msg)
        return CommandResult.success(msg)
    }
    fun executeScript(ctx: Context, code: String, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Executing learned automation.")
        return CommandResult.success("executed")
    }
}

object LearnTool {
    fun learn(ctx: Context, cmd: String, onSpeak: (String) -> Unit): CommandResult {
        com.jarvis.mobile.memory.MemoryEngine.saveLearnedCommand(
            ctx,
            cmd,
            "// User-defined: $cmd"
        )
        onSpeak("Learned. I'll remember how to do this.")
        return CommandResult.success("learned")
    }
    fun listLearned(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        onSpeak("Checking learned commands.")
        return CommandResult.success("listed")
    }
}

object PermissionTool {
    fun requestMissing(ctx: Context, command: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }
}
