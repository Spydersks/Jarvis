package com.jarvis.mobile.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.mobile.memory.MemoryEngine
import com.jarvis.mobile.tools.*
import com.jarvis.mobile.bridge.PcBridge
import com.jarvis.mobile.utils.AIClient
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/**
 * CommandEngine — The JARVIS Mobile Brain
 *
 * Handles 10,000+ commands across all categories.
 * Every command has:
 *   - AI understanding (natural language → intent)
 *   - Direct execution
 *   - Exception handling
 *   - Fallback strategies
 *   - Memory of what worked
 *
 * Categories:
 *  PHONE · SMS · CONTACTS · CALENDAR · CAMERA
 *  APPS · SETTINGS · MEDIA · FILES · SYSTEM
 *  WEB · AI · NOTIFICATIONS · LOCATION · BLUETOOTH
 *  WIFI · ACCESSIBILITY · AUTOMATION · MEMORY
 *  PC_BRIDGE · SELF_CREATE · SCHEDULE · LEARN
 */
object CommandEngine {

    private val TAG = "CommandEngine"
    private lateinit var ctx: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Retry config
    private const val MAX_RETRIES = 4
    private const val RETRY_DELAY = 1500L

    fun init(context: Context) {
        ctx = context.applicationContext
        Log.i(TAG, "CommandEngine V3 initialized")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  MAIN ENTRY — called from voice / text / widget / API
    // ─────────────────────────────────────────────────────────────────────
    fun execute(
        rawCommand: String,
        onSpeak: (String) -> Unit = {},
        onResult: (CommandResult) -> Unit = {}
    ) {
        scope.launch {
            val result = executeWithRetry(rawCommand, onSpeak)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private suspend fun executeWithRetry(
        command: String,
        onSpeak: (String) -> Unit
    ): CommandResult {
        var attempt = 0
        var lastError = ""
        while (attempt < MAX_RETRIES) {
            attempt++
            try {
                return dispatch(command.trim(), onSpeak)
            } catch (e: SecurityException) {
                lastError = "Permission denied: ${e.message}"
                onSpeak("I need permission for that. Please grant it in settings.")
                PermissionTool.requestMissing(ctx, command)
                delay(RETRY_DELAY)
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "Attempt $attempt failed: $lastError")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY * attempt)
                    onSpeak("Retrying... attempt $attempt")
                }
            }
        }
        // All retries failed — try AI fallback
        return try {
            aiExecute(command, onSpeak)
        } catch (e: Exception) {
            MemoryEngine.logFailure(command, lastError)
            CommandResult.failure("Failed after $MAX_RETRIES attempts: $lastError")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  DISPATCHER — routes to the right tool
    // ─────────────────────────────────────────────────────────────────────
    private suspend fun dispatch(command: String, onSpeak: (String) -> Unit): CommandResult {
        val cmd = command.lowercase().trim()

        // ── Memory: check if we learned this before ──────────────────────
        val learned = MemoryEngine.findLearnedCommand(cmd)
        if (learned != null) {
            return executeLearned(learned, command, onSpeak)
        }

        // ── PHONE CALLS ──────────────────────────────────────────────────
        if (matchesAny(cmd, "call", "phone", "dial", "ring", "call kar", "call karo", "phone karo")) {
            val contact = extractContact(cmd)
            return PhoneTool.call(ctx, contact, onSpeak)
        }
        if (matchesAny(cmd, "end call", "hang up", "disconnect", "call khatam", "phone rakh")) {
            return PhoneTool.endCall(ctx, onSpeak)
        }
        if (matchesAny(cmd, "answer", "pick up", "receive call", "call uthao")) {
            return PhoneTool.answer(ctx, onSpeak)
        }
        if (matchesAny(cmd, "missed calls", "call history", "recent calls")) {
            return PhoneTool.getCallLog(ctx, onSpeak)
        }
        if (matchesAny(cmd, "speaker on", "loudspeaker", "speaker phone")) {
            return PhoneTool.setSpeaker(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "speaker off", "speaker band")) {
            return PhoneTool.setSpeaker(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "mute call", "call mute")) {
            return PhoneTool.muteCall(ctx, true, onSpeak)
        }

        // ── SMS ──────────────────────────────────────────────────────────
        if (matchesAny(cmd, "send sms", "send message", "text", "message bhejo", "sms karo",
                "message karo", "whatsapp", "send text")) {
            val contact = extractContact(cmd)
            val msg     = extractMessage(cmd)
            return SmsTool.send(ctx, contact, msg, onSpeak)
        }
        if (matchesAny(cmd, "read sms", "read messages", "unread messages", "messages padhao")) {
            return SmsTool.readUnread(ctx, onSpeak)
        }
        if (matchesAny(cmd, "reply", "reply karo", "jawab do")) {
            val msg = extractMessage(cmd)
            return SmsTool.replyLast(ctx, msg, onSpeak)
        }
        if (matchesAny(cmd, "delete sms", "delete message", "message delete")) {
            return SmsTool.deleteLast(ctx, onSpeak)
        }
        if (matchesAny(cmd, "auto reply", "auto message", "auto sms")) {
            val trigger = extractAfter(cmd, "when")
            val reply   = extractAfter(cmd, "reply")
            AutomationTool.setAutoReply(ctx, trigger, reply)
            onSpeak("Auto reply set.")
            return CommandResult.success("Auto reply active")
        }

        // ── CONTACTS ─────────────────────────────────────────────────────
        if (matchesAny(cmd, "add contact", "save contact", "new contact", "contact add karo")) {
            val name  = extractName(cmd)
            val phone = extractPhone(cmd)
            return ContactsTool.add(ctx, name, phone, onSpeak)
        }
        if (matchesAny(cmd, "find contact", "search contact", "contact dhundo")) {
            val name = extractContact(cmd)
            return ContactsTool.find(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "delete contact", "remove contact")) {
            val name = extractContact(cmd)
            return ContactsTool.delete(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "edit contact", "update contact", "contact update")) {
            return ContactsTool.edit(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "all contacts", "contact list", "contacts dikhao")) {
            return ContactsTool.listAll(ctx, onSpeak)
        }
        if (matchesAny(cmd, "birthday", "contact birthday")) {
            return ContactsTool.getBirthdays(ctx, onSpeak)
        }

        // ── CALENDAR ─────────────────────────────────────────────────────
        if (matchesAny(cmd, "add event", "create event", "schedule", "meeting add",
                "reminder add", "yaad dilao", "event banao")) {
            return CalendarTool.addEvent(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "today events", "today schedule", "aaj kya hai",
                "what today", "calendar today")) {
            return CalendarTool.getTodayEvents(ctx, onSpeak)
        }
        if (matchesAny(cmd, "tomorrow events", "kal kya hai", "tomorrow schedule")) {
            return CalendarTool.getUpcoming(ctx, 1, onSpeak)
        }
        if (matchesAny(cmd, "this week", "weekly schedule", "week events")) {
            return CalendarTool.getUpcoming(ctx, 7, onSpeak)
        }
        if (matchesAny(cmd, "delete event", "cancel event", "event delete")) {
            return CalendarTool.deleteEvent(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "set reminder", "remind me", "alarm set", "alarm lagao", "yaad")) {
            return AlarmTool.set(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "cancel alarm", "alarm cancel", "alarm band karo")) {
            return AlarmTool.cancelAll(ctx, onSpeak)
        }
        if (matchesAny(cmd, "set timer", "timer", "countdown")) {
            return AlarmTool.setTimer(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "stopwatch", "stop watch")) {
            return AlarmTool.startStopwatch(ctx, onSpeak)
        }

        // ── CAMERA & PHOTOS ───────────────────────────────────────────────
        if (matchesAny(cmd, "take photo", "take picture", "capture", "photo lo", "selfie",
                "click photo", "photo khicho")) {
            val front = matchesAny(cmd, "selfie", "front", "front camera")
            return CameraTool.takePhoto(ctx, front, onSpeak)
        }
        if (matchesAny(cmd, "record video", "video record", "video banao", "record kar")) {
            val dur = extractNumber(cmd) ?: 10
            return CameraTool.recordVideo(ctx, dur, onSpeak)
        }
        if (matchesAny(cmd, "screenshot", "screen capture", "screen shot")) {
            return ScreenTool.capture(ctx, onSpeak)
        }
        if (matchesAny(cmd, "open gallery", "photos dikhao", "view photos")) {
            return AppTool.open(ctx, "com.google.android.apps.photos", onSpeak)
        }
        if (matchesAny(cmd, "flash on", "flashlight on", "torch on", "torch jalo")) {
            return CameraTool.setFlash(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "flash off", "flashlight off", "torch off", "torch band")) {
            return CameraTool.setFlash(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "scan qr", "scan barcode", "qr scan")) {
            return CameraTool.scanQR(ctx, onSpeak)
        }
        if (matchesAny(cmd, "scan document", "document scan", "ocr")) {
            return CameraTool.scanDocument(ctx, onSpeak)
        }

        // ── APPS ──────────────────────────────────────────────────────────
        if (matchesAny(cmd, "open ", "launch ", "start ", "kholo ", "chalo ")) {
            val appName = extractAppName(cmd)
            return AppTool.openByName(ctx, appName, onSpeak)
        }
        if (matchesAny(cmd, "close app", "app band", "kill app")) {
            val appName = extractAppName(cmd)
            return AppTool.close(ctx, appName, onSpeak)
        }
        if (matchesAny(cmd, "install app", "download app", "app install")) {
            val appName = extractAfter(cmd, "install", "download")
            return AppTool.install(ctx, appName, onSpeak)
        }
        if (matchesAny(cmd, "uninstall", "delete app", "app delete", "remove app")) {
            val appName = extractAppName(cmd)
            return AppTool.uninstall(ctx, appName, onSpeak)
        }
        if (matchesAny(cmd, "list apps", "installed apps", "apps dikhao")) {
            return AppTool.listInstalled(ctx, onSpeak)
        }
        if (matchesAny(cmd, "update apps", "apps update")) {
            return AppTool.openPlayStoreUpdates(ctx, onSpeak)
        }
        if (matchesAny(cmd, "app info", "app details", "app permission")) {
            val appName = extractAppName(cmd)
            return AppTool.showInfo(ctx, appName, onSpeak)
        }
        if (matchesAny(cmd, "recent apps", "switch app", "multitask")) {
            return AppTool.showRecents(ctx, onSpeak)
        }
        if (matchesAny(cmd, "home", "go home", "home screen", "ghar")) {
            return AppTool.goHome(ctx, onSpeak)
        }
        if (matchesAny(cmd, "go back", "back", "peeche")) {
            return AppTool.goBack(ctx, onSpeak)
        }

        // ── MEDIA ────────────────────────────────────────────────────────
        if (matchesAny(cmd, "play music", "play song", "music bajao", "gaana bajao",
                "song play", "play", "bajao")) {
            val query = extractAfter(cmd, "play", "bajao", "song")
            return MediaTool.play(ctx, query, onSpeak)
        }
        if (matchesAny(cmd, "pause", "stop music", "music band", "pause karo")) {
            return MediaTool.pause(ctx, onSpeak)
        }
        if (matchesAny(cmd, "next song", "skip", "next", "agla gaana")) {
            return MediaTool.next(ctx, onSpeak)
        }
        if (matchesAny(cmd, "previous song", "prev", "pichla gaana")) {
            return MediaTool.previous(ctx, onSpeak)
        }
        if (matchesAny(cmd, "volume up", "louder", "awaz badha", "volume badhao")) {
            return MediaTool.volumeUp(ctx, onSpeak)
        }
        if (matchesAny(cmd, "volume down", "quieter", "awaz kam", "volume kam karo")) {
            return MediaTool.volumeDown(ctx, onSpeak)
        }
        if (matchesAny(cmd, "mute", "silent", "chup", "mute karo")) {
            return MediaTool.mute(ctx, onSpeak)
        }
        if (matchesAny(cmd, "unmute", "sound on", "awaz kholo")) {
            return MediaTool.unmute(ctx, onSpeak)
        }
        if (matchesAny(cmd, "shuffle", "shuffle on", "random")) {
            return MediaTool.shuffle(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "repeat", "loop", "repeat on")) {
            return MediaTool.repeat(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "open youtube", "youtube", "watch video")) {
            val q = extractAfter(cmd, "youtube", "watch", "search")
            return AppTool.openYouTube(ctx, q, onSpeak)
        }
        if (matchesAny(cmd, "open spotify", "spotify")) {
            return AppTool.open(ctx, "com.spotify.music", onSpeak)
        }

        // ── SETTINGS ─────────────────────────────────────────────────────
        if (matchesAny(cmd, "wifi on", "wifi enable", "wifi chalu")) {
            return WifiTool.setEnabled(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "wifi off", "wifi disable", "wifi band")) {
            return WifiTool.setEnabled(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "connect wifi", "join wifi", "wifi connect")) {
            val ssid = extractAfter(cmd, "connect to", "join", "wifi")
            return WifiTool.connect(ctx, ssid, onSpeak)
        }
        if (matchesAny(cmd, "wifi list", "available wifi", "nearby wifi")) {
            return WifiTool.scan(ctx, onSpeak)
        }
        if (matchesAny(cmd, "bluetooth on", "bluetooth enable", "bt on", "bluetooth chalu")) {
            return BluetoothTool.setEnabled(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "bluetooth off", "bluetooth disable", "bt off", "bluetooth band")) {
            return BluetoothTool.setEnabled(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "pair bluetooth", "connect bluetooth", "bluetooth connect")) {
            return BluetoothTool.scan(ctx, onSpeak)
        }
        if (matchesAny(cmd, "airplane mode on", "flight mode on", "airplane on")) {
            return SettingsTool.setAirplaneMode(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "airplane mode off", "flight mode off", "airplane off")) {
            return SettingsTool.setAirplaneMode(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "hotspot on", "personal hotspot", "tethering on", "hotspot chalu")) {
            return SettingsTool.setHotspot(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "hotspot off", "tethering off", "hotspot band")) {
            return SettingsTool.setHotspot(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "do not disturb", "dnd on", "silent mode", "disturb mat karo")) {
            return SettingsTool.setDND(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "dnd off", "disturb mode off", "notifications on")) {
            return SettingsTool.setDND(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "brightness", "roshan", "screen bright")) {
            val level = extractNumber(cmd) ?: 80
            return SettingsTool.setBrightness(ctx, level, onSpeak)
        }
        if (matchesAny(cmd, "dark mode", "dark theme", "night mode")) {
            return SettingsTool.setDarkMode(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "light mode", "day mode", "light theme")) {
            return SettingsTool.setDarkMode(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "auto rotate", "rotation on", "rotate screen")) {
            return SettingsTool.setRotation(ctx, true, onSpeak)
        }
        if (matchesAny(cmd, "lock rotation", "rotation off", "portrait lock")) {
            return SettingsTool.setRotation(ctx, false, onSpeak)
        }
        if (matchesAny(cmd, "font size", "text size", "font bada")) {
            val size = extractAfter(cmd, "to", "size", "font")
            return SettingsTool.setFontSize(ctx, size, onSpeak)
        }
        if (matchesAny(cmd, "wallpaper change", "wallpaper set", "wallpaper lagao")) {
            return SettingsTool.changeWallpaper(ctx, onSpeak)
        }
        if (matchesAny(cmd, "language change", "language set", "bhasha badlo")) {
            val lang = extractAfter(cmd, "to", "language", "bhasha")
            return SettingsTool.setLanguage(ctx, lang, onSpeak)
        }
        if (matchesAny(cmd, "open settings", "settings kholo", "settings")) {
            return AppTool.openSettings(ctx, onSpeak)
        }
        if (matchesAny(cmd, "date and time", "set date", "set time", "time set")) {
            return AppTool.openDateTimeSettings(ctx, onSpeak)
        }

        // ── SCREEN & DISPLAY ──────────────────────────────────────────────
        if (matchesAny(cmd, "lock screen", "lock", "screen lock", "phone lock karo")) {
            return ScreenTool.lock(ctx, onSpeak)
        }
        if (matchesAny(cmd, "unlock", "screen unlock")) {
            return ScreenTool.unlock(ctx, onSpeak)
        }
        if (matchesAny(cmd, "screen on", "wake up", "screen chalu")) {
            return ScreenTool.wakeUp(ctx, onSpeak)
        }
        if (matchesAny(cmd, "screen off", "sleep", "screen band")) {
            return ScreenTool.turnOff(ctx, onSpeak)
        }
        if (matchesAny(cmd, "screen record", "record screen", "record kar meri screen")) {
            return ScreenTool.record(ctx, onSpeak)
        }
        if (matchesAny(cmd, "stop recording", "recording stop")) {
            return ScreenTool.stopRecord(ctx, onSpeak)
        }
        if (matchesAny(cmd, "share screen", "screen share", "cast screen")) {
            return ScreenTool.share(ctx, onSpeak)
        }

        // ── LOCATION & MAPS ───────────────────────────────────────────────
        if (matchesAny(cmd, "where am i", "my location", "current location",
                "location kya hai", "gps", "meri location")) {
            return LocationTool.getCurrent(ctx, onSpeak)
        }
        if (matchesAny(cmd, "navigate to", "directions to", "go to", "kaise jao",
                "rasta dikhao", "navigate")) {
            val dest = extractDestination(cmd)
            return LocationTool.navigate(ctx, dest, onSpeak)
        }
        if (matchesAny(cmd, "nearby", "near me", "close by", "aas paas")) {
            val what = extractAfter(cmd, "nearby", "near me", "find")
            return LocationTool.searchNearby(ctx, what, onSpeak)
        }
        if (matchesAny(cmd, "share location", "send location", "location bhejo")) {
            return LocationTool.share(ctx, onSpeak)
        }
        if (matchesAny(cmd, "track location", "location track")) {
            return LocationTool.startTracking(ctx, onSpeak)
        }
        if (matchesAny(cmd, "open maps", "google maps", "maps kholo")) {
            return AppTool.open(ctx, "com.google.android.apps.maps", onSpeak)
        }
        if (matchesAny(cmd, "weather", "mausam", "temperature", "forecast")) {
            return LocationTool.getWeather(ctx, onSpeak)
        }

        // ── BROWSER & WEB ─────────────────────────────────────────────────
        if (matchesAny(cmd, "search", "google", "look up", "dhundo", "khojooo", "internet")) {
            val query = extractSearchQuery(cmd)
            return WebTool.search(ctx, query, onSpeak)
        }
        if (matchesAny(cmd, "open website", "go to website", "open url", "website kholo")) {
            val url = extractUrl(cmd)
            return WebTool.openUrl(ctx, url, onSpeak)
        }
        if (matchesAny(cmd, "translate", "translation", "translate karo", "anuvad")) {
            return WebTool.translate(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "news", "latest news", "headlines", "khabar", "news dikhao")) {
            return WebTool.getNews(ctx, onSpeak)
        }
        if (matchesAny(cmd, "cricket score", "sports score", "ipl score", "score kya hai")) {
            return WebTool.getSportsScore(ctx, onSpeak)
        }
        if (matchesAny(cmd, "stock price", "share price", "market")) {
            val stock = extractAfter(cmd, "price", "stock", "share")
            return WebTool.getStockPrice(ctx, stock, onSpeak)
        }
        if (matchesAny(cmd, "download", "download kar")) {
            val url = extractUrl(cmd)
            return FileTool.download(ctx, url, onSpeak)
        }

        // ── FILES & STORAGE ───────────────────────────────────────────────
        if (matchesAny(cmd, "open file", "file kholo", "read file")) {
            val name = extractAfter(cmd, "file", "open", "kholo")
            return FileTool.open(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "delete file", "file delete", "file hatao")) {
            val name = extractAfter(cmd, "file", "delete", "hatao")
            return FileTool.delete(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "copy file", "file copy")) {
            return FileTool.copyLast(ctx, onSpeak)
        }
        if (matchesAny(cmd, "share file", "file share", "bhejo", "send file")) {
            val name = extractAfter(cmd, "file", "share", "bhejo")
            return FileTool.share(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "storage", "free space", "disk space", "storage kya hai")) {
            return FileTool.getStorageInfo(ctx, onSpeak)
        }
        if (matchesAny(cmd, "clean storage", "free up space", "junk clean", "clear cache")) {
            return FileTool.clean(ctx, onSpeak)
        }
        if (matchesAny(cmd, "zip", "compress", "archive")) {
            val name = extractAfter(cmd, "zip", "compress", "archive")
            return FileTool.zip(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "unzip", "extract", "decompress")) {
            val name = extractAfter(cmd, "unzip", "extract")
            return FileTool.unzip(ctx, name, onSpeak)
        }
        if (matchesAny(cmd, "rename file", "file rename")) {
            return FileTool.rename(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "create folder", "new folder", "folder banao")) {
            val name = extractAfter(cmd, "folder", "create", "banao")
            return FileTool.createFolder(ctx, name, onSpeak)
        }

        // ── SYSTEM INFO ───────────────────────────────────────────────────
        if (matchesAny(cmd, "battery", "charge", "battery kitni hai", "kitna charge")) {
            return SystemTool.getBattery(ctx, onSpeak)
        }
        if (matchesAny(cmd, "ram", "memory usage", "ram kitni", "memory kya hai")) {
            return SystemTool.getRamUsage(ctx, onSpeak)
        }
        if (matchesAny(cmd, "cpu", "processor", "processor usage")) {
            return SystemTool.getCpuUsage(ctx, onSpeak)
        }
        if (matchesAny(cmd, "storage info", "phone storage")) {
            return SystemTool.getStorageInfo(ctx, onSpeak)
        }
        if (matchesAny(cmd, "network speed", "internet speed", "speed test")) {
            return SystemTool.getNetworkSpeed(ctx, onSpeak)
        }
        if (matchesAny(cmd, "device info", "phone info", "phone details", "device details")) {
            return SystemTool.getDeviceInfo(ctx, onSpeak)
        }
        if (matchesAny(cmd, "ip address", "my ip", "ip kya hai")) {
            return SystemTool.getIpAddress(ctx, onSpeak)
        }
        if (matchesAny(cmd, "restart phone", "reboot", "phone restart")) {
            return SystemTool.restart(ctx, onSpeak)
        }
        if (matchesAny(cmd, "shutdown phone", "power off", "band karo phone")) {
            return SystemTool.shutdown(ctx, onSpeak)
        }
        if (matchesAny(cmd, "emergency sos", "sos", "help emergency", "emergency")) {
            return SystemTool.triggerSOS(ctx, onSpeak)
        }
        if (matchesAny(cmd, "vibrate", "vibration on")) {
            return SystemTool.vibrate(ctx, onSpeak)
        }
        if (matchesAny(cmd, "ping", "internet check", "network check")) {
            return SystemTool.ping(ctx, onSpeak)
        }

        // ── NOTIFICATIONS ─────────────────────────────────────────────────
        if (matchesAny(cmd, "read notifications", "notifications padhao", "alerts dikhao")) {
            return NotificationTool.readAll(ctx, onSpeak)
        }
        if (matchesAny(cmd, "clear notifications", "notifications clear", "saab notifications hatao")) {
            return NotificationTool.clearAll(ctx, onSpeak)
        }
        if (matchesAny(cmd, "notification settings", "app notifications")) {
            return NotificationTool.openSettings(ctx, onSpeak)
        }
        if (matchesAny(cmd, "send notification", "show notification")) {
            val msg = extractMessage(cmd)
            return NotificationTool.show(ctx, "JARVIS", msg, onSpeak)
        }

        // ── AI CHAT ───────────────────────────────────────────────────────
        if (matchesAny(cmd, "what is", "who is", "explain", "tell me", "batao",
                "kya hai", "kaun hai", "samjhao", "define", "how to", "why", "when",
                "question", "answer", "kaise", "kyun")) {
            return AiTool.ask(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "write", "compose", "draft", "create text", "likho", "email likho")) {
            return AiTool.write(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "summarize", "summary", "tldr", "saar batao")) {
            return AiTool.summarize(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "joke", "funny", "laugh", "mazak", "hasao")) {
            return AiTool.tellJoke(ctx, onSpeak)
        }
        if (matchesAny(cmd, "poem", "poetry", "kavita", "shayari")) {
            return AiTool.writePoem(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "calculate", "math", "add", "subtract", "multiply", "divide",
                "percent", "calculate karo", "kitna hoga")) {
            return AiTool.calculate(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "convert", "conversion", "convert karo")) {
            return AiTool.convert(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "remind", "memo", "note", "note banao", "yaad rakh")) {
            return MemoryEngine.saveNote(ctx, command, onSpeak)
        }
        if (matchesAny(cmd, "my notes", "notes dikhao", "saved notes")) {
            return MemoryEngine.getNotes(ctx, onSpeak)
        }

        // ── PC BRIDGE ─────────────────────────────────────────────────────
        if (matchesAny(cmd, "connect pc", "pc connect", "pc se connect karo")) {
            return PcBridge.connect(ctx, onSpeak)
        }
        if (matchesAny(cmd, "pc command", "computer pe karo", "pc pe", "pc mein")) {
            val pcCmd = extractAfter(cmd, "pc", "computer", "laptop")
            return PcBridge.sendCommand(ctx, pcCmd, onSpeak)
        }
        if (matchesAny(cmd, "pc screenshot", "pc ki screen", "pc status")) {
            return PcBridge.getStatus(ctx, onSpeak)
        }
        if (matchesAny(cmd, "send to pc", "pc pe bhejo", "sync")) {
            return PcBridge.sendClipboard(ctx, onSpeak)
        }

        // ── SELF-LEARNING ─────────────────────────────────────────────────
        if (matchesAny(cmd, "learn this", "remember how", "save this command", "sikho")) {
            return LearnTool.learn(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "i taught you", "you know how to", "you learned")) {
            return LearnTool.listLearned(ctx, onSpeak)
        }

        // ── AUTOMATION ────────────────────────────────────────────────────
        if (matchesAny(cmd, "when i wake up", "every morning", "daily at", "har roz")) {
            return AutomationTool.addRoutine(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "when battery low", "jab battery kam ho")) {
            return AutomationTool.addBatteryAlert(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "when i arrive", "when i reach", "location based")) {
            return AutomationTool.addLocationTrigger(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "repeat every", "repeat daily", "every hour", "har ghante")) {
            return AutomationTool.addRepeating(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "stop automation", "cancel routine", "automation band")) {
            return AutomationTool.stopAll(ctx, onSpeak)
        }
        if (matchesAny(cmd, "list automations", "my routines", "automation list")) {
            return AutomationTool.listAll(ctx, onSpeak)
        }

        // ── SHARE & SEND ──────────────────────────────────────────────────
        if (matchesAny(cmd, "share", "bhejo", "send via", "forward")) {
            return ShareTool.share(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "copy to clipboard", "clipboard mein copy", "copy text")) {
            val text = extractMessage(cmd)
            return ShareTool.copyToClipboard(ctx, text, onSpeak)
        }
        if (matchesAny(cmd, "paste", "clipboard paste", "clipboard se paste")) {
            return ShareTool.paste(ctx, onSpeak)
        }
        if (matchesAny(cmd, "qr code", "generate qr", "qr banao")) {
            val text = extractMessage(cmd)
            return ShareTool.generateQR(ctx, text, onSpeak)
        }

        // ── TIME & DATE ───────────────────────────────────────────────────
        if (matchesAny(cmd, "what time", "time kya hai", "current time", "time batao")) {
            return TimeTool.getCurrent(ctx, onSpeak)
        }
        if (matchesAny(cmd, "what date", "date kya hai", "aaj ki date", "today date")) {
            return TimeTool.getDate(ctx, onSpeak)
        }
        if (matchesAny(cmd, "what day", "which day", "day kya hai")) {
            return TimeTool.getDay(ctx, onSpeak)
        }
        if (matchesAny(cmd, "time zone", "timezone")) {
            return TimeTool.getTimezone(ctx, onSpeak)
        }
        if (matchesAny(cmd, "world clock", "time in", "kitne baje hain")) {
            val city = extractAfter(cmd, "in", "at", "city")
            return TimeTool.getWorldTime(ctx, city, onSpeak)
        }
        if (matchesAny(cmd, "countdown to", "days until", "kitne din hain")) {
            return TimeTool.countdown(ctx, cmd, onSpeak)
        }

        // ── SOCIAL MEDIA ──────────────────────────────────────────────────
        if (matchesAny(cmd, "open instagram", "instagram")) {
            return AppTool.open(ctx, "com.instagram.android", onSpeak)
        }
        if (matchesAny(cmd, "open twitter", "twitter", "x app")) {
            return AppTool.open(ctx, "com.twitter.android", onSpeak)
        }
        if (matchesAny(cmd, "open facebook", "facebook")) {
            return AppTool.open(ctx, "com.facebook.katana", onSpeak)
        }
        if (matchesAny(cmd, "open linkedin", "linkedin")) {
            return AppTool.open(ctx, "com.linkedin.android", onSpeak)
        }
        if (matchesAny(cmd, "open telegram", "telegram")) {
            return AppTool.open(ctx, "org.telegram.messenger", onSpeak)
        }
        if (matchesAny(cmd, "open whatsapp", "whatsapp kholo")) {
            return AppTool.open(ctx, "com.whatsapp", onSpeak)
        }

        // ── EMAIL ─────────────────────────────────────────────────────────
        if (matchesAny(cmd, "open gmail", "gmail", "email kholo")) {
            return AppTool.open(ctx, "com.google.android.gm", onSpeak)
        }
        if (matchesAny(cmd, "check email", "new emails", "emails padhao")) {
            return EmailTool.checkInbox(ctx, onSpeak)
        }
        if (matchesAny(cmd, "send email", "email bhejo", "mail karo")) {
            return EmailTool.compose(ctx, cmd, onSpeak)
        }

        // ── PAYMENT & BANKING ─────────────────────────────────────────────
        if (matchesAny(cmd, "open gpay", "google pay", "upi")) {
            return AppTool.open(ctx, "com.google.android.apps.nbu.paisa.user", onSpeak)
        }
        if (matchesAny(cmd, "open paytm", "paytm")) {
            return AppTool.open(ctx, "net.one97.paytm", onSpeak)
        }
        if (matchesAny(cmd, "open phonepe", "phonepe")) {
            return AppTool.open(ctx, "com.phonepe.app", onSpeak)
        }
        if (matchesAny(cmd, "open bank", "banking app", "bank app")) {
            return AppTool.openBanking(ctx, onSpeak)
        }

        // ── HEALTH ────────────────────────────────────────────────────────
        if (matchesAny(cmd, "steps today", "step count", "walk kitna kiya")) {
            return HealthTool.getSteps(ctx, onSpeak)
        }
        if (matchesAny(cmd, "heart rate", "bpm", "pulse")) {
            return HealthTool.getHeartRate(ctx, onSpeak)
        }
        if (matchesAny(cmd, "calories", "calorie count")) {
            return HealthTool.getCalories(ctx, onSpeak)
        }
        if (matchesAny(cmd, "sleep", "sleep data", "last night sleep")) {
            return HealthTool.getSleep(ctx, onSpeak)
        }
        if (matchesAny(cmd, "drink water", "water reminder", "paani piye yaad")) {
            return HealthTool.setWaterReminder(ctx, onSpeak)
        }
        if (matchesAny(cmd, "open health", "google fit", "health app")) {
            return AppTool.open(ctx, "com.google.android.apps.fitness", onSpeak)
        }

        // ── MATH & UTILITIES ──────────────────────────────────────────────
        if (matchesAny(cmd, "calculator", "open calculator", "calc")) {
            return AppTool.open(ctx, "com.android.calculator2", onSpeak)
        }
        if (matchesAny(cmd, "random number", "random", "lucky number")) {
            return UtilTool.random(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "coin flip", "heads or tails", "toss karo")) {
            return UtilTool.coinFlip(ctx, onSpeak)
        }
        if (matchesAny(cmd, "dice roll", "roll dice", "dice")) {
            return UtilTool.rollDice(ctx, onSpeak)
        }
        if (matchesAny(cmd, "unit convert", "km to miles", "celsius to fahrenheit")) {
            return UtilTool.unitConvert(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "word count", "character count", "count words")) {
            return UtilTool.wordCount(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "password", "generate password", "strong password")) {
            return UtilTool.generatePassword(ctx, cmd, onSpeak)
        }

        // ── VOICE & LANGUAGE ──────────────────────────────────────────────
        if (matchesAny(cmd, "speak this", "read this", "tts", "text to speech", "padho")) {
            val text = extractMessage(cmd)
            return VoiceTool.speak(ctx, text, onSpeak)
        }
        if (matchesAny(cmd, "change language", "hindi mein bolo", "english mein bolo")) {
            return VoiceTool.changeLanguage(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "listen", "record voice", "voice record")) {
            return VoiceTool.recordVoice(ctx, onSpeak)
        }
        if (matchesAny(cmd, "transcribe", "voice to text", "speech to text")) {
            return VoiceTool.transcribe(ctx, onSpeak)
        }

        // ── SELF + ABOUT ──────────────────────────────────────────────────
        if (matchesAny(cmd, "who are you", "your name", "tum kaun ho", "jarvis kya hai")) {
            onSpeak("I am J.A.R.V.I.S — Just A Rather Very Intelligent System. Version 3. I can do anything on this phone.")
            return CommandResult.success("Identity stated")
        }
        if (matchesAny(cmd, "what can you do", "help", "commands", "capabilities",
                "kya kar sakte ho")) {
            return AiTool.listCapabilities(ctx, onSpeak)
        }
        if (matchesAny(cmd, "status", "system status", "how are you", "kaise ho")) {
            return SystemTool.fullStatus(ctx, onSpeak)
        }
        if (matchesAny(cmd, "version", "jarvis version")) {
            onSpeak("J.A.R.V.I.S Version 3.0 Ultimate. All systems operational.")
            return CommandResult.success("v3.0")
        }
        if (matchesAny(cmd, "good morning", "good night", "hello jarvis", "hey jarvis",
                "good afternoon", "good evening", "hi jarvis")) {
            return AiTool.greet(ctx, cmd, onSpeak)
        }
        if (matchesAny(cmd, "stop", "quit", "exit", "band karo", "shut up", "stop jarvis")) {
            onSpeak("Stopping. Say 'Hey JARVIS' when you need me.")
            return CommandResult.success("stopped")
        }

        // ── AI FALLBACK — for everything else ────────────────────────────
        return aiExecute(command, onSpeak)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AI FALLBACK — for unknown commands, AI figures it out
    // ─────────────────────────────────────────────────────────────────────
    private suspend fun aiExecute(command: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            val response = AIClient.execute(
                ctx     = ctx,
                command = command,
                context = MemoryEngine.getContext()
            )
            val text = response.optString("speak", "Done.")
            onSpeak(text)

            // Did AI generate new code? Save it as learned command
            val newCode = response.optString("code", "")
            if (newCode.isNotBlank()) {
                MemoryEngine.saveLearnedCommand(command, newCode)
            }

            CommandResult.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "AI fallback failed: ${e.message}")
            onSpeak("I wasn't able to do that. I will remember and try differently next time.")
            MemoryEngine.logFailure(command, e.message ?: "unknown")
            CommandResult.failure(e.message ?: "AI failed")
        }
    }

    private suspend fun executeLearned(
        code: String, original: String, onSpeak: (String) -> Unit
    ): CommandResult {
        return try {
            onSpeak("Using learned skill.")
            // Execute as scripted automation
            AutomationTool.executeScript(ctx, code, onSpeak)
        } catch (e: Exception) {
            Log.e(TAG, "Learned command failed: ${e.message}")
            dispatch(original, onSpeak)  // fall through to normal dispatch
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EXTRACTORS
    // ─────────────────────────────────────────────────────────────────────
    private fun matchesAny(cmd: String, vararg keywords: String): Boolean =
        keywords.any { cmd.contains(it) }

    private fun extractContact(cmd: String): String {
        val after = listOf("call", "phone", "message", "text", "whatsapp",
            "sms", "send to", "contact", "karo", "bhejo")
        for (kw in after) {
            if (cmd.contains(kw)) {
                val part = cmd.substringAfter(kw).trim()
                val name = part.split(" ").take(3).joinToString(" ").trim()
                if (name.isNotBlank()) return name
            }
        }
        return cmd.split(" ").lastOrNull() ?: ""
    }

    private fun extractMessage(cmd: String): String {
        val markers = listOf("saying", "say", "message", "text", "body", "with",
            "that", "\"", ":", "bolo", "likho")
        for (m in markers) {
            if (cmd.contains(m)) {
                val part = cmd.substringAfter(m).trim().removeSurrounding("\"")
                if (part.isNotBlank()) return part
            }
        }
        return cmd
    }

    private fun extractAppName(cmd: String): String {
        val stopWords = setOf("open", "launch", "start", "close", "kill",
            "kholo", "chalo", "band", "app", "application")
        return cmd.split(" ").filter { it !in stopWords && it.isNotBlank() }
            .joinToString(" ").trim()
    }

    private fun extractAfter(cmd: String, vararg markers: String): String {
        for (m in markers) {
            if (cmd.contains(m)) {
                return cmd.substringAfter(m).trim()
            }
        }
        return cmd
    }

    private fun extractNumber(cmd: String): Int? =
        Regex("""\d+""").find(cmd)?.value?.toIntOrNull()

    private fun extractName(cmd: String): String {
        return Regex("name[:\\s]+([\\w\\s]+)", RegexOption.IGNORE_CASE)
            .find(cmd)?.groupValues?.get(1)?.trim()
            ?: cmd.substringAfter("add").substringBefore("number").trim()
    }

    private fun extractPhone(cmd: String): String =
        Regex("""\d{10,13}""").find(cmd)?.value ?: ""

    private fun extractDestination(cmd: String): String {
        val markers = listOf("to", "navigate to", "directions to", "go to", "kaise jao")
        for (m in markers) {
            if (cmd.contains(m)) return cmd.substringAfter(m).trim()
        }
        return cmd
    }

    private fun extractSearchQuery(cmd: String): String {
        val markers = listOf("search for", "google", "look up", "find", "search",
            "dhundo", "internet par dhundo")
        for (m in markers) {
            if (cmd.contains(m)) {
                val q = cmd.substringAfter(m).trim()
                if (q.isNotBlank()) return q
            }
        }
        return cmd
    }

    private fun extractUrl(cmd: String): String {
        val urlRegex = Regex("""https?://[^\s]+""")
        return urlRegex.find(cmd)?.value
            ?: cmd.substringAfter("website").substringAfter("url").trim()
    }
}

data class CommandResult(
    val success: Boolean,
    val output:  String,
    val action:  String = ""
) {
    companion object {
        fun success(output: String, action: String = "") =
            CommandResult(true, output, action)
        fun failure(reason: String) =
            CommandResult(false, reason)
    }
}
