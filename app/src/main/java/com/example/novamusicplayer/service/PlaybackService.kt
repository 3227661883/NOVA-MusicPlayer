package com.example.novamusicplayer.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Player
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DataSource
import androidx.media3.exoplayer.upstream.DefaultDataSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.Callback
import androidx.media3.session.MediaSession.Builder
import com.example.novamusicplayer.MainActivity
import com.example.novamusicplayer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class PlaybackService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var serviceBinder = LocalBinder()

    // Playback state
    data class PlaybackState(
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private val _playbackState = MutableStateFlow(PlaybackState(false, 0L, 0L))
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Visualizer data (waveform amplitude 0-255 per byte)
    private val _visualizerData = MutableStateFlow(ByteArray(0))
    val visualizerData: StateFlow<ByteArray> = _visualizerData.asStateFlow()

    // Equalizer
    private var equalizer: Equalizer? = null
    private val _equalizer = MutableStateFlow<Equalizer?>(null)
    val equalizer: StateFlow<Equalizer?> = _equalizer.asStateFlow()
    private val _equalizerPresetIndex = MutableStateFlow(0)
    val equalizerPresetIndex: StateFlow<Int> = _equalizerPresetIndex.asStateFlow()

    // Preset Reverb
    private var presetReverb: PresetReverb? = null
    private val _reverbPresetIndex = MutableStateFlow(0)
    val reverbPresetIndex: StateFlow<Int> = _reverbPresetIndex.asStateFlow()

    // Album art URI (can be null)
    private val _albumArtUri = MutableStateFlow<Uri?>(null)
    val albumArtUri: StateFlow<Uri?> = _albumArtUri.asStateFlow()

    private var visualizer: Visualizer? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        initializePlayer()
        initializeMediaSession()
        startForeground(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        return serviceBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Keep service running until explicitly stopped
        return true
    }

    override fun onDestroy() {
        visualizer?.release()
        equalizer?.release()
        presetReverb?.release()
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    private fun initializePlayer() {
        // Use low latency audio attributes for AAudio if possible
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .setAllowedCaptureCapabilities(C.CAPTURE_CAPABILITY_ALLOW_CAPTURE_BY_SYSTEM)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
            }

            override fun onPositionChanged(
                playWhenReady: Boolean,
                positionMs: Long,
                bufferedPositionMs: Long
            ) {
                updatePlaybackState()
            }

            override fun onMediaItemChanged(mediaItem: MediaItem?) {
                updateMetadata()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // Update duration when available
                if (player.duration != C.TIME_UNSET) {
                    updatePlaybackState()
                }
            }
        })
    }

    private fun initializeMediaSession() {
        mediaSession = Builder(this, "NOVA Music Player Session").build()
        mediaSession.callback = object : Callback() {
            override fun onPlay() {
                player.play()
            }

            override fun onPause() {
                player.pause()
            }

            override fun onStop() {
                player.stop()
            }

            override fun onSeekTo(positionMs: Long) {
                player.seekTo(positionMs)
            }
        }
        mediaSession.isActive = true

        // Initialize Visualizer if we have an audio session
        val sessionId = player.audioSessionId
        if (sessionId != 0) {
            visualizer = Visualizer(sessionId).apply {
                // Capture size: power of two between 32 and 32768
                val captureSize = Visualizer.getCaptureSizeRange()[1] // max
                setCaptureSize(captureSize)
                dataCaptureListener = Visualizer.OnDataCaptureListener(
                    { visualizer, waveform, timestamp ->
                        // waveform is a byte array, copy to flow
                        _visualizerData.update { waveform.clone() }
                    },
                    Visualizer.getMaxCaptureRate() // we want waveform, not fft
                ) { visualizer, fft, timestamp ->
                    // FFT not used
                }
                enabled = true
            }
        }

        // Initialize Equalizer if we have an audio session
        if (sessionId != 0) {
            equalizer = Equalizer(0, sessionId).apply {
                // Enable the equalizer
                enabled = true
                // Try to use a preset if available, otherwise we can adjust bands later
                if (numberOfPresets > 0) {
                    // Use the first preset as default (often "Normal" or "Bass Boost")
                    usePreset(0)
                    _equalizerPresetIndex.value = 0
                    Log.d("PlaybackService", "Equalizer preset set to: ${getPresetName(0)}")
                }
                // Expose the equalizer instance
                _equalizer.value = this
            }
        }

        // Initialize PresetReverb if we have an audio session
        if (sessionId != 0) {
            presetReverb = PresetReverb(0, sessionId).apply {
                enabled = true
                // Use the first preset as default
                if (numberOfPresets > 0) {
                    preset = 0
                    _reverbPresetIndex.value = 0
                    Log.d("PlaybackService", "Reverb preset set to: ${getPresetName(0)}")
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NOVA Music Player")
            .setContentText("Playing music")
            .setSmallIcon(R.drawable.ic_music_note) // You'll need to add this icon later
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updatePlaybackState() {
        val isPlaying = player.isPlaying
        val positionMs = when {
            player.currentPosition != C.TIME_UNSET -> player.currentPosition
            else -> 0L
        }
        val durationMs = when {
            player.duration != C.TIME_UNSET -> player.duration
            else -> 0L
        }
        _playbackState.value = PlaybackState(isPlaying, positionMs, durationMs)

        // Update media session state
        mediaSession.setCurrentPositionAndPlaybackState(
            positionMs,
            when {
                player.isPlaying -> Player.State.PLAYING
                !player.isPlaying -> Player.State.PAUSED
                else -> Player.State.IDLE
            }
        )
    }

    private fun updateMetadata() {
        player.currentMediaItem?.mediaMetadata?.let { metadata ->
            mediaSession.setMediaItem(
                androidx.media3.session.MediaSession.MediaItem(
                    mediaItem.mediaId,
                    mediaItem.clipping?.startPositionMs ?: 0L,
                    mediaItem.clipping?.endPositionMs ?: C.TIME_UNSET,
                    metadata.title ?: "",
                    metadata.artist ?: "",
                    metadata.albumArtUri ?: null
                )
            )
            // Update album art flow
            _albumArtUri.value = metadata.albumArtUri
        }
    }

    /** Called by UI to set a new audio source */
    fun setUri(uri: Uri) {
        // Clear previous media item
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .build()
        )
        player.prepare()
        updateMetadata()
        updatePlaybackState()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun stop() {
        player.stop()
    }

    /** Expose playback state as a StateFlow for UI collection */
    fun getPlaybackState(): StateFlow<PlaybackState> = playbackState

    /** Expose visualizer data */
    fun getVisualizerData(): StateFlow<ByteArray> = visualizerData

    /** Expose album art URI */
    fun getAlbumArtUri(): StateFlow<Uri?> = albumArtUri

    /** Expose equalizer preset index */
    fun getEqualizerPresetIndex(): StateFlow<Int> = equalizerPresetIndex

    /** Get list of preset names for Equalizer */
    fun getEqualizerPresetNames(): List<String> {
        return equalizer?.let {
            val names = mutableListOf<String>()
            for (i in 0 until numberOfPresets) {
                names.add(getPresetName(i))
            }
            names
        } ?: emptyList()
    }

    /** Set equalizer preset by index */
    fun setEqualizerPreset(index: Int) {
        equalizer?.usePreset(index)
        _equalizerPresetIndex.value = index
    }

    /** Expose reverb preset index */
    fun getReverbPresetIndex(): StateFlow<Int> = reverbPresetIndex

    /** Get list of preset names for PresetReverb */
    fun getReverbPresetNames(): List<String> {
        return presetReverb?.let {
            val names = mutableListOf<String>()
            for (i in 0 until numberOfPresets) {
                names.add(getPresetName(i))
            }
            names
        } ?: emptyList()
    }

    /** Set reverb preset by index */
    fun setReverbPreset(index: Int) {
        presetReverb?.preset = index
        _reverbPresetIndex.value = index
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "nova_music_player_channel"
    }
}
