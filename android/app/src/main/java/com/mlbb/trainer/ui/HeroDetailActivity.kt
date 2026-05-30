package com.mlbb.trainer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mlbb.trainer.R
import com.mlbb.trainer.database.AppDatabase
import com.mlbb.trainer.database.Hero
import com.mlbb.trainer.database.YouTubeVideo
import com.mlbb.trainer.overlay.GameOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class HeroDetailActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private var heroId: Long = -1
    private var heroName: String = ""
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var modelStatusText: TextView
    private lateinit var modelFileNameText: TextView
    private lateinit var yoloStatusText: TextView
    private lateinit var combosStatusText: TextView

    private val modelFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri, "action")
    }

    private val yoloFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri, "yolo")
    }

    private val combosFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri, "combos")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hero_detail)

        heroId = intent.getLongExtra("hero_id", -1)
        heroName = intent.getStringExtra("hero_name") ?: ""

        database = AppDatabase.getDatabase(this)

        findViewById<TextView>(R.id.heroDetailTitle).text = heroName

        modelStatusText = findViewById(R.id.modelStatusText)
        modelFileNameText = findViewById(R.id.modelFileNameText)
        yoloStatusText = findViewById(R.id.yoloStatusText)
        combosStatusText = findViewById(R.id.combosStatusText)

        findViewById<Button>(R.id.addVideoButton).setOnClickListener { showAddVideoDialog() }
        findViewById<Button>(R.id.importModelButton).setOnClickListener { importModel() }
        findViewById<Button>(R.id.importYoloButton).setOnClickListener { importYolo() }
        findViewById<Button>(R.id.importCombosButton).setOnClickListener { importCombos() }
        findViewById<Button>(R.id.openOverlayButton).setOnClickListener { openOverlay() }

        val videoList = findViewById<RecyclerView>(R.id.videoListView)
        videoList.layoutManager = LinearLayoutManager(this)
        videoAdapter = VideoAdapter(
            onDelete = { video -> deleteVideo(video) },
            onOpen = { video -> openYouTube(video.url) }
        )
        videoList.adapter = videoAdapter

        loadHeroInfo()
        loadVideos()
    }

    private fun loadHeroInfo() {
        lifecycleScope.launch {
            val hero = database.heroDao().getHeroById(heroId)
            if (hero != null) {
                updateModelUI(hero)
            }
        }
    }

    private fun updateModelUI(hero: Hero) {
        val heroDir = File(getExternalFilesDir(null), "MLBB_AI")
        val yoloFile = File(heroDir, "model_${heroId}_yolo.tflite")
        val combosFile = File(heroDir, "model_${heroId}_combos.json")

        when (hero.modelStatus) {
            "none" -> {
                modelStatusText.text = "Model: Import qilinmagan"
                modelFileNameText.text = "Bu qahramon uchun .tflite faylini import qiling"
            }
            "ready" -> {
                modelStatusText.text = "Model: Tayyor ✓"
                val name = hero.modelPath.split("/").lastOrNull() ?: hero.modelPath
                modelFileNameText.text = "Fayl: $name"
            }
            "error" -> {
                modelStatusText.text = "Model: Xato ✗"
                modelFileNameText.text = hero.modelPath
            }
        }

        yoloStatusText.text = if (yoloFile.exists()) "YOLO: Tayyor ✓" else "YOLO: Yo'q"
        combosStatusText.text = if (combosFile.exists()) "Combos: Tayyor ✓" else "Combos: Yo'q"
    }

    private fun importModel() {
        modelFileLauncher.launch("application/octet-stream")
    }

    private fun importYolo() {
        yoloFileLauncher.launch("application/octet-stream")
    }

    private fun importCombos() {
        combosFileLauncher.launch("application/json")
    }

    private fun importModelFile(uri: Uri, type: String) {
        lifecycleScope.launch {
            try {
                val heroDir = File(getExternalFilesDir(null), "MLBB_AI")
                heroDir.mkdirs()

                val fileName = when (type) {
                    "action" -> "model_${heroId}.tflite"
                    "yolo" -> "model_${heroId}_yolo.tflite"
                    "combos" -> "model_${heroId}_combos.json"
                    else -> return@launch
                }
                val targetFile = File(heroDir, fileName)

                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (type == "action") {
                    val hero = database.heroDao().getHeroById(heroId)
                    if (hero != null) {
                        database.heroDao().update(
                            hero.copy(
                                modelPath = targetFile.absolutePath,
                                modelStatus = "ready"
                            )
                        )
                    }
                }

                loadHeroInfo()
                val label = when (type) {
                    "action" -> "Model"; "yolo" -> "YOLO"; else -> "Combos"
                }
                Toast.makeText(this@HeroDetailActivity,
                    "$label muvaffaqiyatli import qilindi!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (type == "action") {
                    val hero = database.heroDao().getHeroById(heroId)
                    if (hero != null) {
                        database.heroDao().update(
                            hero.copy(modelStatus = "error", modelPath = "Error: ${e.message}")
                        )
                    }
                }
                loadHeroInfo()
                Toast.makeText(this@HeroDetailActivity,
                    "Import bajarilmadi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadVideos() {
        lifecycleScope.launch {
            database.youTubeVideoDao().getVideosForHero(heroId).collect { videos ->
                videoAdapter.submitList(videos)
                findViewById<TextView>(R.id.videoCountText).text = "Videolar: ${videos.size}"
            }
        }
    }

    private fun showAddVideoDialog() {
        val input = EditText(this).apply {
            hint = "YouTube URL"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("YouTube Video qo'shish")
            .setMessage("$heroName uchun YouTube o'yin URLini joylashtiring")
            .setView(input)
            .setPositiveButton("Qo'shish") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) addVideo(url)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun addVideo(url: String) {
        lifecycleScope.launch {
            database.youTubeVideoDao().insert(
                YouTubeVideo(heroId = heroId, url = url, title = extractTitle(url))
            )
            Toast.makeText(this@HeroDetailActivity, "Video qo'shildi!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteVideo(video: YouTubeVideo) {
        lifecycleScope.launch { database.youTubeVideoDao().delete(video) }
    }

    private fun openYouTube(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun extractTitle(url: String): String {
        val match = Regex("(?:v=|/)([a-zA-Z0-9_-]{11})").find(url)
        return match?.groupValues?.getOrNull(1) ?: url.takeLast(20)
    }

    private fun openOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(this, "Qoplama ruxsati talab qilinadi!", Toast.LENGTH_LONG).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        val intent = Intent(this, GameOverlayService::class.java).apply {
            action = GameOverlayService.ACTION_SHOW
            putExtra("preselect_hero_id", heroId)
            putExtra("preselect_hero_name", heroName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Qoplama ochildi: $heroName", Toast.LENGTH_SHORT).show()
    }
}

class VideoAdapter(
    private val onDelete: (YouTubeVideo) -> Unit,
    private val onOpen: (YouTubeVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {
    private var videos = listOf<YouTubeVideo>()

    fun submitList(list: List<YouTubeVideo>) {
        videos = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_2, parent, false
        )
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.text1.text = video.title.take(40)
        holder.text2.text = video.url.take(50) + "..."
        holder.itemView.setOnClickListener { onOpen(video) }
        holder.itemView.setOnLongClickListener { onDelete(video); true }
    }

    override fun getItemCount() = videos.size

    class VideoViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val text1: android.widget.TextView = itemView.findViewById(android.R.id.text1)
        val text2: android.widget.TextView = itemView.findViewById(android.R.id.text2)
    }
}
