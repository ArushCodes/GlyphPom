package com.closenheimer.glyphpom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

class PomodoroWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            updateWidgetUI(context, appWidgetManager, appWidgetId, options, null)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateWidgetUI(context, appWidgetManager, appWidgetId, newOptions, null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == "UPDATE_WIDGET") {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PomodoroWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (id in appWidgetIds) {
                val options = appWidgetManager.getAppWidgetOptions(id)
                updateWidgetUI(context, appWidgetManager, id, options, intent)
            }
        }
    }

    private fun updateWidgetUI(context: Context, manager: AppWidgetManager, id: Int, options: Bundle, data: Intent?) {
        val views = RemoteViews(context.packageName, R.layout.widget_pomodoro)

        // 1. Dynamic Data Parsing
        val time = data?.getStringExtra("TIME") ?: "00:00"
        val mode = data?.getStringExtra("MODE") ?: "READY"
        val isConfirmingStop = data?.getBooleanExtra("CONFIRM_STOP", false) ?: false

        views.setTextViewText(R.id.widget_timer, time)
        views.setTextViewText(R.id.widget_mode, mode)

        // 2. Sizing Logic (Only show controls if height > 100dp)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        views.setViewVisibility(R.id.widget_controls, if (minHeight > 100) View.VISIBLE else View.GONE)

        // 3. Dynamic Button Logic (START / PAUSE / RESUME)
        val actionIntent = Intent(context, PomodoroService::class.java)

        when (mode) {
            "FOCUS", "REST", "FLOWING", "WORK" -> {
                views.setTextViewText(R.id.widget_btn_action, "PAUSE")
                views.setInt(R.id.widget_btn_action, "setBackgroundColor", Color.parseColor("#333333"))
                views.setTextColor(R.id.widget_btn_action, Color.WHITE)
                actionIntent.action = "ACTION_PAUSE"
            }
            "PAUSED", "WAITING" -> {
                views.setTextViewText(R.id.widget_btn_action, "RESUME")
                views.setInt(R.id.widget_btn_action, "setBackgroundColor", Color.WHITE)
                views.setTextColor(R.id.widget_btn_action, Color.BLACK)
                actionIntent.action = "ACTION_PAUSE"
            }
            else -> {
                views.setTextViewText(R.id.widget_btn_action, "GO")
                views.setInt(R.id.widget_btn_action, "setBackgroundColor", Color.WHITE)
                views.setTextColor(R.id.widget_btn_action, Color.BLACK)
                actionIntent.action = "START_TIMER"
            }
        }

        val pAction = PendingIntent.getForegroundService(context, 10, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_action, pAction)

        // 4. STOP / SURE? Logic
        val stopIntent = Intent(context, PomodoroService::class.java)
        if (isConfirmingStop) {
            views.setTextViewText(R.id.widget_btn_stop, "SURE?")
            views.setInt(R.id.widget_btn_stop, "setBackgroundColor", Color.parseColor("#880000"))
            stopIntent.action = "STOP_TIMER" // Second click stops
        } else {
            views.setTextViewText(R.id.widget_btn_stop, "X")
            views.setInt(R.id.widget_btn_stop, "setBackgroundColor", Color.parseColor("#EA1111"))
            stopIntent.action = "WIDGET_STOP_CLICK" // First click triggers "Sure?"
        }

        val pStop = PendingIntent.getService(context, 11, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_stop, pStop)

        // 5. Open App on Timer Click
        val mainIntent = Intent(context, MainActivity::class.java)
        val pMain = PendingIntent.getActivity(context, 12, mainIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_timer, pMain)

        manager.updateAppWidget(id, views)
    }
}