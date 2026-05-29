package com.mlbb.trainer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.*
import com.mlbb.trainer.database.Hero

class FloatingOverlayView(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callback: OverlayCallback
) {
    interface OverlayCallback {
        fun onStartAI(heroId: Long, heroName: String)
        fun onStopAI()
        fun onExit()
        fun onHeroSelected(heroId: Long, heroName: String)
    }

    private var isExpanded = false
    private var isDragging = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0
    private var initialTouchY = 0

    private var selectedHeroId: Long = -1
    private var selectedHeroName: String = ""

    private val bubbleSize = 100
    private val expandedWidth = 360
    private val expandedHeight = 520

    private var cachedHeroes: List<Hero> = emptyList()

    private lateinit var selectedHeroText: TextView
    private lateinit var statusText: TextView
    private lateinit var heroListContainer: LinearLayout
    private var heroInfoText: TextView? = null
    private var apmModeText: TextView? = null
    private var levelText: TextView? = null
    private var phaseText: TextView? = null

    private val layoutParams: WindowManager.LayoutParams
    private val rootView: LinearLayout

    init {
        layoutParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 200
        }

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC1A1A2E.toInt())
            elevation = 10f
            setOnTouchListener { _, event -> onTouch(event) }
            addView(createBubbleView())
        }
    }

    private fun createBubbleView(): View = TextView(context).apply {
        text = "\uD83E\uDD16"
        textSize = 28f
        gravity = Gravity.CENTER
        layoutParams = ViewGroup.LayoutParams(bubbleSize, bubbleSize)
    }

    private fun createExpandedView(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(expandedWidth, expandedHeight)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xEE1A1A2E.toInt())
            elevation = 15f
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    collapse()
                }
                false
            }
        }

        container.addView(TextView(context).apply {
            text = "\u22EF MLBB AI Boshqaruvi \u22EF"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 16)
        })

        heroInfoText = TextView(context).apply {
            text = "Quyida qahramonni tanlang"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 0, 0, 4)
        }
        container.addView(heroInfoText)

        selectedHeroText = TextView(context).apply {
            text = "Qahramon: Hech biri tanlanmagan"
            textSize = 14f
            setTextColor(0xFFFFD700.toInt())
            setPadding(0, 0, 0, 4)
        }
        container.addView(selectedHeroText)

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 8)
        }

        apmModeText = TextView(context).apply {
            text = "APM: --"
            textSize = 11f
            setTextColor(0xFF88CCFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(apmModeText)

        levelText = TextView(context).apply {
            text = "Dar: --"
            textSize = 11f
            setTextColor(0xFF88FF88.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(levelText)

        phaseText = TextView(context).apply {
            text = "Faza: ---"
            textSize = 11f
            setTextColor(0xFFFFCC88.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusRow.addView(phaseText)
        container.addView(statusRow)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        heroListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(heroListContainer)
        container.addView(scrollView)

        if (cachedHeroes.isNotEmpty()) {
            updateHeroList(cachedHeroes)
        }

        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 8, 0, 8) }
            setBackgroundColor(0x44FFFFFF.toInt())
        })

        statusText = TextView(context).apply {
            text = "Holat: Bo'sh"
            textSize = 12f
            setTextColor(0xFF88FF88.toInt())
            setPadding(0, 0, 0, 8)
        }
        container.addView(statusText)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        buttonRow.addView(Button(context).apply {
            text = "\u25B6 Boshlash"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2E7D32.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                if (selectedHeroId > 0) {
                    callback.onStartAI(selectedHeroId, selectedHeroName)
                    setStatusRunning()
                } else {
                    Toast.makeText(context, "Avval qahramon tanlang!", Toast.LENGTH_SHORT).show()
                }
            }
        })

        buttonRow.addView(Button(context).apply {
            text = "\u23F9 To'xtatish"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFC62828.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                callback.onStopAI()
                setStatusStopped()
            }
        })

        buttonRow.addView(Button(context).apply {
            text = "\u2715 Chiqish"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF546E7A.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { callback.onExit() }
        })
        container.addView(buttonRow)

        return container
    }

    fun show() {
        try {
            if (!rootView.isAttachedToWindow)
                windowManager.addView(rootView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        try {
            if (rootView.isAttachedToWindow)
                windowManager.removeView(rootView)
        } catch (e: Exception) {}
    }

    fun updateStatus(apmMode: String, level: String, phase: String) {
        apmModeText?.text = "APM: $apmMode"
        levelText?.text = "Dar: $level"
        phaseText?.text = "Faza: $phase"
    }

    fun setStatusRunning() { statusText.text = "Holat: Ishlamoqda" }
    fun setStatusStopped() { statusText.text = "Holat: To'xtatildi" }

    fun updateHeroList(heroes: List<Hero>) {
        cachedHeroes = heroes
        if (::heroListContainer.isInitialized) {
            heroListContainer.removeAllViews()
            for (hero in heroes) {
                val row = createHeroRow(hero)
                heroListContainer.addView(row)
            }
        }
        heroInfoText?.text = "Qahramonlar: ${heroes.size} ta mavjud"
    }

    private fun createHeroRow(hero: Hero): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0x33FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 4) }
            setOnClickListener {
                selectedHeroId = hero.id
                selectedHeroName = hero.name
                selectedHeroText.text = "Qahramon: ${hero.name}"
                callback.onHeroSelected(hero.id, hero.name)

                for (i in 0 until heroListContainer.childCount) {
                    val child = heroListContainer.getChildAt(i)
                    child.setBackgroundColor(
                        if (child.tag == hero.id) 0x55FFD700.toInt()
                        else 0x33FFFFFF.toInt()
                    )
                }
                setBackgroundColor(0x55FFD700.toInt())
            }
        }
        row.tag = hero.id

        row.addView(TextView(context).apply {
            text = hero.name
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(context).apply {
            text = if (hero.modelStatus == "ready") "\u2705" else "\uD83D\uDCF9"
            textSize = 12f
        })

        return row
    }

    private fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        rootView.removeAllViews()
        rootView.addView(createBubbleView())
        layoutParams.width = bubbleSize
        layoutParams.height = bubbleSize
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager.updateViewLayout(rootView, layoutParams)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialTouchX = event.rawX.toInt()
                initialTouchY = event.rawY.toInt()
                initialX = layoutParams.x
                initialY = layoutParams.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.toInt() - initialTouchX
                val dy = event.rawY.toInt() - initialTouchY
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                    isDragging = true
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(rootView, layoutParams)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) toggleExpand()
                isDragging = false
            }
        }
        return true
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        if (isExpanded) {
            val expandedView = createExpandedView()
            rootView.removeAllViews()
            rootView.addView(expandedView)
            layoutParams.width = expandedWidth
            layoutParams.height = expandedHeight
            layoutParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            collapse()
        }
        windowManager.updateViewLayout(rootView, layoutParams)
    }
}
