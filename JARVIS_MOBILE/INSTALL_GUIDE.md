# J.A.R.V.I.S MOBILE — APK Build Guide

## HOW TO BUILD THE APK

### Option A — Android Studio (Easiest)
1. Install Android Studio: https://developer.android.com/studio
2. Open this folder as a project
3. Click **Build → Build Bundle/APK → Build APK**
4. APK is in: `app/build/outputs/apk/debug/app-debug.apk`
5. Transfer to phone and install

### Option B — Command Line (Windows)
```
# Install Java 17+ and Android SDK first
# Then:
cd JARVIS_MOBILE
gradlew.bat assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
```

### Option C — GitHub Actions (Auto-build in cloud, FREE)
1. Push this folder to a GitHub repo
2. Add `.github/workflows/build.yml` (template below)
3. GitHub builds the APK automatically
4. Download from the Actions tab

```yaml
name: Build JARVIS APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with: { java-version: '17', distribution: 'temurin' }
      - uses: android-actions/setup-android@v2
      - run: chmod +x gradlew && ./gradlew assembleDebug
      - uses: actions/upload-artifact@v3
        with:
          name: JARVIS-APK
          path: app/build/outputs/apk/debug/app-debug.apk
```

---

## AFTER INSTALLING ON PHONE

### Required Permissions (grant all for full power):
1. **Accessibility Service** → Settings → Accessibility → JARVIS Controller → ON
2. **Notification Access** → Settings → Notifications → Notification Access → JARVIS → ON
3. **Device Admin** → Settings → Security → Device Admin → JARVIS → Activate
4. **Draw Over Apps** → Settings → Apps → JARVIS → Display over other apps → ON
5. **All other permissions** → granted automatically on first launch

### First Launch:
1. Open JARVIS app
2. Setup wizard appears
3. Paste API key (Gemini free: https://aistudio.google.com/apikey)
4. Optionally enter PC JARVIS IP for PC control
5. Say "Jarvis" + any command

---

## COMMANDS (10,000+ supported)

### Phone & SMS
- "Jarvis, call Rahul"
- "Jarvis, send WhatsApp to Mom saying I'm coming home"
- "Jarvis, read my unread messages"
- "Jarvis, auto reply when anyone says hello say I'm busy"

### Apps & Settings
- "Jarvis, open YouTube and search lofi music"
- "Jarvis, turn on WiFi"
- "Jarvis, brightness 50%"
- "Jarvis, dark mode on"
- "Jarvis, hotspot on"

### Camera & Media
- "Jarvis, take a selfie"
- "Jarvis, flashlight on"
- "Jarvis, play desi music"
- "Jarvis, volume up"
- "Jarvis, record video for 30 seconds"

### Location & Web
- "Jarvis, navigate to nearest petrol pump"
- "Jarvis, what's the weather today"
- "Jarvis, search cricket score"
- "Jarvis, translate this to Hindi"

### System & Info
- "Jarvis, battery kitni hai"
- "Jarvis, storage info"
- "Jarvis, lock screen"
- "Jarvis, device info"

### AI Chat
- "Jarvis, explain quantum physics"
- "Jarvis, write an email to my boss"
- "Jarvis, tell me a joke"
- "Jarvis, calculate 15% of 8500"

### PC Bridge (when PC JARVIS is running)
- "Jarvis, connect to my PC"
- "Jarvis, PC pe Chrome kholo"
- "Jarvis, PC screenshot lo"
- "Jarvis, sync clipboard to PC"

### Automation
- "Jarvis, set alarm at 7 AM"
- "Jarvis, remind me to drink water every hour"
- "Jarvis, when battery hits 20% notify me"
- "Jarvis, repeat this every morning"

---

## ARCHITECTURE

```
JarvisApp (Application)
│
├── JarvisService (Foreground, always running)
│   ├── Continuous wake-word detection "Jarvis"
│   ├── TextToSpeech engine
│   └── CommandEngine dispatcher
│
├── CommandEngine (10,000+ commands)
│   ├── Pattern matching (fast)
│   ├── Direct tool execution
│   └── AI fallback (Gemini/Claude/GPT/Groq)
│
├── Tools (AllTools.kt)
│   ├── PhoneTool, SmsTool, ContactsTool
│   ├── AppTool, MediaTool, SystemTool
│   ├── CameraTool, ScreenTool, LocationTool
│   ├── WifiTool, BluetoothTool, SettingsTool
│   ├── WebTool, FileTool, NotificationTool
│   ├── AiTool, AutomationTool, LearnTool
│   └── TimeTool, HealthTool, UtilTool
│
├── MemoryEngine (persistent storage)
│   ├── Config (API keys, settings)
│   ├── Notes and learned commands
│   └── Failure log for self-healing
│
├── BridgeService (PC ↔ Mobile WebSocket)
│
├── JarvisAccessibilityService (UI control)
├── NotificationListener (read all notifications)
├── BootReceiver (auto-start on reboot)
├── SmsReceiver (auto-reply on SMS)
└── CallReceiver (call announcements)
```
