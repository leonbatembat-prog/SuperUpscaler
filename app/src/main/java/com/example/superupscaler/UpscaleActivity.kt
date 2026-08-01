package com.example.superupscaler

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt

class UpscaleActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPickImage: Button
    private lateinit var btnUpscale: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var seekScale: SeekBar
    private lateinit var txtScaleValue: TextView
    private lateinit var radioMode: RadioGroup
    private lateinit var radioFormat: RadioGroup
    private lateinit var fileInfoBox: android.widget.LinearLayout
    private lateinit var txtFileName: TextView
    private lateinit var txtInputSize: TextView
    private lateinit var txtOutputSize: TextView
    private lateinit var txtFileSize: TextView

    private var originalBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var currentFileName: String = "gambar"
    private var upscaleJob: Job? = null

    private val TILE_SIZE = 128
    private val NATIVE_SCALE = 4
    private val MODEL_FILE = "realesrgan_x4.tflite"
    private val CHANNEL_ID = "upscale_channel"
    private val NOTIF_ID = 101

    private var targetScale = 4

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin galeri diperlukan untuk memilih gambar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upscale)

        imagePreview = findViewById(R.id.imagePreview)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnUpscale = findViewById(R.id.btnUpscale)
        btnCancel = findViewById(R.id.btnCancel)
        btnSave = findViewById(R.id.btnSave)
        btnSettings = findViewById(R.id.btnSettings)
        seekScale = findViewById(R.id.seekScale)
        txtScaleValue = findViewById(R.id.txtScaleValue)
        radioMode = findViewById(R.id.radioMode)
        radioFormat = findViewById(R.id.radioFormat)
        fileInfoBox = findViewById(R.id.fileInfoBox)
        txtFileName = findViewById(R.id.txtFileName)
        txtInputSize = findViewById(R.id.txtInputSize)
        txtOutputSize = findViewById(R.id.txtOutputSize)
        txtFileSize = findViewById(R.id.txtFileSize)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        requestGalleryPermission()

        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                targetScale = progress + 2
                txtScaleValue.text = "${targetScale}x"
                updateOutputSizePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        btnUpscale.setOnClickListener { runUpscale() }
        btnCancel.setOnClickListener { cancelUpscale() }
        btnSave.setOnClickListener { saveResultToGallery() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadModelInBackground()
    }

    private fun requestGalleryPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            galleryPermissionLauncher.launch(permission)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Proses Upscale", NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun loadModelInBackground() {
        statusText.text = "Menyiapkan model AI..."
        CoroutineScope(Dispatchers.Main).launch {
            val loaded = withContext(Dispatchers.Default) {
                try {
                    val assetFileDescriptor = assets.openFd(MODEL_FILE)
                    val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
                    val fileChannel = inputStream.channel
                    val modelBuffer: ByteBuffer = fileChannel.map(
                        FileChannel.MapMode.READ_ONLY,
                        assetFileDescriptor.startOffset,
                        assetFileDescriptor.declaredLength
                    )
                    val options = Interpreter.Options()
                    options.setNumThreads(4)
                    try {
                        val compatList = CompatibilityList()
                        if (compatList.isDelegateSupportedOnThisDevice) {
                            gpuDelegate = GpuDelegate()
                            options.addDelegate(gpuDelegate)
                        }
                    } catch (e: Exception) {
                        gpuDelegate = null
                    }
                    interpreter = Interpreter(modelBuffer, options)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            statusText.text = if (loaded) "Model siap. Pilih gambar untuk mulai"
            else "Model belum ditemukan di assets/$MODEL_FILE."
            if (originalBitmap != null) btnUpscale.isEnabled = loaded
        }
    }

    private fun loadImage(uri: Uri) {
        val stream = contentResolver.openInputStream(uri)
        val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
        stream?.close()
        originalBitmap = bitmap
        resultBitmap = null
        imagePreview.setImageBitmap(bitmap)
        btnUpscale.isEnabled = interpreter != null
        btnSave.isEnabled = false

        currentFileName = getFileNameFromUri(uri) ?: "gambar"
        fileInfoBox.visibility = android.widget.LinearLayout.VISIBLE
        txtFileName.text = currentFileName
        txtInputSize.text = "Masukan: ${bitmap.width}x${bitmap.height}px"
        updateOutputSizePreview()
        val approxKb = (bitmap.byteCount / 1024)
        txtFileSize.text = "Ukuran asli (perkiraan): ${approxKb}KB"

        statusText.text = "Gambar siap (${bitmap.width}x${bitmap.height})"
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name
    }

    private fun updateOutputSizePreview() {
        val bmp = originalBitmap ?: return
        val outW = bmp.width * targetScale
        val outH = bmp.height * targetScale
        txtOutputSize.text = "Keluaran: ${outW}x${outH}px"
    }

    private fun selectedMode(): String = when (radioMode.checkedRadioButtonId) {
        R.id.modeFast -> "fast"
        R.id.modeMax -> "max"
        else -> "standard"
    }

    private fun selectedFormat(): Triple<Bitmap.CompressFormat, Int, String> = when (radioFormat.checkedRadioButtonId) {
        R.id.formatJpegLow -> Triple(Bitmap.CompressFormat.JPEG, 70, "jpg")
        R.id.formatPng -> Triple(Bitmap.CompressFormat.PNG, 100, "png")
        else -> Triple(Bitmap.CompressFormat.JPEG, 95, "jpg")
    }

    private fun runUpscale() {
        val bitmap = originalBitmap ?: return
        val mode = selectedMode()

        if (mode != "fast" && interpreter == null) {
            Toast.makeText(this, "Model belum dimuat", Toast.LENGTH_SHORT).show()
            return
        }

        btnUpscale.isEnabled = false
        btnPickImage.isEnabled = false
        btnCancel.visibility = android.view.View.VISIBLE
        progressBar.visibility = android.view.View.VISIBLE
        progressBar.progress = 0
        statusText.text = "Memproses upscaling..."

        val startTime = System.currentTimeMillis()

        upscaleJob = CoroutineScope(Dispatchers.Main).launch {
            val output = withContext(Dispatchers.Default) {
                if (mode == "fast") {
                    val outW = bitmap.width * targetScale
                    val outH = bitmap.height * targetScale
                    Bitmap.createScaledBitmap(bitmap, outW, outH, true)
                } else {
                    upscaleBitmapTiled(bitmap, interpreter!!) { progress, etaSeconds ->
                        runOnUiThread {
                            progressBar.progress = progress
                            statusText.text = "Memproses... $progress% — sisa ~${formatDuration(etaSeconds)}"
                        }
                    }
                }
            }

            if (!isActive) return@launch

            var finalOutput = output
            if (mode != "fast" && targetScale != NATIVE_SCALE) {
                val outW = bitmap.width * targetScale
                val outH = bitmap.height * targetScale
                finalOutput = Bitmap.createScaledBitmap(output, outW, outH, true)
            }

            resultBitmap = finalOutput
            imagePreview.setImageBitmap(finalOutput)
            progressBar.visibility = android.view.View.GONE
            btnUpscale.isEnabled = true
            btnPickImage.isEnabled = true
            btnCancel.visibility = android.view.View.GONE
            btnSave.isEnabled = true

            val durationSec = (System.currentTimeMillis() - startTime) / 1000
            statusText.text = "Selesai dalam ${formatDuration(durationSec)}: ${finalOutput.width}x${finalOutput.height}"
            txtOutputSize.text = "Keluaran: ${finalOutput.width}x${finalOutput.height}px"

            showCompletionNotification(durationSec)
        }
    }

    private fun cancelUpscale() {
        upscaleJob?.cancel()
        progressBar.visibility = android.view.View.GONE
        btnUpscale.isEnabled = true
        btnPickImage.isEnabled = true
        btnCancel.visibility = android.view.View.GONE
        statusText.text = "Proses dibatalkan"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m}m ${s}d" else "${s}d"
    }

    private fun showCompletionNotification(durationSec: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Upscale selesai")
            .setContentText("Selesai dalam ${formatDuration(durationSec)}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) { }
    }

    private fun upscaleBitmapTiled(
        src: Bitmap,
        model: Interpreter,
        onProgress: (Int, Long) -> Unit
    ): Bitmap {
        val tilesX = (src.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (src.height + TILE_SIZE - 1) / TILE_SIZE
        val totalTiles = tilesX * tilesY

        val outWidth = src.width * NATIVE_SCALE
        val outHeight = src.height * NATIVE_SCALE
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        var done = 0
        val startTime = System.currentTimeMillis()

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                if (upscaleJob?.isActive == false) return output

                val x0 = tx * TILE_SIZE
                val y0 = ty * TILE_SIZE
                val w = minOf(TILE_SIZE, src.width - x0)
                val h = minOf(TILE_SIZE, src.height - y0)

                val tile = Bitmap.createBitmap(src, x0, y0, w, h)
                val paddedTile = padToTileSize(tile)
                val upscaledTile = runModelOnTile(model, paddedTile)
                val cropped = Bitmap.createBitmap(
                    upscaledTile, 0, 0, w * NATIVE_SCALE, h * NATIVE_SCALE
                )
                canvas.drawBitmap(cropped, (x0 * NATIVE_SCALE).toFloat(), (y0 * NATIVE_SCALE).toFloat(), null)

                done++
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000
                val avgPerTile = if (done > 0) elapsedSec.toDouble() / done else 0.0
                val remainingTiles = totalTiles - done
                val etaSeconds = (avgPerTile * remainingTiles).roundToInt().toLong()
                onProgress((done * 100) / totalTiles, max(0, etaSeconds))
            }
        }
        return output
    }

    private fun padToTileSize(tile: Bitmap): Bitmap {
        if (tile.width == TILE_SIZE && tile.height == TILE_SIZE) return tile
        val padded = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawBitmap(tile, 0f, 0f, null)
        return padded
    }

    private fun runModelOnTile(model: Interpreter, tile: Bitmap): Bitmap {
        val inputBuffer = bitmapToByteBuffer(tile)
        val outSize = TILE_SIZE * NATIVE_SCALE
        val outputBuffer = ByteBuffer.allocateDirect(4 * outSize * outSize * 3)
        outputBuffer.order(ByteOrder.nativeOrder())
        model.run(inputBuffer, outputBuffer)
        return byteBufferToBitmap(outputBuffer, outSize, outSize)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * TILE_SIZE * TILE_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(TILE_SIZE * TILE_SIZE)
        bitmap.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    private fun byteBufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            val g = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            val b = (buffer.float.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun saveResultToGallery() {
        val bitmap = resultBitmap ?: return
        val (format, quality, ext) = selectedFormat()
        val filename = "upscaled_${System.currentTimeMillis()}.$ext"
        val mime = if (ext == "png") "image/png" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SuperUpscaler")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(format, quality, out)
            }
            Toast.makeText(this, "Tersimpan ke Galeri", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        interpreter?.close()
        gpuDelegate?.close()
    }
}
