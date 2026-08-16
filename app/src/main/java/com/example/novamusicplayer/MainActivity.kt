package com.example.novamusicplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.image.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.withResources
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DrawModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.PainterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                    waveform = waveform,
                    currentSongInfo = currentSongInfo,
                    albumArtBitmap = albumArtBitmap
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
    private var waveform by remember { mutableStateOf<FloatArray?>(null) }
    private var albumArtBitmap by remember { mutableStateOf<Bitmap?>(null) }

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
                // Collect visualizer data and convert to normalized float array for drawing
                service.visualizerData.collect { byteArray ->
                    if (byteArray.isNotEmpty()) {
                        // Convert signed byte (-128..127) to float (-1..1)
                        val floatArray = FloatArray(byteArray.size) { i ->
                            val b = byteArray[i]
                            val f = if (b > 0) b / 127f else b / 128f
                            f
                        }
                        waveform.value = floatArray
                        // Also compute amplitude (peak) for possible use
                        val maxAbs = floatArray.map { kotlin.math.abs(it) }.maxOrNull() ?: 0f
                        visualizerAmplitude.value = maxAbs
                    } else {
                        waveform.value = null
                        visualizerAmplitude.value = 0f
                    }
                }
            }
            lifecycleScope.launch {
                // Collect album art URI and load bitmap
                service.albumArtUri.collect { uri ->
                    uri?.let { albumUri ->
                        // Attempt to load bitmap from content URI
                        contentResolver?.openInputStream(albumUri)?.use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            albumArtBitmap.value = bitmap
                        }
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
    waveform: FloatArray?,
    currentSongInfo: String,
    albumArtBitmap: Bitmap?
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

        // Song info and album art
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = currentSongInfo,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            albumArtBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Album Art",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Visualizer - using actual waveform data
        Visualizer(
            waveform = waveform,
            isPlaying = isPlaying,
            albumArtBitmap = albumArtBitmap
        )

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
fun Visualizer(waveform: FloatArray?, isPlaying: Boolean, albumArtBitmap: Bitmap? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(
                albumArtBitmap?.let { bitmap ->
                    bitmap.asImageBitmap()
                } ?: Color(0xFF0D0D0D) // dark background if no album art
            )
            .clip(RoundedCornerShape(20.dp))
    ) {
        // Draw waveform on top
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (isPlaying && waveform != null && waveform.isNotEmpty()) {
                val width = size.width
                val height = size.height
                val pointCount = waveform.size
                if (pointCount >= 2) {
                    val xStep = width / (pointCount - 1)
                    val maxAmplitudeHeight = height * 0.4f // leave some margin
                    val centerY = height / 2f
                    // Create a path for the waveform
                    val path = android.graphics.Path().apply {
                        moveTo(0f, centerY - waveform[0] * maxAmplitudeHeight)
                        for (i in 1 until pointCount) {
                            val x = i * xStep
                            val y = centerY - waveform[i] * maxAmplitudeHeight
                            lineTo(x, y)
                        }
                    }
                    // Draw the path
                    drawPath(
                        path = path,
                        color = Color(0xFF00BFA6), // teal accent
                        strokeWidth = 2f
                    )
                    // Optionally fill under the curve with gradient
                    val fillPath = android.graphics.Path().apply {
                        moveTo(0f, centerY - waveform[0] * maxAmplitudeHeight)
                        for (i in 1 until pointCount) {
                            val x = i * xStep
                            val y = centerY - waveform[i] * maxAmplitudeHeight
                            lineTo(x, y)
                        }
                        lineTo(width, height)
                        lineTo(0f, height)
                        close()
                    }
                    // Create a vertical gradient for fill
                    val shader = android.graphics.LinearGradient(
                        0f, 0f, 0f, height,
                        intArrayOf(0x6600BFA6, 0x00000000), // teal with alpha to transparent
                        floatArrayOf(0f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    drawPath(
                        path = fillPath,
                        color = Color(0x6600BFA6),
                        style = Stroke(width = 0f, fill = Fill(shader))
                    )
                }
            } else {
                // Draw a placeholder line when not playing or no data
                drawLine(
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    color = Color(0xFF666666),
                    strokeWidth = 2f
                )
            }
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
