# Super Upscaler — Panduan Build

Project Android Studio (Kotlin) untuk upscale gambar pakai AI (TensorFlow Lite), mirip cara kerja Super Image Pro.

## Yang sudah ada di project ini
- UI lengkap (pilih gambar, tombol upscale, progress bar, simpan ke galeri)
- Logika upscaling dengan **tiling** (gambar dipecah jadi potongan kecil supaya tidak bikin HP kehabisan memori)
- Integrasi TensorFlow Lite Interpreter

## Yang HARUS Anda lakukan sendiri (paling penting)

Saya **tidak bisa menyertakan file model AI** (`.tflite`) di project ini karena file model itu besar dan perlu diunduh dari internet — saya tidak punya akses unduh langsung ke Anda. Tanpa langkah ini, aplikasi akan tetap terbuka tapi tombol "Upscale" tidak akan berfungsi.

### Langkah mendapatkan model:
1. Cari model **Real-ESRGAN** atau **ESRGAN** versi **TFLite** (bukan .pth/.onnx), skala 4x. Beberapa sumber populer untuk dicari di GitHub/Hugging Face:
   - "real-esrgan tflite android"
   - "esrgan-tf2 tflite mobile"
2. Unduh file `.tflite`-nya.
3. Ganti nama file jadi `realesrgan_x4.tflite`, lalu taruh di folder:
   ```
   app/src/main/assets/realesrgan_x4.tflite
   ```
4. **Cek spesifikasi model** (biasanya tertulis di halaman sumbernya):
   - Ukuran input tile (mis. 64x64, 128x128, 256x256) → sesuaikan variabel `TILE_SIZE` di `MainActivity.kt`
   - Tipe data input: float32 (0–1) atau uint8 (0–255) → jika modelnya uint8, bagian `bitmapToByteBuffer`/`byteBufferToBitmap` di `MainActivity.kt` perlu disesuaikan (ganti `putFloat`/`.float` jadi `put`/`.get` byte biasa)
   - Faktor scale (2x/3x/4x) → sesuaikan `SCALE_FACTOR`

Kode di `MainActivity.kt` sudah diberi komentar di titik-titik yang perlu disesuaikan.

## Cara build jadi APK
1. Install **Android Studio** (gratis, dari situs resmi Android).
2. Pilih **Open** lalu arahkan ke folder `SuperUpscaler` ini.
3. Tunggu Gradle sync selesai (butuh koneksi internet untuk unduh dependency pertama kali).
4. Sambungkan HP Android (aktifkan USB Debugging) atau pakai emulator.
5. Klik tombol **Run ▶**.
6. Untuk membuat file APK yang bisa dibagikan/install manual: **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

## Kalau ingin hasil lebih ringan/cepat (opsional)
Jika model AI terasa berat/lambat di HP, sebagai alternatif sementara Anda bisa mengganti logika `runModelOnTile` dengan resize biasa (`Bitmap.createScaledBitmap` + filter bicubic) — kualitasnya lebih rendah tapi jalan instan tanpa model AI.

## Struktur project
```
SuperUpscaler/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/superupscaler/MainActivity.kt
│       ├── res/layout/activity_main.xml
│       ├── res/values/ (strings, themes)
│       └── assets/  ← taruh model .tflite di sini
├── build.gradle
├── settings.gradle
└── gradle.properties
```
