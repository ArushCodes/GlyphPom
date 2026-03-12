package com.closenheimer.glyphpom

import android.content.Intent
import android.service.quicksettings.TileService

class PomodoroTile : TileService() {
    // When you tap the tile in the notification tray
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PomodoroService::class.java).apply {
            action = "START_TIMER"
        }
        startForegroundService(intent)
    }
}