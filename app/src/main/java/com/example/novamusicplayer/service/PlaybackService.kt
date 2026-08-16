package com.example.novamusicplayer.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.Callback
import androidx.media3.session.MediaSession.Builder
import com.example.novamusicplayer.MainActivity
import com.example.novamusicplayer.R

class PlaybackService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var serviceBinder = LocalBinder()

    // Simple playback state holder
    data class PlaybackState(
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private val playbackState = androidx.compose.runtime.StateFlow(PlaybackState(false, 0L, 0L))

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
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build()
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
        playbackState.value = PlaybackState(isPlaying, positionMs, durationMs)

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
        }
    }

    /** Called by UI to set a new audio source */
    fun setUri(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
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
    fun getPlaybackState(): androidx.compose.runtime.StateFlow<PlaybackState> = playbackState.asStateFlow()

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "nova_music_player_channel"
    }
}
