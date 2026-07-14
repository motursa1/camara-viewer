package com.alanleiva.camaraviewer

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.alanleiva.camaraviewer.databinding.ActivityMainBinding

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ================== AUDIO BOOST ==================
    // LoudnessEnhancer amplifica por software el audio del stream.
    // El valor va en milibelios (mB): 1000 mB = 10 dB.
    private var loudness: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0

    // Niveles del boton: 0 = normal, luego +10, +20, +30 dB
    private val NIVELES_BOOST = intArrayOf(0, 1000, 2000, 3000)
    private val ETIQUETAS = arrayOf("🔊 NORMAL", "🔊 +10 dB", "🔊 +20 dB", "🔊 MAX +30 dB")
    private var nivelActual = 0

    // ================== WATCHDOG ==================
    private var lastCheckedPosition = -1L
    private var stalledChecks = 0
    private val CHECK_INTERVAL_MS = 5000L
    private val MAX_STALLED_CHECKS_BEFORE_RESTART = 3
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnBoost.setOnClickListener { ciclarBoost() }
    }

    // ---------------------------------------------------------------
    // BOTON BOOST
    // ---------------------------------------------------------------
    private fun ciclarBoost() {
        nivelActual = (nivelActual + 1) % NIVELES_BOOST.size
        aplicarBoost()
        binding.btnBoost.text = ETIQUETAS[nivelActual]

        // Al activar boost, subimos tambien el volumen del sistema al maximo
        if (nivelActual > 0) subirVolumenSistemaAlMaximo()
    }

    private fun aplicarBoost() {
        val gananciaMb = NIVELES_BOOST[nivelActual]
        try {
            if (loudness == null && audioSessionId != 0) {
                loudness = LoudnessEnhancer(audioSessionId)
            }
            loudness?.let {
                it.setTargetGain(gananciaMb)
                it.enabled = gananciaMb > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo aplicar boost: ${e.message}")
        }
    }

    private fun subirVolumenSistemaAlMaximo() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo subir volumen: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    // PLAYER
    // ---------------------------------------------------------------
    private fun buildRtspUrl(): String {
        val ip = getString(R.string.camera_ip)
        val port = getString(R.string.camera_rtsp_port)
        val path = getString(R.string.camera_stream_path)
        val user = getString(R.string.camera_user)
        val pass = getString(R.string.camera_pass)
        val auth = if (user.isNotEmpty()) "$user:$pass@" else ""
        return "rtsp://$auth$ip:$port/$path"
    }

    private fun initializePlayer() {
        releasePlayer()

        // ---- BUFFER ANTI-MICROCORTES ----
        // Estos valores son la clave contra los congelamientos de 1-2 segundos.
        // minBuffer 4s / maxBuffer 12s: hay reserva suficiente para cubrir
        // un hueco del encoder de la camara sin que la imagen se detenga.
        // bufferForPlayback 2000ms = latencia inicial de ~2s (aceptable en vigilancia).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                4000,   // minBufferMs
                12000,  // maxBufferMs
                2000,   // bufferForPlaybackMs
                3000    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Sesion de audio propia y estable: sin esto el LoudnessEnhancer
        // se pierde cada vez que el reproductor se recrea.
        if (audioSessionId == 0) {
            audioSessionId = Util.generateAudioSessionIdV21(this)
        }

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(buildRtspUrl()))

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        exo.setAudioSessionId(audioSessionId)
        exo.setMediaSource(mediaSource)
        exo.playWhenReady = true
        exo.volume = 1.0f

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Error de reproduccion: ${error.errorCodeName}")
                mostrarEstado("Reconectando...")
                mainHandler.postDelayed({ initializePlayer() }, RECONNECT_DELAY_MS)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> ocultarEstado()
                    Player.STATE_BUFFERING -> { /* silencioso: es normal */ }
                    Player.STATE_IDLE -> mostrarEstado("Conectando...")
                }
            }
        })

        binding.playerView.player = exo
        exo.prepare()
        player = exo

        // Reconstruir el efecto sobre la nueva sesion
        loudness?.release()
        loudness = null
        aplicarBoost()

        lastCheckedPosition = -1L
        stalledChecks = 0
    }

    private fun checkStreamHealth() {
        val p = player ?: return
        val pos = p.currentPosition

        if (p.playbackState == Player.STATE_READY && pos == lastCheckedPosition) {
            stalledChecks++
            Log.w(TAG, "Stream congelado ($stalledChecks)")
            if (stalledChecks >= MAX_STALLED_CHECKS_BEFORE_RESTART) {
                mostrarEstado("Reconectando...")
                initializePlayer()
            }
        } else {
            stalledChecks = 0
        }
        lastCheckedPosition = pos
    }

    private fun mostrarEstado(txt: String) {
        binding.txtEstado.text = txt
        binding.txtEstado.visibility = View.VISIBLE
    }

    private fun ocultarEstado() {
        binding.txtEstado.visibility = View.GONE
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        mainHandler.postDelayed(watchdogRunnable, CHECK_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(watchdogRunnable)
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        loudness?.release()
        loudness = null
    }

    companion object {
        private const val TAG = "CamaraViewer"
    }
}
