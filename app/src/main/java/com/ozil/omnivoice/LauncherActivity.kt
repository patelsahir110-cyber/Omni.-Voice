package com.ozil.omnivoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * 1st tap on the app icon: asks for needed permissions once, then starts the
 * floating side panel + notification. 2nd tap: stops everything.
 */
class LauncherActivity : Activity() {

    private val reqOverlay = 101
    private val reqPerms = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "OmniVoice stopped", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        proceed()
    }

    private fun proceed() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for OmniVoice", Toast.LENGTH_LONG).show()
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(i, reqOverlay)
            return
        }

        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), reqPerms)
            return
        }

        launchPanel()
    }

    private fun launchPanel() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))

        if (!accessibilityEnabled()) {
            Toast.makeText(
                this,
                "Turn ON \"OmniVoice\" here so it can type into other apps",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        finish()
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqOverlay) {
            if (Settings.canDrawOverlays(this)) proceed() else finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPerms) launchPanel()
    }
}
