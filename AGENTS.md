# AGENTS.md — MLBB AI Trainer (Android)

## Loyiha haqida
- **GitHub**: `https://github.com/Nurmamatov404/android-build-workflow`
- **Loyiha**: Android APK (MLBB AI Trainer) GitHub Actions orqali build qilinadi
- **Manba kodi**: `/data/data/com.termux/files/home/android-build-workflow/android/`
- **CI/CD**: `.github/workflows/build.yml` — push da avtomatik build

## Build tarixi

| Commit | Message | Build status |
|--------|---------|-------------|
| `a3a2750` | first commit | - |
| `12ecbde` | add android MLBB trainer project | - |
| `8373fd0` | add gradle wrapper and gitignore | - |
| `d037dce` | fix compilation errors and clean up | failure |
| `9d1510e` | fix workflow: use android/ subdirectory for gradle | failure |
| `fa77d00` | fix setup-android: use packages input instead of deprecated api-level | failure |
| `299488c` | fix theme: use AppCompat-compatible attributes, add material dependency | failure |
| `5145e35` | fix accessibility config: remove invalid android:canMonitorInput and typeTouchInteractionMove | failure |
| `55987b9` | fix when expression syntax in InferenceService | failure |
| `01c7f7c` | fix GestureDescription for API 34: use top-level class | failure |
| `89bd154` | fix: resolve compilation errors for API 34 build | success |
| `04a834f` | translate app UI to Uzbek language | success |
| `22bfd04` | add YouTube training pipeline (train.yml + train.py) | - |

## So'nggi yaxshilanishlar

### 1. GameStateDetector.kt — to'liq qayta yozildi
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/GameStateDetector.kt`

**`GameState` data class** (qator 9–24):
- `heroLevel: Int` — aniqlangan level (1–15)
- `hasLevelUp: Boolean` — skill ustida sariq nuqta bormi
- `levelUpButtonX/Y` — level-up tugmasi koordinatasi
- `isShopOpen: Boolean` — do'kon ochiqmi
- `shopRecommendX/Y` — "tavsiya etilgan" tugmasi
- `buyConfirmX/Y` — "sotib olish" tugmasi
- `estimatedGold: Int` — taxminiy oltin miqdori
- `isDead: Boolean` — qahramon o'likmi
- `matchEnded: Boolean` — o'yin tugaganmi
- `inBattle: Boolean` — jangda
- `gameStarted: Boolean` — o'yin boshlanganmi

**`detect(bitmap, displayW, displayH): GameState`** (qator 33–66):
- Asosiy metod — barcha detektorlarni chaqiradi, `GameState` qaytaradi

**`averageBrightness()`** (qator 68–75):
- Ekran yorqinligi (0–255), har 20-pikselda namuna
- `DEAD_BRIGHTNESS_THRESHOLD = 12` → 15+ qorong'i frame = o'lim
- `GAME_START_BRIGHTNESS_THRESHOLD = 80` → o'yin boshlangan

**`detectLevel()`** (qator 82–97):
- Yuqori-chap burchak (y=2.5%, x=1–10%) oq piksellarni sanaydi
- `whiteCount in 3..25` → `level = whiteCount/2 + 1`
- `whiteCount < 3` → `lastLevel` (oldingi levelni saqlaydi)

**`detectLevelUp()`** (qator 103–116):
- y=65–88%, x=60–100% sariq piksellar (`r>180, g>150, b<100`)
- 20+ sariq pixel → `hasLevelUp = true`

**`findLevelUp()`** (qator 120–135):
- Skill tugmalari ustida (y-4%) sariq/yashil piksel qidiradi

**`detectShop()`** (qator 139–148):
- Ekran markazi (cx±50, cy±80) qorong'i piksellar soni
- 60+ qorong'i pixel → do'kon ochiq

**`detectBattle()`** (qator 171–180):
- Ekran pastki yarmida qizil (`r>180, b<100`) yoki ko'k (`b>180, r<100`)
- 40+ rangli pixel → jang

**`detectMatchEnd()`** (qator 184–193):
- Markazda (cx±100, cy±40) yorug' piksellar (`brightness > 220`)
- 80+ yorug' pixel → o'yin tugagan

---

### 2. InferenceService.kt — 6 ta tuzatish
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/InferenceService.kt`

**Frame FPS logging** (qator 79–80, 185–191):
```kotlin
private var frameCount = 0L
private var lastFrameLogTime = 0L
```
Har 5 soniyada `Log.d(TAG, "FPS: ... pk=... phase=...")` chiqaradi. `adb logcat -s InferenceService` orqali kuzatish mumkin.

**Fallback pixel knowledge** (qator 196–203, 226–230):
- `ScreenAnalyzer.analyze()` `null` qaytarsa yoki `isInitialized = false` bo'lsa:
```kotlin
gameKnowledge = GameKnowledge(isInitialized = true)
pixelKnowledge = gameKnowledge.toPixelCoords(displayWidth, displayHeight)
```
- Hardcoded default pozitsiyalar (`GameKnowledge.kt:16–35`) ishlatiladi:
  - joystick: (12%, 78%)
  - skill1: (72%, 82%), skill2: (80%, 78%), skill3: (88%, 74%)
  - attack: (88%, 85%), recall: (5%, 50%), minimap: (94%, 6%)

**Null retry** (qator 196):
```kotlin
if (reanalyzeCounter >= 200 || pixelKnowledge == null)
```
- `pixelKnowledge == null` bo'lsa, 200 frame kutmay, har frame da qayta analiz

**Inference thread** (qator 175–182):
```kotlin
imageReader?.setOnImageAvailableListener({ ... }, inferenceHandler)
```
- Frame processing `inferenceHandler` (background thread) da ishlaydi
- Main thread bloklanmaydi

**Handler init tartibi** (qator 142–143):
```kotlin
inferenceThread = HandlerThread("InferenceThread").apply { start() }
inferenceHandler = Handler(inferenceThread!!.looper)
// keyin:
setupMediaProjection(resultCode, data)
```
- `inferenceHandler` `setupMediaProjection`dan oldin yaratiladi
- `setOnImageAvailableListener` ga `inferenceHandler` null emas

**executeGameAction null check** (qator 284–285):
```kotlin
if (pk == null) { Log.w(TAG, "executeGameAction: pixelKnowledge null, analysisda"); return }
```
- `pixelKnowledge` null bo'lsa, jim qaytmaydi — warning chiqaradi

---

### 3. HumanLikeTouchExecutor.kt — null check
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/HumanLikeTouchExecutor.kt`

**executeTap** (qator 87):
```kotlin
if (service == null) { Log.w(TAG, "Tap dropped: TouchEventService null"); return@postDelayed }
```

**executeSwipe** (qator 117):
```kotlin
if (service == null) { Log.w(TAG, "Swipe dropped: TouchEventService null"); return@postDelayed }
```
- AccessibilityService ulanganda `TouchEventService.instance` set qilinadi
- Service mavjud bo'lmasa, gesture dispach qilinmaydi — log chiqadi

---

### 4. HeroCombo.kt — noma'lum qahramonlar uchun farm rotation
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/HeroCombo.kt`

**getCombos()** (qator 163–180):
```kotlin
val heroCombos = allCombos[upper]
val hardcoded = if (heroCombos != null) {
    heroCombos.filter { ... }
} else {
    listOf(SkillCombo("basic_farm", farmRotation, minLevel = 1))
}
```
- `allCombos["UNKNOWN"]` yoki ro'yxatda yo'q qahramon → `farmRotation` (skill1→skill2→attack→move sikli) default combo sifatida
- 15 qadamlik `farmRotation` `HeroCombo.kt:152–161` da belgilangan

---

### 5. Autopilot state machine (yangi)
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/InferenceService.kt`

**AIState enum** (qator 88–90):
- `LANE_FARM` — minion farm, hujum, skill1/skill2
- `TEAM_FIGHT` — jang, combo, ultimate, intensiv skill
- `DEAD` — o'lik, hech narsa qilmaydi
- `SHOPPING` — do'konda buyum sotib oladi
- `RECALL` — bazaga qaytish, kutadi
- `ROAMING` — minimap bo'ylab harakat

**updateAIState(pk)** (qator 328–361):
- `isDead=true` → `DEAD` state, hech narsa qilmaydi
- `isShopOpen=true` → `SHOPPING`, buyum sotib oladi
- `inBattle=true` → `TEAM_FIGHT`, combo va intensiv skill
- `RECALL` da 30+ action kutsa → `LANE_FARM` ga qaytadi
- 40+ action lane farm da → `ROAMING` ga o'tadi
- `stateTimer` har bir `executeGameAction` da oshadi

**executeLaneFarm(pk)** (qator 363–374):
- Random 12 variant: attack, skill1-2, minimal move, minimap tap
- Farm paytida recall chaqirish imkoniyati

**executeTeamFight(pk)** (qator 376–388):
- ApmMode bo'yicha intensivlik: LAZY→normal, NORMAL→intense, INTENSE→combo+skill
- Team fight da combolar faol ishlatiladi

**executeRoaming(pk)** (qator 390–399):
- Harakat + minimap tap + skill1-2
- 15 action dan keyin `LANE_FARM` ga qaytadi

---

### 6. imageToBitmap — to'g'rilandi
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/InferenceService.kt` (qator 629–661)

**Buffer.rewind()** (qator 635):
```kotlin
buffer.rewind()
```
- Image buffer o'qilishidan oldin `rewind()` qilinadi — ba'zi qurilmalarda buffer pozitsiyasi oxirida bo'lishi mumkin

**Row padding handling** (qator 637–641):
- Agar `rowPadding == 0` bo'lsa, oddiy `copyPixelsFromBuffer` ishlatiladi
- Agar padding bo'lsa, qator-qator o'qilib, `setPixels()` orqali bitmap ga yoziladi
- RGBA→ARGB konvertatsiyasi to'g'ri

---

### 7. TFLite model inference — qo'shildi
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/InferenceService.kt`

**Buffer** (qator 81):
- `modelFrameBuffer: MutableList<Bitmap>` — 4 ta ketma-ket 224×224 frame
- `modelInferenceCounter` — har 7-frame da bir marta inference

**runModelOnFrame(bitmap)** (qator 227–245):
- Frame ni 224×224 ga o'lchaydi, buffer ga qo'shadi
- Buffer da 4 ta frame bo'lsa, `tfliteModel.run()` chaqiradi
- Natija `lastModelOutput` ga yoziladi

**Model chiqishi → action** (qator 337–358):
- `actionType = "DOWN"` → `executeTouch(x, y, "DOWN")`
- `actionType = "MOVE"` → `executeTouch(x, y, "MOVE")`
- `actionType = "UP"` → `executeTouch(0, 0, "UP")`
- `actionType = "NONE"` → heuristic action (AIState)

**Ishlatish**: Model `.tflite` fayli mavjud bo'lsa avtomatik ishlaydi. Model bo'lmasa, faqat heuristic action lar ishlaydi.

---

### 8. Joystick hold — to'g'rilandi
**Fayl**: `android/app/src/main/java/com/mlbb/trainer/inference/HumanLikeTouchExecutor.kt`

**executeJoystickMove** (qator 159–192):
- Eski usul: 1ms "touch down" + 40-150ms "move" (2 xil gesture)
- **Yangi**: bitta gesture (400-800ms), path joystick dan offset gacha
- `GestureDescription.StrokeDescription(path, 0, 400-800ms)` — bir marta DOWN→MOVE→UP
- Offset va wobble to'g'ridan-to'g'ri path da

**Gesture duration**:
- Taps: 60-300ms (avval 30-120ms edi, MLBB qabul qilmasligi mumkin)
- Swipe: 120-350ms (avval 80-250ms)
- Joystick: 400-800ms (avval 1-150ms)
- Barcha duration lar MLBB ning minimal touch vaqtiga mos

## Tuzatilgan xatolar

### 1. Workflow setup (`fa77d00`)
- `google()` repository, `setup-java` v4, `setup-android` — packages input
- `sdk adb` dan `platform-tools` ga almashtirish
- AGP 8.x va Gradle 8.x versiyalari

### 2. Theme xatoligi (`299488c`)
- `Theme.MaterialComponents.DayNight.NoActionBar` ishlatildi
- `@+id/` reference larni to'g'rilash
- Material dependency qo'shildi

### 3. Accessibility config (`5145e35`)
- `android:canMonitorInput="true"` va `typeTouchInteractionMove` API 34 da ishlamaydi
- Olib tashlandi

### 4. `when` expression (`55987b9`)
- `InferenceService.kt:185` da `when { ... }` dagi sintaksis xatosi tuzatildi

### 5. `GestureDescription` API 34 (`01c7f7c`)
- `GestureDescription.Builder()` deprecated — top-level class ishlatildi

### 6. API 34 kompilyatsiya xatolari (`89bd154`)
| Xatolik | Fayl | Fix |
|---------|------|-----|
| `motionEvent` removed | `TouchEventService.kt:45` | Reflection `getMethod("getMotionEvent")` |
| `isAiRunning` private setter | `InferenceService.kt:554` | `private set` olib tashlandi |
| `Log` topilmayapti | `LearnedComboProvider.kt:47` | `import android.util.Log` |
| `Int` vs `Float` | `ScreenAnalyzer.kt:181` | `.toFloat()` |
| `modelPath` property emas | `TFLiteModel.kt:50` | `private val modelPath` |
| `setMargins` xatosi (6 ta) | `FloatingOverlayView.kt` | `LinearLayout.LayoutParams` ga almashtirildi |
| `row` topilmayapti | `FloatingOverlayView.kt:291` | `setBackgroundColor()` to'g'ridan-to'g'ri |
| `LayoutInflater` yo'q | `HeroDetailActivity.kt:205` | Import qo'shildi |

## Tarjima (Uzbek) — commit `04a834f`
11 ta fayl, barcha UI satrlari ingliz tilidan o'zbek tiliga o'tkazildi:
- `strings.xml` — resurs satrlari
- `activity_main.xml`, `activity_settings.xml`, `activity_hero_detail.xml` — layout matnlari
- `MainActivity.kt`, `SettingsActivity.kt`, `HeroDetailActivity.kt` — dialog, toast matnlari
- `RecordingService.kt`, `GameOverlayService.kt` — bildirishnomalar
- `FloatingOverlayView.kt` — overlay UI
- `InferenceService.kt` — AI bildirishnomalari

## Muhim eslatmalar
- APK **debug** build — Play Protect bloklashi mumkin. "Install anyway" tugmasini bosing yoki Play Protectori o'chiring.
- `motionEvent` uchun reflection ishlatilgan — runtime da mavjud bo'lsa ishlaydi.
- Sozlamadagi rejim nomlari (Auto, Lazy, Normal, Intense, Random, Smart) texnik atama sifatida asl holicha qoldirilgan.

## YouTube orqali ML model o'qitish

### Ishga tushirish
1. GitHub → Actions → **Train AI Model from YouTube** → **Run workflow**
2. YouTube video URL larini vergul bilan ajratib yozing
3. Workflow avtomatik: yuklab oladi → kadr ajratadi → pseudo-label yaratadi → o'qitadi → `.tflite` chiqaradi

### Fayllar
| Fayl | Vazifasi |
|------|----------|
| `.github/workflows/train.yml` | Training workflow (workflow_dispatch) |
| `train/train.py` | Asosiy training script (CNN+LSTM + YOLO) |
| `train/requirements.txt` | Python kutubxonalari |

### Model arxitekturasi

**Action model (CNN+LSTM)**:
- **Input**: 4 ta ketma-ket kadr (224×224 RGB)
- **CNN**: Conv2D(32)→Conv2D(64)→Conv2D(128)→Dense(256)
- **LSTM**: 256 birlik
- **Output 1**: Koordinatalar `[x1,y1,x2,y2]` (sigmoid, 0-1)
- **Output 2**: Action logits `[DOWN, MOVE, UP, NONE]` (softmax)

**YOLO model (Tiny YOLO)** — UI element detection:
- **Input**: 1 ta kadr (416×416 RGB)
- **Backbone**: Conv2D(16→32→64→128→256) stride=2
- **Output**: Grid (13×13) × (8 class + 5 bbox) → joystick, skill1-3, ultimate, attack, recall, minimap
- **Fayl**: `model_hero_yolo.tflite` (action model yonida)

### YOLO ishlash prinsipi
- `YOLODetector.kt` — TFLite interpreter orqali YOLO inference
- `ScreenAnalyzer.kt` — YOLO detections mavjud bo'lsa ishlatadi, bo'lmasa heuristic usulga o'tadi
- `InferenceService.kt` — model yonida `_yolo.tflite` bo'lsa avtomatik yuklaydi
- YOLO real vaqtda ekrandagi tugmalar pozitsiyasini aniqlaydi → `PixelKnowledge` yangilanadi

### Eksport
- Action model: `model_hero.tflite` — hero harakatlari uchun
- YOLO model: `model_hero_yolo.tflite` — UI element deteksiyasi uchun
- Telefonda: Qahramon tafsilotlari → **Import Trained Model (.tflite)** → ikkala fayl ham import qilinadi

### Qo'llab-quvvatlanadigan URL turlari
- **YouTube**: `youtube.com/watch?v=...` yoki `youtu.be/...` → `yt_dlp` bilan 4 xil extractor strategiya
- **Google Drive**: `drive.google.com/file/d/...` → `gdown` bilan avtomatik yuklash
- **Direct URL**: istalgan `.mp4` yoki video fayl linki → `requests` bilan stream yuklash

### Tuzatilgan muammolar (`f35785e`)
| Xatolik | Sabab | Fix |
|---------|-------|-----|
| yt-dlp `CalledProcessError` | Eski versiya (2024.3.10), YouTube blokirovkasi | Version pin olib tashlandi, `yt_dlp` library ishlatildi, 4 xil extractor strategiya |
| `IndentationError` | `download_videos` loop tashqarisidagi kod | `for` loop indentatsiyasi tuzatildi |
| BGR/RGB mos kelmasligi | OpenCV BGR, model RGB kutadi | `cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)` qo'shildi |
| pinned numpy | TensorFlow bilan versiya mos kelmasligi | Version pin olib tashlandi |
