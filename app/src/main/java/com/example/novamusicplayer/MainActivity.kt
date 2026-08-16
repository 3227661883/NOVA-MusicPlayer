package com.example.novamusicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.novamusicplayer.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaTheme {
                // A surface container using the 'background' color from the theme
                NovaMusicPlayerApp()
            }
        }
    }
}

@Composable
fun NovaMusicPlayerApp() {
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember {
        // Initialize ExoPlayer
        androidx.media3.exoplayer.ExoPlayer.Builder(LocalContext.current)
            .setHandleAudioBecomingNoisy(true)
            .build().also { it ->
                // Set the media item
                val mediaItem = androidx.media3.exoplayer.MediaItem.fromUri(
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
                )
                it.setMediaItem(mediaItem)
                it.prepare()
            }
    }

    // Release the player when the composition leaves the composition
    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

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

        // Play/Pause button
        Button(
            onClick = {
                if (isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
                isPlaying = !isPlaying
            },
            modifier = Modifier.width(120.dp)
        ) {
            Text(text = if (isPlaying) "Pause" else "Play")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Current state
        Text(
            text = "State: ${if (isPlaying) "Playing" else "Paused"}",
            style = MaterialTheme.typography.bodyMedium
        )

        // We can add more UI elements here, like a progress bar, etc.
        // For MVP, this is enough to test playback.
    }
}
