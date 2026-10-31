package com.liquidbattery

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Scheduler.now(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 31 && !Reader.hasPermission(this)) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent {
            val s by store.settings.collectAsState(initial = UiSettings())
            val snap by store.snapshot.collectAsState(initial = null)
            val metas by store.metas.collectAsState(initial = emptyMap())
            LiquidTheme(s) {
                GlassBackground {
                    AppRoot(store, s, snap, metas) {
                        if (Build.VERSION.SDK_INT >= 31) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.now(applicationContext)
    }
}

/** Shown when the widget is placed or reconfigured. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancel by default so backing out doesn't add an unconfigured widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val store = Store(this)
        setContent {
            val s by store.settings.collectAsState(initial = UiSettings())
            val snap by store.snapshot.collectAsState(initial = null)
            val metas by store.metas.collectAsState(initial = emptyMap())
            val scope = rememberCoroutineScope()
            val rows = remember(snap, metas) { resolve(snap, metas) }
            LiquidTheme(s) {
                GlassBackground {
                    WidgetConfigScreen(s, rows, store, scope) {
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                        finish()
                    }
                }
            }
        }
    }
}
