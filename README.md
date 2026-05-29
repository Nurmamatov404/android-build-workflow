# Android APK GitHub Actions Build

Bu workflow Android (Java/Kotlin) loyihangizni GitHub serverlarda build qilib, APK fayl va error loglarni qaytaradi.

## Ishlatish

1. **Kodni GitHub'ga yuklang:**
   ```bash
   git init
   git add .
   git commit -m "first commit"
   git remote add origin https://github.com/username/repo-name.git
   git branch -M main
   git push -u origin main
   ```

2. **Workflow faylini qo'lda ko'chiring:**
   - `.github/workflows/build.yml` faylini loyihangizning `.github/workflows/` papkasiga qo'ying
   - Yoki bu papka tayyor — hammasini GitHub'ga push qiling

3. **Build ishga tushadi:**
   - `git push` qilishingiz bilan workflow avtomatik ishga tushadi
   - GitHub repo'ngizdagi **Actions** tabidan build jarayonini kuzating
   - Build tugagach, **APK** va **error log** larni artifact sifatida yuklab olishingiz mumkin

## Natijalar

| Artifact nomi | Tavsifi |
|---|---|
| `android-apk-debug` | Tayyor APK fayl |
| `build-error-log` XATOLIK BO'LSA | Error log to'liq |
| `build-report` | To'liq build log va error log |

## Qo'lda build ishga tushirish

GitHub repo'ngizda:
1. **Actions** tabiga o'ting
2. **Android APK Build** workflow'ni tanlang
3. **Run workflow** -> **Run workflow** tugmasini bosing
4. Build turini tanlang: `debug` yoki `release`
5. Build tugagach, artifactlarni yuklab oling

## Muhim eslatmalar

- Loyihangizda `gradlew` fayli bo'lishi kerak (agar bo'lmasa, build fail bo'ladi)
- Agar `gradlew` yo'q bo'lsa, Terminalda: `gradle wrapper` yozib generate qiling
- Build muvaffaqiyatli bo'lsa, APK faylni **android-apk-debug** artifact'dan yuklaysiz
- Build xato bersa, **build-error-log** artifact'dan error.log ni yuklab olasiz
