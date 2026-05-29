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
| `train/train.py` | Asosiy training script (CNN+LSTM) |
| `train/requirements.txt` | Python kutubxonalari |

### Model arxitekturasi
- **Input**: 4 ta ketma-ket kadr (224×224 RGB)
- **CNN**: Conv2D(32)→Conv2D(64)→Conv2D(128)→Dense(256)
- **LSTM**: 256 birlik
- **Output 1**: Koordinatalar `[x1,y1,x2,y2]` (sigmoid, 0-1)
- **Output 2**: Action logits `[DOWN, MOVE, UP, NONE]` (softmax)

### Eksport
- `.tflite` fayl artifact sifatida yuklanadi
- Telefonda: Qahramon tafsilotlari → **Import Trained Model (.tflite)**

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
