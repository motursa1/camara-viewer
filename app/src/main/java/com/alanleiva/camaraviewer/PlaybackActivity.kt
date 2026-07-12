package com.alanleiva.camaraviewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.alanleiva.camaraviewer.databinding.ActivityPlaybackBinding

class PlaybackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    private lateinit var binding: ActivityPlaybackBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.playerView.player = exoPlayer

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
