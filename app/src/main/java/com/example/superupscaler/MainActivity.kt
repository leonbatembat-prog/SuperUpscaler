package com.example.superupscaler

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPickImage: Button
    private lateinit var btnUpscale: Button
    private lateinit var btnSave: Button

    private var originalBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var interpreter: Interpreter? = null

    private val TILE_SIZE = 128
    private val SCALE_FACTOR = 4
    private val MODEL_FILE = "realesrgan_x4.tflite"

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnUpscale = findViewById(R.id.btnUpscale)
        btnSave = findViewById(R.id.btnSave)

        btnPickImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        btnUpscale.setOnClickListener { runUpscale() }
        btnSave.setOnClickListener { saveResultToGallery() }

        loadModel()
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer: ByteBuffer =
                fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)
            statusText.text = "Model siap. Pilih gambar untuk mulai"
        } catch (e: Exception) {
            statusText.text = "Model belum ditemukan di assets/$MODEL_FILE. " +
                    "Lihat README untuk cara mengunduh dan menempatkan model."
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
        statusText.text = "Gambar siap (${bitmap.width}x${bitmap.height})"
    }

    private fun runUpscale() {
        val bitmap = originalBitmap ?: return
        val model = interpreter ?: run {
            Toast.makeText(this, "Model belum dimuat", Toast.LENGTH_SHORT).show()
            return
        }

        btnUpscale.isEnabled = false
        btnPickImage.isEnabled = false
        progressBar.visibility = ProgressBar.VISIBLE
        progressBar.progress = 0
        statusText.text = "Memproses upscaling..."

        CoroutineScope(Dispatchers.Main).launch {
            val output = withContext(Dispatchers.Default) {
                upscaleBitmapTiled(bitmap, model) { progress ->
                    runOnUiThread {
                        progressBar.progress = progress
                    }
                }
            }
            resultBitmap = output
            imagePreview.setImageBitmap(output)
            progressBar.visibility = ProgressBar.GONE
            btnUpscale.isEnabled = true
            btnPickImage.isEnabled = true
            btnSave.isEnabled = true
            statusText.text = "Selesai: ${output.width}x${output.height}"
        }
    }

    private fun upscaleBitmapTiled(
        src: Bitmap,
        model: Interpreter,
        onProgress: (Int) -> Unit
    ): Bitmap {
        val tilesX = (src.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (src.height + TILE_SIZE - 1) / TILE_SIZE
        val totalTiles = tilesX * tilesY

        val outWidth = src.width * SCALE_FACTOR
        val outHeight = src.height * SCALE_FACTOR
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        var done = 0
        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val x0 = tx * TILE_SIZE
                val y0 = ty * TILE_SIZE
                val w = minOf(TILE_SIZE, src.width - x0)
                val h = minOf(TILE_SIZE, src.height - y0)

                val tile = Bitmap.createBitmap(src, x0, y0, w, h)
                val paddedTile = padToTileSize(tile)

                val upscaledTile = runModelOnTile(model, paddedTile)

                val cropped = Bitmap.createBitmap(
                    upscaledTile, 0, 0, w * SCALE_FACTOR, h * SCALE_FACTOR
                )

                canvas.drawBitmap(cropped, (x0 * SCALE_FACTOR).toFloat(), (y0 * SCALE_FACTOR).toFloat(), null)

                done++
                onProgress((done * 100) / totalTiles)
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

        val outSize = TILE_SIZE * SCALE_FACTOR
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
        val filename = "upscaled_${System.currentTimeMillis()}.png"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SuperUpscaler")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "Tersimpan ke Galeri", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        interpreter?.close()
    }
}
