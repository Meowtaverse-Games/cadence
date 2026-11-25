package com.meowtaversegames.cadence

import android.content.Intent
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class BlockActivity : ComponentActivity() {
    private val runtime by lazy { BlockingRuntime.get(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var resumed = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { }.build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto system-bar styles follow the device theme; content already uses safeDrawingPadding.
        enableEdgeToEdge()
        setContent {
            CadenceTheme {
                BackHandler { home() }
                LaunchedEffect(runtime.decision?.runService) {
                    if (runtime.decision?.runService == false) home()
                }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("集中の時間です", style = MaterialTheme.typography.headlineLarge)
                    runtime.decision?.end?.let { Text("${clockText(it)} まで") }
                    if (runtime.blockWindow.multiWindow) Text("分割画面を閉じてください")
                    Button(onClick = ::home) { Text("ホームへ") }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        runtime.windowChanged(BlockWindow(true, isInMultiWindowMode))
        runtime.store.log("block_resumed multiWindow=$isInMultiWindowMode")
        runtime.reconcile(ReconcileSource.VISIBLE_ACTIVITY)
        audio.requestAudioFocus(focus)
    }

    override fun onPause() {
        resumed = false
        runtime.windowChanged(BlockWindow(false, isInMultiWindowMode))
        audio.abandonAudioFocusRequest(focus)
        super.onPause()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        runtime.windowChanged(BlockWindow(resumed, isInMultiWindowMode))
    }

    private fun home() {
        audio.abandonAudioFocusRequest(focus)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
