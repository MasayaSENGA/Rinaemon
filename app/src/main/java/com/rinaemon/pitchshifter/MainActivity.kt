package com.rinaemon.pitchshifter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rinaemon.pitchshifter.ui.theme.PitchShifterTheme

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val recordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionThenProjection()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchProjectionRequest() }

    private val projectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            PitchController.statusMessage.value = "キャプチャの許可がキャンセルされました"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(MediaProjectionManager::class.java)

        setContent {
            PitchShifterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PitchScreen(
                        onStart = { startFlow() },
                        onStop = { stopCapture() }
                    )
                }
            }
        }
    }

    private fun startFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationPermissionThenProjection()
        }
    }

    private fun requestNotificationPermissionThenProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchProjectionRequest()
        }
    }

    private fun launchProjectionRequest() {
        projectionRequest.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun PitchScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val isCapturing by PitchController.isCapturing.collectAsState()
    val semitones by PitchController.semitones.collectAsState()
    val muteOriginal by PitchController.muteOriginal.collectAsState()
    val status by PitchController.statusMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "リアルタイム\nピッチシフター",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(text = "状態: $status", modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))

        Text(
            text = "ピッチ: ${
                when {
                    semitones > 0f -> "+"
                    semitones < 0f -> "-"
                    else -> ""
                }
            }${"%.1f".format(kotlin.math.abs(semitones))}"
        )
        Slider(
            value = semitones,
            onValueChange = { PitchController.semitones.value = it },
            valueRange = -12f..12f,
            steps = 47,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "元の音声をミュート\n（実験的・アラーム音量で再生）")
            Switch(
                checked = muteOriginal,
                onCheckedChange = { PitchController.muteOriginal.value = it }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onStart, enabled = !isCapturing) {
                Text("開始")
            }
            Button(onClick = onStop, enabled = isCapturing) {
                Text("停止")
            }
        }

        Text(
            text = "注意: 他アプリの再生音を複製して変換するため、元の音声も同時に鳴ります。" +
                "「元の音声をミュート」は実験的な回避策で、音量まわりの挙動が端末により異なる場合があります。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}
