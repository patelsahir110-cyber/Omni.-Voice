# OmniVoice (Android)

Floating voice-typing side panel for any app.

- **Tap app icon once** → side panel on the right edge + notification (Speak / Stop all).
- **Tap app icon again** → everything stops.
- Speak → text is typed into the text box you are using, in any app (WhatsApp, Chrome, Gmail…).
- Languages: English (India/US/UK), Hindi, Marathi, Spanish, French, German.
- Engine: Hybrid (default), Offline (prefers on-device), Internet.
- Theme: White / Black. Spoken punctuation: "comma", "period", "question mark", "new line".

## Get the APK (no Android Studio needed)

1. Create a new **empty GitHub repository** (e.g. `omnivoice-android`).
2. Upload everything in this folder (keep the `.github` folder). On github.com: *Add file → Upload files*.
3. Open the **Actions** tab → *Build OmniVoice APK* → wait ~3–5 minutes for the green tick.
   (If it did not start by itself: *Run workflow*.)
4. Open the finished run → **Artifacts → OmniVoice-APK** → download, unzip, you get `app-debug.apk`.
5. Copy it to your phone and install (allow "Install unknown apps" when asked).

## First run on the phone

1. Tap the OmniVoice icon.
2. Allow **Display over other apps**, then the **Microphone** (and Notifications) prompts.
3. A settings screen opens: turn **ON** the *OmniVoice* accessibility service. This is what lets it type into other apps. Without it, text is copied to the clipboard instead.
4. Tap any text box in any app, then tap **Tap & Speak** on the right-edge panel (or *Speak* in the notification).

Tap the "Voice" tab on the edge to collapse/expand the panel; drag it up/down to move it.

## Notes

- Recognition uses the phone's built-in speech service (Google voice typing). Offline needs the language pack downloaded in Google's voice-typing settings.
- Debug build is signed with Android's debug key, fine for personal use.
