package com.liquidbattery

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Scheduler.now(applicationContext)
        askNotif()
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        if (Build.VERSION.SDK_INT >= 31 && !Reader.hasPermission(this)) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            askNotif()
        }
        lifecycleScope.launch { Live.sync(applicationContext, store.settings.first().liveUpdates) }
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
