package com.example.novamusicplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.novamusicplayer.service.PlaybackService
import com.example.novamusicplayer.ui.theme.NovaTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var playbackService: PlaybackService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            // Start collecting state flows
            startCollectingState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            playbackService = null
            stopCollectingState()
        }
    }

    // Permission launcher for reading media (Android 13+ uses READ_MEDIA_AUDIO)
    private val requestPermissionLauncher = registerForActivityResult(
        RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, proceed to open file picker
            openFilePicker()
        } else {
            // Permission denied, show a message or handle accordingly
            // For simplicity, we just do nothing; the user can try again.
        }
    }

    // SAF launcher to pick an audio file
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Grant read permission for the URI
            takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Start service and set the URI
            startPlaybackService(it)
            // Update song info (simple file name)
            currentSongInfo.value = uri.lastPathSegment ?: "Unknown"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaTheme {
                NovaMusicPlayerUI(
                    onSelectSong = { checkPermissionAndOpenPicker() },
                    onPlayPause = { togglePlayPause() },
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    visualizerAmplitude = visualizerAmplitude,
                    currentSongInfo = currentSongInfo
                )
            }
        }
    }

    private fun checkPermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_MEDIA_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                requestPermissionLauncher.launch(
                    android.Manifest.permission.READ_MEDIA_AUDIO
                )
            }
        } else {
            // Below Android 13, use READ_EXTERNAL_STORAGE (though for audio we might still need it)
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openFilePicker()
            } else {
                requestPermissionLauncher.launch(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        }
    }

    private fun openFilePicker() {
        pickMediaLauncher.launch(
            // Set MIME type filter to audio
            arrayOf("audio/*")
        )
    }

    private fun startPlaybackService(uri: Uri) {
        // Start the service (if not already started)
        val intent = Intent(this, PlaybackService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // Bind to service
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun togglePlayPause() {
        playbackService?.let { service ->
            if (service.isPlaying) {
                service.pause()
            } else {
                service.play()
            }
        }
    }

    // State variables for UI
    private var isPlaying by remember { mutableStateOf(false) }
    private val playbackState by remember { mutableStateOf(PlaybackService.PlaybackState(false, 0L, 0L)) }
    private var currentSongInfo by remember { mutableStateOf("No song selected") }
    private var visualizerAmplitude by remember { mutableStateOf(0f) }

    private var stateCollectionJob: kotlinx.coroutines.Job? = null

    private fun startCollectingState() {
        playbackService?.let { service ->
            stateCollectionJob = lifecycleScope.launch {
                // Collect playbackState
                service.playbackState.collect { state ->
                    playbackState.value = state
                    isPlaying.value = state.isPlaying
                }
            }
            lifecycleScope.launch {
                // Collect visualizer data and compute amplitude
                service.visualizerData.collect { waveform ->
                    if (waveform.isNotEmpty()) {
                        // Compute RMS or peak amplitude
                        val maxAbs = waveform.map { it.toInt() and 0xFF }.map { if (it > 127) it - 256 else it }.map { kotlin.math.abs(it) }.maxOrNull() ?: 0
                        // Normalize to 0-1 (max possible is 128 for signed byte)
                        visualizerAmplitude.value = maxAbs / 128f
                    } else {
                        visualizerAmplitude.value = 0f
                    }
                }
            }
        }
    }

    private fun stopCollectingState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCollectingState()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}

@Composable
fun NovaMusicPlayerUI(
    onSelectSong: () -> Unit,
    onPlayPause: () -> Unit,
    isPlaying: Boolean,
    playbackState: PlaybackService.PlaybackState,
    visualizerAmplitude: Float,
    currentSongInfo: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalSpacing(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NOVA Music Player",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Song info
        Text(
            text = currentSongInfo,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Visualizer - using amplitude from audio waveform
        Visualizer(amplitude = visualizerAmplitude, isPlaying = isPlaying)

        Spacer(modifier = Modifier.height(16.dp))

        // Controls
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onSelectSong,
                modifier = Modifier.width(100.dp)
            ) {
                Text(text = "Select Song")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = onPlayPause,
                modifier = Modifier.width(80.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Progress info
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatMs(playbackState.positionMs),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = formatMs(playbackState.durationMs),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun Visualizer(amplitude: Float, isPlaying: Boolean) {
    // Simple animated visualizer: number of bars, height based on amplitude + animation
    val progress = remember { mutableStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var current = progress.value
            while (isPlaying) {
                current = (current + 0.02f) % 1.0f
                progress.value = current
                delay(20) // 50 fps
            }
        } else {
            progress.value = 0f
        }
    }
    val waveHeight = (amplitude * 0.5f + progress.value * 0.5f) * 80.dp // max height 80dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(10) {
            val barHeight = if (isPlaying) {
                // Vary each bar slightly for a more interesting look
                val offset = (it * 0.1f + progress.value) % 1.0f
                (amplitude * 0.3f + offset * 0.7f) * 80.dp
            } else {
                0.dp
            }
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(barHeight)
                    .background(Color(0xFF00BFA6)) // teal accent
            )
        }
    }
}

private fun formatMs(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

/**
 * Simple data class to mirror PlaybackService.PlaybackState.
 * In a real app, we would likely use the same class or a sealed interface.
 */
data class PlaybackService$PlaybackState(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long
)
