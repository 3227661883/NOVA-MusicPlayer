package com.example.novamusicplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Equalizer
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
import androidx.compose.animation.AnimationSpec
import androidx.compose.animation.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.image.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
            androidx.compose.ui.platform.LocalLayoutDirection
            androidx.compose.ui.res.PainterResource
            androidx.compose.ui.text.font.FontWeight
            androidx.compose.ui.unit.dp
            androidx.core.content.ContextCompat
            androidx.lifecycle.lifecycleScope
            com.example.novamusicplayer.service.PlaybackService
            com.example.novamusicplayer.ui.theme.NovaTheme
            kotlinx.coroutines.flow.collectLatest
            kotlinx.coroutines.flow.update
            kotlinx.coroutines.launch
            kotlinx.coroutines.withContext
            okhttp3.OkHttpClient
            okhttp3.Request
            okhttp3.Response
            org.json.JSONObject
            java.io.File
            java.io.FileOutputStream
            java.io.IOException
            java.util.zip.ZipEntry
            java.util.zip.ZipFile

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

    // SAF launcher to pick an audio file, CUE file, or archive
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Grant read permission for the URI
            takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Handle based on file type
            when (uri.lastSegment?.lowercase) {
                ".cue" -> loadCueFile(it)
                ".zip", ".rar" -> extractAndPlayArchive(it)
                else -> {
                    // Start service and set the URI
                    startPlaybackService(it)
                    // Fetch metadata and lyrics
                    fetchMetadataAndLyrics(it)
                }
            }
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
                    currentLyricIndex = currentLyricIndex,
                    equalizer = equalizer,
                    equalizerBands = equalizerBands,
                    rotationAngle = rotationAngle,
                    cueTracks = cueTracks,
                    selectedCueTrackIndex = selectedCueTrackIndex,
                    extractionProgress = extractionProgress,
                    isExtracting = isExtracting
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
            // Set MIME type filter to audio, CUE, and archives
            arrayOf("audio/*", "text/*", "application/zip", "application/x-rar-compressed")
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
    private var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    private var equalizerBands by remember { mutableStateOf<List<EqualizerBand>>(emptyList()) }
    private var rotationAngle by remember { mutableStateOf(0f) }
    private var cueTracks by remember { mutableStateOf<List<CueTrack>>(emptyList()) }
    private var selectedCueTrackIndex by remember { mutableStateOf(-1) }
    private var extractionProgress by remember { mutableStateOf(0f) }
    private var isExtracting by remember { mutableStateOf(false) }

    private var stateCollectionJob: kotlinx.coroutines.Job? = null
    private var lyricFetchJob: kotlinx.coroutines.Job? = null
    private var equalizerJob: kotlinx.coroutines.Job? = null
    private var rotationJob: kotlinx.coroutines.Job? = null
    private var cueProcessingJob: kotlinx.coroutines.Job? = null
    private var extractionJob: kotlinx.coroutines.Job? = null

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
            lifecycleScope.launch {
                // Collect equalizer and initialize bands
                service.equalizer.collect { eq ->
                    equalizer.value = eq
                    if (eq != null) {
                        initializeEqualizerBands(eq)
                    }
                }
            }
            // Animate rotation when playing
            rotationJob = lifecycleScope.launch {
                var lastTime = System.currentTimeMillis()
                while (isBound) {
                    val now = System.currentTimeMillis()
                    val deltaTime = (now - lastTime) / 1000.0 // seconds
                    lastTime = now
                    
                    if (isPlaying) {
                        // Rotate at 30 degrees per second when playing
                        rotationAngle.value = (rotationAngle.value + 30.0 * deltaTime) % 360.0
                    }
                    // Delay to avoid excessive updates (aim for ~30fps)
                    delay(33)
                }
            }
        }
    }

    private fun extractAndPlayArchive(uri: Uri) {
        // Show extraction progress
        isExtracting.value = true
        extractionProgress.value = 0f
        
        // Cancel any ongoing extraction
        extractionJob?.cancel()
        extractionJob = lifecycleScope.launch {
            try {
                // For simplicity, we'll just show a toast or handle in a real implementation
                // In a full implementation, we would:
                // 1. Extract the archive to a temporary directory
                // 2. Find audio files inside
                // 3. Play the first audio file or show a selection UI
                // 4. Show extraction progress
                
                // For now, we'll simulate extraction progress
                for (progress in 0..100) {
                    extractionProgress.value = progress / 100f
                    delay(20) // Simulate extraction time
                }
                
                // After extraction, we would play the first audio file found
                // For demonstration, we'll just show a message
                currentSongInfo.value = "Archive extraction complete (demo)"
                isExtracting.value = false
                
                // In a real implementation, we would:
                // 1. Extract the archive
                // 2. Find the first audio file
                // 3. Call startPlaybackService with the extracted file URI
                // 4. Fetch metadata and lyrics
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to extract archive", e)
                currentSongInfo.value = "Error extracting archive"
                isExtracting.value = false
            }
        }
    }

    private fun loadCueFile(uri: Uri) {
        // Cancel any ongoing cue processing
        cueProcessingJob?.cancel()
        cueProcessingJob = lifecycleScope.launch {
            try {
                // Read the CUE file content
                val cueContent = contentResolver?.openInputStream(uri)?.use { it ->
                    it.readBytes()
                } ?: return@lifecycleScope
                val cueText = String(cueContent, Charsets.UTF_8)
                
                // Parse the CUE file
                val tracks = parseCue(cueText)
                
                // Update UI with tracks
                cueTracks.value = tracks
                
                // If we have tracks, select the first one by default
                if (tracks.isNotEmpty()) {
                    selectedCueTrackIndex.value = 0
                    // Get the first track's file path
                    val firstTrack = tracks[0]
                    // Resolve the file path relative to the CUE file directory
                    val audioFilePath = resolveCueFilePath(uri, firstTrack.file)
                    // Start playback with the resolved audio file
                    startPlaybackService(audioFilePath)
                    // Fetch metadata and lyrics for the audio file
                    fetchMetadataAndLyrics(audioFilePath)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load CUE file", e)
                currentSongInfo.value = "Error loading CUE file"
            }
        }
    }

    private fun parseCue(content: String): List<CueTrack> {
        val tracks = mutableListOf<CueTrack>()
        var currentTrack: CueTrack? = null
        var currentFile: String? = null
        
        val lines = content.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("FILE ") -> {
                    // Extract filename from FILE "filename" TYPE
                    val fileMatch = """FILE\s+"([^"]+)"""".toRegex().matchEntire(trimmed)
                    if (fileMatch != null) {
                        currentFile = fileMatch.groupValues[1]
                    }
                }
                trimmed.startsWith("TRACK ") -> {
                    // Save previous track if exists
                    if (currentTrack != null) {
                        tracks.add(currentTrack)
                    }
                    // Start new track
                    currentTrack = CueTrack()
                    // Extract track number
                    val trackMatch = """TRACK\s+(\d+)""".toRegex().matchEntire(trimmed)
                    if (trackMatch != null) {
                        currentTrack?.number = trackMatch.groupValues[1].toInt()
                    }
                }
                trimmed.startsWith("TITLE ") -> {
                    val titleMatch = """TITLE\s+"([^"]+)"""".toRegex().matchEntire(trimmed)
                    if (titleMatch != null && currentTrack != null) {
                        currentTrack?.title = titleMatch.groupValues[1]
                    }
                }
                trimmed.startsWith("PERFORMER ") -> {
                    val performerMatch = """PERFORMER\s+"([^"]+)"""".toRegex().matchEntire(trimmed)
                    if (performerMatch != null && currentTrack != null) {
                        currentTrack?.performer = performerMatch.groupValues[1]
                    }
                }
                trimmed.startsWith("INDEX 01 ") -> {
                    val indexMatch = """INDEX\s+01\s+(\d+:\d+:\d+)""".toRegex().matchEntire(trimmed)
                    if (indexMatch != null && currentTrack != null) {
                        val timeStr = indexMatch.groupValues[1]
                        val parts = timeStr.split(":")
                        if (parts.size == 3) {
                            val minutes = parts[0].toIntOrNull() ?: 0
                            val seconds = parts[1].toIntOrNull() ?: 0
                            val frames = parts[2].toIntOrNull() ?: 0
                            // Convert to milliseconds (75 frames per second in CD audio)
                            val totalMs = ((minutes * 60 + seconds) * 1000) + (frames * 1000 / 75)
                            currentTrack?.startTimeMs = totalMs
                        }
                    }
                }
            }
        }
        // Add the last track
        if (currentTrack != null) {
            tracks.add(currentTrack)
        }
        
        // Set file for all tracks
        for (track in tracks) {
            track.file = currentFile
        }
        
        return tracks
    }

    private fun resolveCueFilePath(cueUri: Uri, relativePath: String): Uri {
        // Get the directory of the CUE file
        val cuePath = cueUri.path ?: return cueUri
        val cueDir = cuePath.substring(0, cuePath.lastIndexOf('/') + 1)
        // Resolve the relative path
        val resolvedPath = cueDir + relativePath
        return Uri.parse(resolvedPath)
    }

    private fun stopCollectingState() {
        stateCollectionJob?.cancel()
        stateCollectionJob = null
        lyricFetchJob?.cancel()
        equalizerJob?.cancel()
        rotationJob?.cancel()
        cueProcessingJob?.cancel()
        extractionJob?.cancel()
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
        equalizerJob?.cancel()
        rotationJob?.cancel()
        cueProcessingJob?.cancel()
        extractionJob?.cancel()
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
    currentLyricIndex: Int,
    equalizer: Equalizer?,
    equalizerBands: List<EqualizerBand>,
    rotationAngle: Float,
    cueTracks: List<CueTrack>,
    selectedCueTrackIndex: Int,
    extractionProgress: Float,
    isExtracting: Boolean
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

        Spacer(modifier = Modifier.height(16.dp))

        // Album cover with rotation and light trail effect
        AlbumCover(
            albumArtBitmap = albumArtBitmap,
            rotationAngle = rotationAngle,
            isPlaying = isPlaying
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Visualizer
        Visualizer(
            amplitude = visualizerAmplitude,
            isPlaying = isPlaying,
            waveform = waveform
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Equalizer UI
        EqualizerUI(
            equalizer = equalizer,
            bands = equalizerBands,
            onBandLevelChanged = { bandIndex, level ->
                equalizer?.let { eq ->
                    eq.setBandLevel(bandIndex, level)
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // CUE track selector (if we have tracks)
        if (cueTracks.isNotEmpty()) {
            CueTrackSelector(
                tracks = cueTracks,
                selectedIndex = selectedCueTrackIndex,
                onTrackSelected = { index ->
                    selectedCueTrackIndex.value = index
                    // Load the selected track
                    val selectedTrack = cueTracks.value[index]
                    // Resolve the file path (we'd need to store the original CUE URI)
                    // For simplicity, we'll just show a toast or handle in a real implementation
                    // In a full implementation, we'd need to store the CUE URI and resolve the track path
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Archive extraction progress (if extracting)
        if (isExtracting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Extracting archive...",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = extractionProgress,
                    modifier = Modifier
                        .height(4.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                )
                Text(
                    text = "${(extractionProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .align(Alignment.End)
                )
            }
        }

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
fun CueTrackSelector(
    tracks: List<CueTrack>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "CUE Tracks",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            items(items = tracks) { index, track ->
                val isSelected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onTrackSelected(index) }
                ) {
                    Column {
                        Text(
                            text = "Track ${track.number}: ${track.title}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        if (track.performer.isNotEmpty()) {
                            Text(
                                text = "By ${track.performer}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCover(albumArtBitmap: Bitmap?, rotationAngle: Float, isPlaying: Boolean) {
    // We'll use a Box to center the image and apply rotation with light trail effect
    Box(
        modifier = Modifier
            .size(220.dp)
            .background(
                // Create a subtle gradient background for depth
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0D0D),
                        Color(0xFF1A1A2E)
                    )
                )
            )
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Light trail effect - multiple faded copies of the image with increasing rotation offset
        if (isPlaying && albumArtBitmap != null) {
            val trailCount = 5
            val trailSpacing = 5.0f // degrees between trail images
            val baseAlpha = 0.3f
            
            // Draw trail images (further back in time)
            for (i in trailCount downTo 1) {
                val offset = i * trailSpacing
                val alpha = baseAlpha * (1.0f - (i.toFloat() / trailCount))
                val trailRotation = (rotationAngle - offset) % 360.0
                
                Image(
                    bitmap = albumArtBitmap.asImageBitmap(),
                    contentDescription = "Album Cover Trail",
                    modifier = Modifier
                        .size(180.dp)
                        .alpha(alpha)
                        .graphicsLayer {
                            rotationZ = trailRotation
                            scaleX = 1.0f - (i * 0.02f) // Slightly smaller for trailing images
                            scaleY = 1.0f - (i * 0.02f)
                        }
                )
            }
        }
        
        // Main album cover image
        albumArtBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Album Cover",
                modifier = Modifier
                    .size(180.dp)
                    .graphicsLayer {
                        rotationZ = rotationAngle
                    }
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            )
        } ?: {
            // Placeholder if no album art
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            ) {
                // Animated placeholder when playing
                if (isPlaying) {
                    val pulse by remember { mutableStateOf(0.5f) }
                    LaunchedEffect(isPlaying) {
                        if (isPlaying) {
                            var current = pulse
                            while (isPlaying) {
                                current = if (current >= 1.0f) 0.0f else current + 0.01f
                                pulse.value = current
                                delay(16) // ~60fps
                            }
                        } else {
                            pulse.value = 0.5f
                        }
                    }
                    val pulseSize = 24.dp + (pulse * 16.dp)
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "No Album Art",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .size(pulseSize)
                            .alpha(0.8f)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "No Album Art",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EqualizerUI(
    equalizer: Equalizer?,
    bands: List<EqualizerBand>,
    onBandLevelChanged: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Equalizer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (equalizer == null || bands.isEmpty()) {
            Text(
                text = "Loading equalizer...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterStart)
            )
        } else {
            // Preset selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Preset: ${equalizer?.let { eq -> eq.getPresetName(eq.getPreset()) } ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall
                )
                // In a full implementation, we'd have a dropdown here to change presets
                // For now, we just show the current preset
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Band sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                bands.forEach { band ->
                    EqualizerBandSlider(
                        band = band,
                        onLevelChanged = { level ->
                            onBandLevelChanged(band.index, level)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EqualizerBandSlider(
    band: EqualizerBand,
    onLevelChanged: (Int) -> Unit
) {
    val level by remember { mutableStateOf(band.initialLevel) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${band.frequency / 1000}Hz",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(48.dp)
            )
            Text(
                text = "${band.level}dB",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .width(32.dp)
                    .align(Alignment.End)
            )
        }
        Slider(
            value = level.toFloat(),
            onValueChange = {
                val newLevel = it.roundToInt()
                level.value = newLevel
                onBandLevelChanged(newLevel)
            },
            valueRange = band.minLevel..band.maxLevel,
            steps = (band.maxLevel - band.minLevel).toInt(),
            thumb = { 
                SliderDefaults.Thumb(
                    color = MaterialTheme.colorScheme.primary,
                    size = 12.dp
                )
            },
            trackColor = MaterialTheme.colorScheme.primaryVariant,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
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
    // Background that pulses with the beat
    val pulse by remember { mutableStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            var current = pulse
            while (isPlaying) {
                current = (current + 0.02f) % 1.0f
                pulse.value = current
                delay(16) // ~60fps
            }
        } else {
            pulse.value = 0f
        }
    }
    
    // Base color that shifts hue with pulse
    val baseHue = (210 + pulse * 30) % 360
    val backgroundColor = Color(
        hue = baseHue,
        saturation = 0.3f,
        value = 0.1f + amplitude * 0.4f
    )
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(backgroundColor)
            .clip(RoundedCornerShape(20.dp))
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
                    val maxAmplitudeHeight = height * 0.4f // leave some margin
                    val centerY = height / 2f
                    
                    // Draw multiple spectrum bars below the waveform
                    val spectrumBars = 32
                    val barWidth = width / spectrumBars
                    val spectrumHeight = height * 0.25
                    
                    // Simple spectrum simulation from waveform (in real app, we'd use FFT)
                    for (i in 0 until spectrumBars) {
                        val spectrumX = i * barWidth
                        // Get energy from a portion of the waveform
                        val startIdx = (i * waveform.size / spectrumBars).coerceAtMost(waveform.size - 1)
                        val endIdx = (((i + 1) * waveform.size / spectrumBars) - 1).coerceAtMost(waveform.size - 1)
                        var maxEnergy = 0f
                        for (j in startIdx..endIdx) {
                            val absVal = kotlin.math.abs(waveform[j])
                            if (absVal > maxEnergy) {
                                maxEnergy = absVal
                            }
                        }
                        val barHeight = spectrumHeight * maxEnergy * 2.0 // Scale up
                        
                        // Color based on frequency (low to high: red to violet)
                        val hue = (i * 180 / spectrumBars).toInt() // 0-180 degrees (red to blue-green)
                        val barColor = Color(
                            hue = hue.toFloat(),
                            saturation = 0.8f,
                            brightness = 0.7f
                        )
                        
                        // Draw spectrum bar with glow
                        for (glow in 3 downto 1) {
                            val alpha = (0.15 * glow).coerceIn(0f, 0.4f)
                            val strokeWidth = (1.5 * glow).toFloat()
                            drawRect(
                                color = barColor.copy(alpha = alpha),
                                top = centerY + spectrumHeight/2 - barHeight/2,
                                left = spectrumX,
                                width = barWidth - 1,
                                height = barHeight,
                                strokeWidth = strokeWidth
                            )
                        }
                    }
                    
                    // Draw the main waveform path with enhanced glow
                    val path = android.graphics.Path().apply {
                        moveTo(0f, centerY - waveform[0] * maxAmplitudeHeight)
                        for (i in 1 until pointCount) {
                            val x = i * xStep
                            val y = centerY - waveform[i] * maxAmplitudeHeight
                            lineTo(x, y)
                        }
                    }
                    
                    // Multiple glow layers with rotating hues
                    val glowLayers = 5
                    val baseHue = (System.currentTimeMillis() / 20 % 360).toFloat() // Slow hue shift
                    for (layer in 0 until glowLayers) {
                        val layerOffset = (layer * 10).toInt()
                        val layerHue = (baseHue + layerOffset) % 360
                        val layerAlpha = (0.3 - layer * 0.05).coerceIn(0f, 0.3f)
                        val layerWidth = (2.0 + layer * 0.5).toFloat()
                        
                        val glowColor = Color(
                            hue = layerHue,
                            saturation = 0.7f,
                            brightness = 0.8f,
                            alpha = layerAlpha
                        )
                        
                        drawPath(
                            path = path,
                            color = glowColor,
                            strokeWidth = layerWidth
                        )
                    }
                    
                    // Main waveform path (brightest)
                    drawPath(
                        path = path,
                        color = Color(0xFF00BFA6), // teal accent
                        strokeWidth = 2f
                    )
                    
                    // Add sparkles at peaks
                    val sparkleProbability = 0.02 // 2% chance per point to draw a sparkle
                    val random = java.util.Random(System.currentTimeMillis())
                    for (i in 0 until pointCount) {
                        if (random.nextFloat() < sparkleProbability) {
                            val x = i * xStep
                            val y = centerY - waveform[i] * maxAmplitudeHeight
                            val sparkleSize = (4.0 + amplitude * 6.0).toFloat()
                            val sparkleColor = Color(
                                hue = (random.nextFloat() * 360),
                                saturation = 0.8f,
                                brightness = 0.9f,
                                alpha = 0.8f
                            )
                            drawCircle(
                                center = Offset(x, y),
                                radius = sparkleSize,
                                color = sparkleColor
                            )
                        }
                    }
                }
            } else {
                // Draw a placeholder pattern when not playing or no data
                val pulseOffset = (System.currentTimeMillis() / 100 % 10).toInt()
                for (i in 0 until 10) {
                    val x = (size.width / 10) * i
                    val y = size.height / 2
                    val barHeight = (size.height * 0.3) * (0.5 + 0.5 * kotlin.math.sin((i + pulseOffset) * 0.5))
                    drawRect(
                        color = Color(0xFF666666),
                        top = y - barHeight/2,
                        left = x - 1,
                        width = 3,
                        height = barHeight
                    )
                }
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

/**
 * Data class for an equalizer band.
 */
data class EqualizerBand(
    val index: Int,
    val frequency: Int,
    val initialLevel: Int,
    val minLevel: Int,
    val maxLevel: Int,
    var level: Int = 0
)

/**
 * Data class for a CUE track.
 */
data class CueTrack(
    val number: Int = 0,
    val title: String = "",
    val performer: String = "",
    val file: String = "",
    val startTimeMs: Long = 0
)
