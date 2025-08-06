package org.macnonline.crossingalert

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var isFlashing = false
    private val handler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var strobeRunnable: Runnable

    private var videoUri: Uri? = null // Для Android 10+

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            cameraId = cameraManager.cameraIdList.first()
            Log.d("MainActivity", "Используется камера с ID: $cameraId")
        } catch (e: CameraAccessException) {
            Log.e("MainActivity", "Ошибка при доступе к камере: ${e.message}")
            e.printStackTrace()
        }

        checkPermissions()
    }

    // Получаем разрешения
    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkPermissions() {
        Log.d("MainActivity", "Проверка разрешений...")

        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }



        if (permissions.isNotEmpty()) {
            Log.d("MainActivity", "Запрашиваем разрешения: ${permissions.joinToString()}")
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        } else {
            Log.d("MainActivity", "Все разрешения получены")
            if (isNight()) toggleFlashStrobe()
            else startCamera(false)
        }
    }

    // Определяем время для выбора режима работы приложения
    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNightTime = hour < 6 || hour >= 20
        Log.d("MainActivity", "Проверка времени суток: ${if (isNightTime) "ночь" else "день"}")
        return isNightTime
    }

    private fun startCamera(useFront: Boolean) {
        Log.d("MainActivity", "Запуск камеры...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = if (useFront)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            val previewView = findViewById<PreviewView>(R.id.previewView)
            preview.setSurfaceProvider(previewView.surfaceProvider)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                Log.d("MainActivity", "Камера успешно привязана")

                handler.postDelayed({ startRecording() }, 300)
            } catch (exc: Exception) {
                Log.e("MainActivity", "Ошибка при привязке камеры: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        Log.d("MainActivity", "Запуск записи видео...")

        val currentVideoCapture = videoCapture ?: return
        val name = "crosswalk_${System.currentTimeMillis()}.mp4"

        val mediaRecorder = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 и ниже
            val videoDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val outputFile = File(videoDir, name)
            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            currentVideoCapture.output
                .prepareRecording(this, outputOptions)
        } else {
            // Android 10+
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            }

            val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
                .setContentValues(contentValues)
                .build()

            currentVideoCapture.output
                .prepareRecording(this, mediaStoreOutputOptions)
        }.apply {
            // Подключаем аудио, если разрешено
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                withAudioEnabled()
            }
        }

        // Запускаем запись
        recording = mediaRecorder.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d("MainActivity", "Запись началась")
                }

                is VideoRecordEvent.Finalize -> {
                    Log.d("MainActivity", "Запись завершена")

                    // Завершаем IS_PENDING при необходимости
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (recordEvent.outputResults.outputUri ?: videoUri)?.let { uri ->
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }
                            contentResolver.update(uri, values, null, null)
                        }
                    }

                    Toast.makeText(this, "Видео сохранено!", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ finish() }, 1000)
                }


            }
        }

        // Останавливаем запись через 10 секунд (автостоп)
        handler.postDelayed({ stopRecording() }, 10_000L)
    }


    private fun stopRecording() {
        Log.d("MainActivity", "Остановка записи видео...")
        recording?.stop()
        recording = null
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun toggleFlashStrobe() {
        Log.d("MainActivity", "Переключение стробоскопа...")
        if (cameraId == null) return

        isFlashing = !isFlashing
        if (isFlashing) startStrobe() else turnOffFlash()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun startStrobe() {
        val interval = 300L// частота моргания фонарика
        val stopAfter = 10000L// время работы строба  10 сек

        strobeRunnable = object : Runnable {
            @RequiresApi(Build.VERSION_CODES.M)
            override fun run() {
                if (!isFlashing) return
                try {
                    cameraManager.setTorchMode(cameraId!!, true)
                    handler.postDelayed({
                        cameraManager.setTorchMode(cameraId!!, false)
                        if (isFlashing) handler.postDelayed(this, interval)
                    }, interval)
                } catch (e: CameraAccessException) {
                    Log.e("MainActivity", "Ошибка доступа к фонарику: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        handler.post(strobeRunnable)
        handler.postDelayed({ stopStrobe() }, stopAfter)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun stopStrobe() {
        Log.d("MainActivity", "Остановка стробоскопа...")
        isFlashing = false
        handler.removeCallbacks(strobeRunnable)
        turnOffFlash()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun turnOffFlash() {
        try {
            Log.d("MainActivity", "Выключение фонарика...")
            cameraManager.setTorchMode(cameraId!!, false)
        } catch (e: CameraAccessException) {
            Log.e("MainActivity", "Ошибка при выключении фонарика: ${e.message}")
            e.printStackTrace()
        } finally {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        Log.d("MainActivity", "Активность уничтожена. Остановка записи.")
    }
}
