package com.alanleiva.camaraviewer

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.alanleiva.camaraviewer.databinding.ActivityMainBinding
import kotlin.system.exitProcess

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ===============================================================
    //  TRANSPORTE RTSP
    //  true = fuerza RTP sobre TCP (interleaved).
    //  VLC negocia UDP y cae solo a TCP si no llega el RTP; Media3 no
    //  lo hace de forma confiable y se queda en "Sin senal" para siempre.
    //  Ponelo en false solo para experimentar.
    // ===============================================================
    private val FORZAR_TCP = true

    // ===============================================================
    //  AUDIO BOOST  (LoudnessEnhancer, ganancia en milibelios)
    // ===============================================================
    private var loudness: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0
    private val NIVELES_BOOST = intArrayOf(0, 1000, 2000, 3000)
    private val ETIQUETAS = arrayOf("NORMAL", "+10 dB", "+20 dB", "MAX +30 dB")
    private var nivelActual = 0

    // ===============================================================
    //  ZOOM POR PELLIZCO
    // ===============================================================
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var escala = 1f
    private var desplX = 0f
    private var desplY = 0f
    private val ESCALA_MIN = 1f
    private val ESCALA_MAX = 8f

    // ===============================================================
    //  DETECCION DE CONGELAMIENTO
    //  Se mide por frames REALMENTE renderizados, no por currentPosition
    //  (que sigue avanzando aunque la imagen este congelada).
    //  Se escribe desde el hilo de reproduccion -> @Volatile.
    // ===============================================================
    @Volatile
    private var ultimoFrameMs = 0L
    private var reconectando = false

    // Se guarda la referencia porque para quitarlo hay que pasarla a
    // clearVideoFrameMetadataListener (no acepta null).
    private val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
        ultimoFrameMs = SystemClock.elapsedRealtime()
    }

    private val WATCHDOG_INTERVAL_MS = 500L
    private val UMBRAL_AVISO_MS = 1500L      // muestra "sin video"
    private val UMBRAL_RECONECTAR_MS = 5000L // recrea el reproductor
    private val RECONNECT_DELAY_MS = 500L

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            revisarSalud()
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activarPantallaCompleta()

        binding.btnBoost.setOnClickListener { ciclarBoost() }
        binding.btnSalir.setOnClickListener { salirForzado() }
        configurarZoom()
    }

    // ---------------------------------------------------------------
    //  SALIDA FORZADA
    //  Libera todo y mata el proceso: la app no queda en segundo plano
    //  consumiendo datos del stream RTSP.
    // ---------------------------------------------------------------
    private fun salirForzado() {
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        loudness?.release()
        loudness = null
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) activarPantallaCompleta()
    }

    // ---------------------------------------------------------------
    //  PANTALLA COMPLETA (inmersiva)
    // ---------------------------------------------------------------
    private fun activarPantallaCompleta() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, binding.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------------------------------------------------------
    //  ZOOM
    // ---------------------------------------------------------------
    private fun configurarZoom() {
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    escala = (escala * d.scaleFactor).coerceIn(ESCALA_MIN, ESCALA_MAX)
                    aplicarTransformacion()
                    return true
                }
            })

        gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent,
                    dx: Float, dy: Float
                ): Boolean {
                    if (escala > 1f) {
                        desplX -= dx
                        desplY -= dy
                        aplicarTransformacion()
                    }
                    return true
                }

                // Doble toque = volver a 1x
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    escala = 1f
                    desplX = 0f
                    desplY = 0f
                    aplicarTransformacion()
                    return true
                }
            })

        binding.zoomContainer.setOnTouchListener { v, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            v.performClick()
            true
        }
    }

    private fun aplicarTransformacion() {
        val pv = binding.playerView
        // Limitar el paneo para no arrastrar la imagen fuera de la pantalla
        val maxX = (pv.width * (escala - 1f)) / 2f
        val maxY = (pv.height * (escala - 1f)) / 2f
        desplX = desplX.coerceIn(-maxX, maxX)
        desplY = desplY.coerceIn(-maxY, maxY)

        pv.scaleX = escala
        pv.scaleY = escala
        pv.translationX = desplX
        pv.translationY = desplY

        if (escala > 1.02f) {
            binding.txtZoom.text = String.format("%.1fx", escala)
            binding.txtZoom.visibility = View.VISIBLE
        } else {
            binding.txtZoom.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------
    //  AUDIO BOOST
    // ---------------------------------------------------------------
    private fun ciclarBoost() {
        nivelActual = (nivelActual + 1) % NIVELES_BOOST.size
        aplicarBoost()
        binding.btnBoost.text = ETIQUETAS[nivelActual]
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
            am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo subir volumen: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    //  REPRODUCTOR
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
        reconectando = false
        ultimoFrameMs = 0L
        mostrarEstado("Conectando...")

        // ---- BUFFER: EQUIVALENTE A LA CACHE DE RED DE VLC ----
        // VLC usa ~1000ms por defecto y practicamente no se cae. Copiamos
        // ese criterio: suficiente para cubrir un hueco del encoder, sin
        // acumular atraso como pasaba con 4000ms.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1000,  // minBufferMs
                3000,  // maxBufferMs
                800,   // bufferForPlaybackMs
                1000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        if (audioSessionId == 0) {
            audioSessionId = Util.generateAudioSessionIdV21(this)
        }

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(FORZAR_TCP)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(buildRtspUrl()))

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        exo.setAudioSessionId(audioSessionId)
        exo.setMediaSource(mediaSource)
        exo.playWhenReady = true
        exo.volume = 1.0f

        // Marca de tiempo de cada frame que el decodificador entrega a
        // pantalla. Es la unica senal confiable de "la imagen esta viva".
        exo.setVideoFrameMetadataListener(frameListener)

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Error: ${error.errorCodeName}", error)
                // El codigo se muestra en pantalla: sirve para distinguir
                // un fallo de SDP/transporte de un timeout de red.
                programarReconexion("Error: ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> mostrarEstado("Conectando...")
                    Player.STATE_BUFFERING -> mostrarEstado("Sin senal...")
                    Player.STATE_READY -> { /* el watchdog decide si ocultar */ }
                    Player.STATE_ENDED -> programarReconexion("Stream terminado...")
                }
            }
        })

        binding.playerView.player = exo
        exo.prepare()
        player = exo

        loudness?.release()
        loudness = null
        aplicarBoost()
    }

    private fun programarReconexion(msg: String) {
        if (reconectando) return
        reconectando = true
        mostrarEstado(msg)
        mainHandler.postDelayed({ initializePlayer() }, RECONNECT_DELAY_MS)
    }

    private fun revisarSalud() {
        val p = player ?: return
        if (reconectando) return

        // Aun no llega el primer frame: el listener del estado ya avisa.
        if (ultimoFrameMs == 0L) return

        val dt = SystemClock.elapsedRealtime() - ultimoFrameMs

        when {
            dt > UMBRAL_RECONECTAR_MS -> {
                Log.w(TAG, "Congelado ${dt}ms -> reconectando")
                programarReconexion("Video congelado, reconectando...")
            }
            dt > UMBRAL_AVISO_MS -> {
                mostrarEstado("Sin video (${dt / 1000}s)")
            }
            p.playbackState == Player.STATE_READY -> {
                ocultarEstado()
            }
        }
    }

    private fun mostrarEstado(txt: String) {
        binding.txtEstado.text = txt
        binding.txtEstado.visibility = View.VISIBLE
    }

    private fun ocultarEstado() {
        binding.txtEstado.visibility = View.GONE
    }

    private fun releasePlayer() {
        player?.clearVideoFrameMetadataListener(frameListener)
        player?.release()
        player = null
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    // El boton "atras" tambien cierra del todo: nada de segundo plano.
    @Deprecated("Compatibilidad con APIs anteriores")
    override fun onBackPressed() {
        salirForzado()
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
