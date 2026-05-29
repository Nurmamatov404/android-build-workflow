package com.mlbb.trainer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mlbb.trainer.database.AppDatabase
import android.view.LayoutInflater
import com.mlbb.trainer.database.Hero
import com.mlbb.trainer.overlay.GameOverlayService
import com.mlbb.trainer.ui.HeroDetailActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var heroAdapter: HeroAdapter
    private lateinit var statusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var instructionText: TextView

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Ekranni yozib olishga ruxsat berilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val frames = intent.getIntExtra("frames", 0)
            val touches = intent.getIntExtra("touches", 0)
            val path = intent.getStringExtra("session_path") ?: ""
            statusText.text = "Holat: To'xtatildi"
            Toast.makeText(this@MainActivity,
                "Saqlangan: $frames kadr, $touches teginish", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        database = AppDatabase.getDatabase(this)

        statusText = findViewById(R.id.statusText)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        instructionText = findViewById(R.id.instructionText)

        val heroList = findViewById<RecyclerView>(R.id.heroListView)
        heroList.layoutManager = LinearLayoutManager(this)
        heroAdapter = HeroAdapter(
            onHeroClick = { hero -> openHeroDetail(hero) },
            onHeroLongClick = { hero -> deleteHero(hero) }
        )
        heroList.adapter = heroAdapter

        loadHeroes()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(receiver, IntentFilter("com.mlbb.trainer.RECORDING_STOPPED"))

        findViewById<Button>(R.id.addHeroButton).setOnClickListener { showAddHeroDialog() }
        findViewById<Button>(R.id.startRecordingButton).setOnClickListener { checkAndStartRecording() }
        findViewById<Button>(R.id.showOverlayButton).setOnClickListener { showOverlay() }
        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { openSettings() }

        updateOverlayStatus()
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
        if (RecordingService.isRecording) {
            statusText.text = "Holat: Yozilmoqda..."
        } else {
            statusText.text = "Holat: Bo'sh"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    private fun loadHeroes() {
        lifecycleScope.launch {
            database.heroDao().getAllHeroes().collect { heroes ->
                heroAdapter.submitList(heroes)
            }
        }
    }

    private fun showAddHeroDialog() {
        val input = EditText(this).apply {
            hint = "Qahramon nomi (masalan: Ling, Fanny, Hayabusa)"
        }

        AlertDialog.Builder(this)
            .setTitle("Qahramon qo'shish")
            .setMessage("Mobile Legends qahramon nomini kiriting:")
            .setView(input)
            .setPositiveButton("Qo'shish") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    addHero(name)
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun addHero(name: String) {
        lifecycleScope.launch {
            val hero = Hero(name = name)
            database.heroDao().insert(hero)
            Toast.makeText(this@MainActivity, "'$name' qahramoni qo'shildi!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteHero(hero: Hero) {
        AlertDialog.Builder(this)
            .setTitle("Qahramonni o'chirish")
            .setMessage("${hero.name} va uning barcha videolarini o'chirish?")
            .setPositiveButton("O'chirish") { _, _ ->
                lifecycleScope.launch {
                    database.youTubeVideoDao().deleteAllForHero(hero.id)
                    database.heroDao().delete(hero)
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun openHeroDetail(hero: Hero) {
        val intent = Intent(this, HeroDetailActivity::class.java).apply {
            putExtra("hero_id", hero.id)
            putExtra("hero_name", hero.name)
        }
        startActivity(intent)
    }

    private fun showOverlay() {
        val intent = Intent(this, GameOverlayService::class.java).apply {
            action = GameOverlayService.ACTION_SHOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateOverlayStatus()
    }

    private fun openSettings() {
        startActivity(Intent(this, com.mlbb.trainer.ui.SettingsActivity::class.java))
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Qoplama ruxsati allaqachon berilgan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateOverlayStatus() {
        overlayStatusText.text = if (GameOverlayService.isOverlayShowing) {
            "Qoplama: Faol"
        } else {
            "Qoplama: Faol emas (boshlash uchun Ko'rsatish-ni bosing)"
        }
    }

    private fun checkAndStartRecording() {
        if (!TouchEventService.isConnected) {
            AlertDialog.Builder(this)
                .setTitle("Maxsus imkoniyatlar xizmati kerak")
                .setMessage("Maxsus imkoniyatlar xizmatini yoqing:\nSozlamalar > Maxsus imkoniyatlar > MLBB Trener")
                .setPositiveButton("Sozlamalarni ochish") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Bekor qilish", null)
                .show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = "Holat: Yozilmoqda..."
    }
}

class HeroAdapter(
    private val onHeroClick: (Hero) -> Unit,
    private val onHeroLongClick: (Hero) -> Unit
) : RecyclerView.Adapter<HeroAdapter.HeroViewHolder>() {

    private var heroes = listOf<Hero>()

    fun submitList(list: List<Hero>) {
        heroes = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HeroViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            android.R.layout.simple_list_item_2, parent, false
        )
        return HeroViewHolder(view)
    }

    override fun onBindViewHolder(holder: HeroViewHolder, position: Int) {
        val hero = heroes[position]
        holder.text1.text = hero.name + if (hero.modelStatus == "ready") " \u2705" else ""
        holder.text2.text = if (hero.modelStatus == "ready") "Model tayyor"
                            else if (hero.modelStatus == "error") "Model xatosi"
                            else "Model yo'q"
        holder.itemView.setOnClickListener { onHeroClick(hero) }
        holder.itemView.setOnLongClickListener {
            onHeroLongClick(hero)
            true
        }
    }

    override fun getItemCount() = heroes.size

    class HeroViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val text1: android.widget.TextView = itemView.findViewById(android.R.id.text1)
        val text2: android.widget.TextView = itemView.findViewById(android.R.id.text2)
    }
}
