package com.alanleiva.camaraviewer

import android.net.Uri
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alanleiva.camaraviewer.databinding.ActivityMainBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    // ===============================================================
    //  AUDIO BOOST (ecualizador nativo de VLC)
    //  preAmp y las bandas van de -20 a +20 dB. El volumen de VLC
    //  admite hasta 200 (%), que suma ~6 dB mas.
    //  Formato de cada nivel: preAmp, ganancia de bandas, volumen, etiqueta
    // ===============================================================
    private data class Nivel(
        val preAmp: Float,
        val bandas: Float,
        val volumen: Int,
        val etiqueta: String
    )

    private val NIVELES = listOf(
        Nivel(0f, 0f, 100, "NORMAL"),
        Nivel(10f, 0f, 100, "+10 dB"),
        Nivel(20f, 6f, 150, "+26 dB"),
        Nivel(20f, 20f, 200, "MAX")
    )
    private var nivelActual = 0

    // ===============================================================
    //  ZOOM
    // ===============================================================
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var escala = 1f
    private var desplX = 0f
    private var desplY = 0f
    private val ESCALA_MIN = 1f
    private val ESCALA_MAX = 8f

    // ===============================================================
    //  WATCHDOG
    //  En VLC la senal de "esto sigue vivo" es el evento TimeChanged.
    // ===============================================================
    @Volatile
    private var ultimoAvanceMs = 0L
    private var reconectando = false

    private val WATCHDOG_INTERVAL_MS = 500L
    private val UMBRAL_AVISO_MS = 1500L
    private val UMBRAL_RECONECTAR_MS = 5000L
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) activarPantallaCompleta()
    }

    private fun activarPantallaCompleta() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, binding.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------------------------------------------------------
    //  SALIDA FORZADA
    // ---------------------------------------------------------------
    private fun salirForzado() {
        mainHandler.removeCallbacksAndMessages(null)
        liberarTodo()
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
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
                    e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
                ): Boolean {
                    if (escala > 1f) {
                        desplX -= dx
                        desplY -= dy
                        aplicarTransformacion()
                    }
                    return true
                }

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
        val vl = binding.videoLayout
        val maxX = (vl.width * (escala - 1f)) / 2f
        val maxY = (vl.height * (escala - 1f)) / 2f
        desplX = desplX.coerceIn(-maxX, maxX)
        desplY = desplY.coerceIn(-maxY, maxY)

        vl.scaleX = escala
        vl.scaleY = escala
        vl.translationX = desplX
        vl.translationY = desplY

        if (escala > 1.02f) {
            binding.txtZoom.text = String.format("%.1fx", escala)
            binding.txtZoom.visibility = View.VISIBLE
        } else {
            binding.txtZoom.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------
    //  BOOST
    // ---------------------------------------------------------------
    private fun ciclarBoost() {
        nivelActual = (nivelActual + 1) % NIVELES.size
        aplicarBoost()
        binding.btnBoost.text = NIVELES[nivelActual].etiqueta
    }

    private fun aplicarBoost() {
        val mp = mediaPlayer ?: return
        val n = NIVELES[nivelActual]
        try {
            if (nivelActual == 0) {
                mp.setEqualizer(null)
            } else {
                val eq = MediaPlayer.Equalizer.create()
                eq.preAmp = n.preAmp
                for (i in 0 until MediaPlayer.Equalizer.getBandCount()) {
                    eq.setAmp(i, n.bandas)
                }
                mp.setEqualizer(eq)
            }
            mp.volume = n.volumen
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo aplicar boost: ${e.message}")
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
        liberarTodo()
        reconectando = false
        ultimoAvanceMs = 0L
        mostrarEstado("Conectando...")

        // Opciones globales de VLC.
        val opciones = arrayListOf(
            "--rtsp-tcp",              // RTP interleaved sobre TCP
            "--network-caching=1000",  // cache de red: el default de VLC
            "--clock-jitter=0",        // no intentar corregir jitter de reloj
            "--clock-synchro=0",
            "--drop-late-frames",      // preferir tirar frames a acumular atraso
            "--skip-frames",
            "--no-audio-time-stretch",
            "--avcodec-fast"
        )

        val vlc = LibVLC(this, opciones)
        val mp = MediaPlayer(vlc)

        // textureView = true: se renderiza sobre un TextureView, que si
        // acepta transformaciones de matriz. Con SurfaceView el zoom
        // por pellizco no se comporta bien.
        mp.attachViews(binding.videoLayout, null, false, true)

        val media = Media(vlc, Uri.parse(buildRtspUrl()))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=1000")
        media.addOption(":rtsp-tcp")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        mp.media = media
        media.release()

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.TimeChanged -> {
                    ultimoAvanceMs = SystemClock.elapsedRealtime()
                    mainHandler.post { ocultarEstado() }
                }
                MediaPlayer.Event.Playing -> {
                    ultimoAvanceMs = SystemClock.elapsedRealtime()
                    mainHandler.post { ocultarEstado() }
                }
                MediaPlayer.Event.Buffering -> {
                    val pct = ev.buffering
                    if (pct >= 100f) {
                        ultimoAvanceMs = SystemClock.elapsedRealtime()
                    } else {
                        mainHandler.post { mostrarEstado("Sin senal... ${pct.toInt()}%") }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    mainHandler.post { programarReconexion("Error de reproduccion") }
                }
                MediaPlayer.Event.EndReached -> {
                    mainHandler.post { programarReconexion("Stream terminado") }
                }
            }
        }

        libVlc = vlc
        mediaPlayer = mp

        aplicarBoost()
        mp.play()
    }

    private fun programarReconexion(msg: String) {
        if (reconectando) return
        reconectando = true
        mostrarEstado(msg)
        mainHandler.postDelayed({ initializePlayer() }, RECONNECT_DELAY_MS)
    }

    private fun revisarSalud() {
        if (reconectando) return
        if (ultimoAvanceMs == 0L) return

        val dt = SystemClock.elapsedRealtime() - ultimoAvanceMs
        when {
            dt > UMBRAL_RECONECTAR_MS -> {
                Log.w(TAG, "Congelado ${dt}ms -> reconectando")
                programarReconexion("Video congelado, reconectando...")
            }
            dt > UMBRAL_AVISO_MS -> {
                mostrarEstado("Sin video (${dt / 1000}s)")
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

    private fun liberarTodo() {
        mediaPlayer?.let {
            it.setEventListener(null)
            it.stop()
            it.detachViews()
            it.release()
        }
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacksAndMessages(null)
        liberarTodo()
    }

    @Deprecated("Compatibilidad con APIs anteriores")
    override fun onBackPressed() {
        salirForzado()
    }

    companion object {
        private const val TAG = "CamaraViewer"
    }
}
