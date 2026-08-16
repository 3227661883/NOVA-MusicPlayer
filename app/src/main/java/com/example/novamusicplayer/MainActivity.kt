package com.example.novamusicplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.image.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.index
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.Unit
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.withResources
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

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
            // Fetch metadata and lyrics
            fetchMetadataAndLyrics(it)
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
                    albumArtBitmap = albumArtBitmap,
                    lyricLines = lyricLines,
                    currentLyricIndex = currentLyricIndex
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
    private var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    private var currentLyricIndex by remember { mutableStateOf(-1) }

    private var stateCollectionJob: kotlinx.coroutines.Job? = null
    private var lyricFetchJob: kotlinx.coroutines.Job? = null

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

    private fun fetchMetadataAndLyrics(uri: Uri) {
        // Cancel any ongoing lyric fetch
        lyricFetchJob?.cancel()
        lyricFetchJob = lifecycleScope.launch {
            try {
                // Try to get metadata from the file using MediaMetadataRetriever
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                retriever.release()

                val trackName = title ?: uri.lastPathSegment ?: "Unknown"
                val artistName = artist ?: "Unknown Artist"

                // Update song info
                currentSongInfo.value = "$trackName - $artistName"

                // Fetch lyrics from LRCLib
                val lyric = fetchLyricsFromLRCLib(trackName, artistName)
                lyricLines.value = lyric
                // Reset current lyric index
                currentLyricIndex.value = -1
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to fetch metadata/lyrics", e)
                currentSongInfo.value = uri.lastPathSegment ?: "Unknown"
                lyricLines.value = emptyList()
                currentLyricIndex.value = -1
            }
        }
    }

    private suspend fun fetchLyricsFromLRCLib(track: String, artist: String): List<LyricLine> {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val encodedTrack = java.net.URLEncoder.encode(track, "UTF-8")
            val encodedArtist = java.net.URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/get?artist=$encodedArtist&track=$encodedTrack"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Try without artist
                    val url2 = "https://lrclib.net/api/get?track=$encodedTrack"
                    val request2 = Request.Builder().url(url2).build()
                    response2 = client.newCall(request2).execute()
                    if (!response2.isSuccessful) {
                        return@withContext emptyList()
                    }
                    response2.body?.string()?.let { parseLrc(it) } ?: emptyList()
                } else {
                    response.body?.string()?.let { parseLrc(it) } ?: emptyList()
                }
            }
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        lrc.split("\n").forEach { line ->
            if (line.startsWith("[") && line.contains("]")) {
                val tagEnd = line.indexOf(']')
                val timestamp = line.substring(1, tagEnd)
                val text = line.substring(tagEnd + 1).trim()
                // Parse timestamp: [mm:ss.xx] or [mm:ss:xx]
                val parts = timestamp.split(":")
                if (parts.size >= 2) {
                    val minutes = parts[0].toIntOrNull() ?: 0
                    val seconds = parts[1].toDoubleOrNull() ?: 0.0
                    var totalSec = minutes * 60 + seconds
                    if (parts.size >= 3) {
                        // Handle hundredths of a second
                        val hundredths = parts[2].toDoubleOrNull() ?: 0.0
                        totalSec += hundredths / 100.0
                    }
                    val timeMs = (totalSec * 1000).toLong()
                    if (!text.isEmpty()) {
                        lines.add(LyricLine(timeMs, text))
                    }
                }
            }
        }
        // Sort by time
        lines.sortBy { it.timeMs }
        return lines
    }

    // Update current lyric index based on playback position
    private fun updateLyricIndex(positionMs: Long) {
        if (lyricLines.value.isEmpty()) {
            currentLyricIndex.value = -1
            return
        }
        // Find the last lyric line with time <= positionMs
        var index = -1
        for (i in lyricLines.value.indices) {
            if (lyricLines.value[i].timeMs <= positionMs) {
                index = i
            } else {
                break
            }
        }
        if (index != currentLyricIndex.value) {
            currentLyricIndex.value = index
        }
    }

    private fun stopCollectingState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        lyricFetchJob?.cancel()
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
    albumArtBitmap: Bitmap?,
    lyricLines: List<LyricLine>,
    currentLyricIndex: Int
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

        Spacer(modifier = Modifier.height(12.dp))

        // Song info and album art
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = currentSongInfo,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Now Playing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

        Spacer(modifier = Modifier.height(12.dp))

        // Visualizer
        Visualizer(
            amplitude = visualizerAmplitude,
            isPlaying = isPlaying,
            waveform = waveform
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Lyric list
        LyricView(
            lines = lyricLines,
            currentIndex = currentLyricIndex,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.background)
                .clip(RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
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
fun LyricView(
    lines: List<LyricLine>,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp)
    ) {
        itemsIndexed(items = lines) { index, line ->
            val isCurrent = index == currentIndex
            val lineColor = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val bgColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp)
                    .background(bgColor)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = "${formatMs(line.timeMs)} - ${line.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = lineColor
                )
            }
        }
    }
}

@Composable
fun Visualizer(amplitude: Float, isPlaying: Boolean, waveform: FloatArray? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                if (isPlaying) Color(0xFF0D0D0D) else Color(0xFF222222)
            )
            .clip(RoundedCornerShape(16.dp))
    ) {
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
                    val maxAmplitudeHeight = height * 0.4f
                    val centerY = height / 2f
                    val path = android.graphics.Path().apply {
                        moveTo(0f, centerY - waveform[0] * maxAmplitudeHeight)
                        for (i in 1 until pointCount) {
                            val x = i * xStep
                            val y = centerY - waveform[i] * maxAmplitudeHeight
                            lineTo(x, y)
                        }
                    }
                    // Draw glowing effect with multiple strokes
                    val glowPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#00BFA6")
                        strokeWidth = 4f
                        style = android.graphics.Paint.Style.STROKE
                        isAntiAlias = true
                    }
                    // We cannot directly set a custom paint in Compose Canvas easily, so we'll approximate by drawing multiple times with increasing stroke width and alpha
                    val baseColor = Color(0xFF00BFA6)
                    for (i in 3 downto 1) {
                        val alpha = (0.2 * i).coerceIn(0f, 0.6f)
                        val strokeWidth = (2.0 * i).toFloat()
                        drawPath(
                            path = path,
                            color = baseColor.copy(alpha = alpha),
                            strokeWidth = strokeWidth
                        )
                    }
                    // Main stroke
                    drawPath(
                        path = path,
                        color = baseColor,
                        strokeWidth = 2f
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

/**
 * Data class for a lyric line with timestamp.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)
