package com.randomprototypes.rpnetworkcamera

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService

import android.net.wifi.WifiManager
import android.provider.MediaStore

import android.util.Size
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.camera2.Camera2Config
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.util.Consumer
import java.net.ServerSocket
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity(),  CameraXConfig.Provider  {
    private var imageCapture: ImageCapture? = null

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    public val server_port = 25600
    public var server_ip : String? = null;

    public var timestampOffsetToEpoch : Long = 0;

    public val captureImageSemaphore = Semaphore(1)
    public val captureVideoSemaphore = Semaphore(1)
    public var imgCapWidth : Int = 0
    public var imgCapHeight : Int = 0
    public var imgCapFormat : String = ""
    public var imgCapturedTimestamp : Long = 0
    public var imgCapturedFrameId : Int = 0
    public var videoRecordStartTimestamp : Long = 0
    public var imgCapturedByteArray : ByteArray? = null
    public var imgCapRequested : Boolean = false

    public var isRecording : Boolean = false
    public var videoRecordFilename : String = ""
    public var videoEncoder : BitmapToVideoEncoder? = null

    private lateinit var videoCapture: VideoCapture<Recorder>
    public var currentRecording: Recording? = null
    private lateinit var recordingState: VideoRecordEvent

    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }

    fun ipToString(ipAddress:Int):String{
        return (ipAddress and 0xFF).toString()+"."+(ipAddress shr 8 and 0xFF)+"."+(ipAddress shr 16 and 0xFF)+"."+(ipAddress shr 24 and 0xFF)
    }

    //https://ameblo.jp/maoukamerasu/entry-12628746602.html
    fun getLocalIpAddress(): String? {
        val manager:WifiManager = this.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipAddress: String = ipToString(manager.connectionInfo.ipAddress)

        return ipAddress;
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main)

        timestampOffsetToEpoch = System.currentTimeMillis() - System.nanoTime() / 1000000

        val textView = findViewById<View>(R.id.ipAddressTextView) as TextView
        server_ip = getLocalIpAddress()
        textView.setText("ip address:\n"+server_ip)

        // Request camera permissions
        if (allPermissionsGranted()) {
            val sock = ServerSocket(server_port)
            thread { ServerHandler(sock, this).run() }
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    public fun getTimestampMs() : Long
    {
        return System.nanoTime() / 1000000 + timestampOffsetToEpoch
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        if(event is VideoRecordEvent.Start)
        {
            videoRecordStartTimestamp = getTimestampMs()
        }

        if (event is VideoRecordEvent.Finalize) {
            // display the captured video
                Log.d(TAG, "record finished")
            captureVideoSemaphore.release()
        }
    }

    @SuppressLint("MissingPermission")
    public fun startRecording() {
        Log.d(TAG, "startRecording()")
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "test.mp4")
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val videoFile = File(videoRecordFilename )

        var videoOutput = FileOutputOptions.Builder(videoFile).build()
        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(this, videoOutput)//mediaStoreOutput)
            //.withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), captureListener)

        Log.d(TAG, "Recording started")
    }



    private fun startCamera() {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
            imageCapture = ImageCapture.Builder().build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                //.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(720, 480))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalyser(this))
                }

            val quality = Quality.HD
            val qualitySelector = QualitySelector.from(quality)

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                /*cameraProvider.bindToLifecycle(
                    this, cameraSelector,
                    //videoCapture,
                    preview,
                    imageAnalyzer)*/
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture, imageAnalyzer)


            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    public fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
