package com.alanleiva.camaraviewer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.alanleiva.camaraviewer.databinding.ActivityRecordingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private var currentDir: String? = null

    // Patrones de nombre usados por el firmware yi-hack para carpetas (por hora) y archivos mp4
    private val dirPattern = Regex("""\d{4}Y\d{2}M\d{2}D\d{2}H""")
    private val filePattern = Regex("""\d{2}M\d{2}S\d+\.mp4""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        loadDirectories()
    }

    override fun onBackPressed() {
        if (currentDir != null) {
            currentDir = null
            loadDirectories()
        } else {
            super.onBackPressed()
        }
    }

    private fun baseUrl(): String {
        val ip = getString(R.string.camera_ip)
        val port = getString(R.string.camera_http_port)
        return "http://$ip:$port"
    }

    private fun loadDirectories() {
        binding.titleText.text = "Grabaciones"
        showLoading(true)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    NetworkUtils.getText("${baseUrl()}/cgi-bin/eventslist.sh")
                }
                val dirs = dirPattern.findAll(text).map { it.value }.distinct().sortedDescending().toList()
                showLoading(false)
                if (dirs.isEmpty()) {
                    Toast.makeText(this@RecordingsActivity, "No se encontraron carpetas de grabaciones", Toast.LENGTH_LONG).show()
                }
                binding.recyclerView.adapter = SimpleListAdapter(dirs) { dir ->
                    currentDir = dir
                    loadFiles(dir)
                }
            } catch (e: Exception) {
                Log.e("CamaraViewer", "Error listando carpetas: ${e.message}")
                showLoading(false)
                Toast.makeText(this@RecordingsActivity, "Error al conectar con la cámara", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadFiles(dir: String) {
        binding.titleText.text = dir
        showLoading(true)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    NetworkUtils.getText("${baseUrl()}/cgi-bin/eventsfile.sh?dirname=$dir")
                }
                val files = filePattern.findAll(text).map { it.value }.distinct().sortedDescending().toList()
                showLoading(false)
                if (files.isEmpty()) {
                    Toast.makeText(this@RecordingsActivity, "No hay videos en esta carpeta", Toast.LENGTH_LONG).show()
                }
                binding.recyclerView.adapter = SimpleListAdapter(files) { file ->
                    playRecording(dir, file)
                }
            } catch (e: Exception) {
                Log.e("CamaraViewer", "Error listando archivos: ${e.message}")
                showLoading(false)
                Toast.makeText(this@RecordingsActivity, "Error al conectar con la cámara", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playRecording(dir: String, file: String) {
        val url = "${baseUrl()}/record/$dir/$file"
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_URL, url)
        startActivity(intent)
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (loading) View.GONE else View.VISIBLE
    }
}
