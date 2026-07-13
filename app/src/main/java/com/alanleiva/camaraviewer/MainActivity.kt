package com.alanleiva.camaraviewer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.alanleiva.camaraviewer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastCheckedPosition = -1L
    private var stalledChecks = 0
    private val CHECK_INTERVAL_MS = 5000L
    private val MAX_STALLED_CHECKS_BEFORE_RESTART = 2
    private val RECONNECT_DELAY_MS = 3000L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkStreamHealth()
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        binding.btnRecordings.setOnClickListener {
            startActivity(android.content.Intent(this, RecordingsActivity::class.java))
        }

        startPlayer()
        mainHandler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS)
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun buildRtspUrl(): String {
        val ip = getString(R.string.camera_ip)
        val port = getString(R.string.camera_rtsp_port)
        val path = getString(R.string.camera_stream_path)
        val user = getString(R.string.camera_user)
        val pass = getString(R.string.camera_pass)
        val auth = if (user.isNotEmpty()) "$user:$pass@" else ""
        return "rtsp://$auth$ip:$port/$path"
    }

    // Buffer mínimo: prioriza latencia baja sobre fluidez perfecta.
    // Si notas cortes/tartamudeo con esto, sube un poco estos valores.
    private fun buildLowLatencyLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                300,   // minBufferMs
                1000,  // maxBufferMs
                150,   // bufferForPlaybackMs
                300    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun startPlayer() {
        releasePlayer()
        setStatus("Conectando...")

        val exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(buildLowLatencyLoadControl())
            .build()
        player = exoPlayer
        binding.playerView.player = exoPlayer

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(buildRtspUrl()))

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> setStatus("")
                    Player.STATE_BUFFERING -> setStatus("Cargando...")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("CamaraViewer", "Error de reproducción: ${error.message}")
                setStatus("Reconectando...")
                scheduleReconnect()
            }
        })

        lastCheckedPosition = -1L
        stalledChecks = 0
    }

    private fun checkStreamHealth() {
        val p = player ?: return
        val playbackState = p.playbackState
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            Log.w("CamaraViewer", "Estado inválido detectado ($playbackState), reiniciando reproductor")
            scheduleReconnect()
            return
        }
        val currentPosition = p.currentPosition
        if (playbackState == Player.STATE_READY && p.playWhenReady) {
            if (currentPosition == lastCheckedPosition) {
                stalledChecks++
                Log.w("CamaraViewer", "Posible congelamiento detectado ($stalledChecks/$MAX_STALLED_CHECKS_BEFORE_RESTART)")
                if (stalledChecks >= MAX_STALLED_CHECKS_BEFORE_RESTART) {
                    Log.w("CamaraViewer", "Imagen congelada confirmada, reiniciando reproductor")
                    setStatus("Reconectando...")
                    scheduleReconnect()
                    return
                }
            } else {
                stalledChecks = 0
            }
        }
        lastCheckedPosition = currentPosition
    }

    private fun scheduleReconnect() {
        releasePlayer()
        mainHandler.postDelayed({ startPlayer() }, RECONNECT_DELAY_MS)
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
        binding.statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            startPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdogRunnable)
        releasePlayer()
    }
}
