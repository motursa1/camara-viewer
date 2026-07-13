package com.alanleiva.camaraviewer

import android.media.AudioManager
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
    private var watchdogRunning = false

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
        setVolumeToMax()

        binding.btnRecordings.setOnClickListener {
            startActivity(android.content.Intent(this, RecordingsActivity::class.java))
        }
    }

    private fun setVolumeToMax() {
        // Sube el volumen del stream de música al máximo posible del dispositivo
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        volumeControlStream = AudioManager.STREAM_MUSIC
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

    private fun buildLowLatencyLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 1000, 150, 300)
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
        exoPlayer.volume = 1.0f

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
                if (stalledChecks >= MAX_STALLED_CHECKS_BEFORE_RESTART) {
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

    private fun startWatchdog() {
        if (!watchdogRunning) {
            watchdogRunning = true
            mainHandler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS)
        }
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    // --- Ciclo de vida: se detiene por completo al salir de pantalla ---
    // Esto cubre tanto el botón "atrás" como el botón "home" o cambiar de app:
    // en cualquier caso, se libera el reproductor y deja de consumir datos/audio.

    override fun onStart() {
        super.onStart()
        setVolumeToMax()
        startPlayer()
        startWatchdog()
    }

    override fun onStop() {
        super.onStop()
        stopWatchdog()
        releasePlayer()
    }

    override fun onBackPressed() {
        // Libera todo explícitamente y cierra la app por completo,
        // sin dejarla viva en segundo plano ni en la lista de recientes.
        stopWatchdog()
        releasePlayer()
        finishAndRemoveTask()
    }
}
