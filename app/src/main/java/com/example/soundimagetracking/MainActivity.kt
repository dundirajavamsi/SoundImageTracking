package com.example.soundimagetracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.concurrent.Executors
import kotlin.math.log10


class MainActivity : AppCompatActivity(),LifecycleOwner {

    companion object{
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 101
        private const val CAMERA_PERMISSION_REQUEST_CODE = 102
    }
    private var mediaRecorder: MediaRecorder? = null
    var textView : TextView? = null;
    var button: Button? = null;
    var textureView: TextureView? = null
    private lateinit var timerTextView: TextView
    private lateinit var imageViewCapturedImage: ImageView
    private val countdownDuration = 3000 // milliseconds
    private var isCountdownRunning = false
    private lateinit var imageCapture: ImageCapture
    private lateinit var outputDirectory: File

    private val threshold = 80
    private val audioTiming = 2000
    private val measuringFrequency = 200
    private val averageDecibelValuesListLength = audioTiming/measuringFrequency
    private val queue: Queue<Double> = LinkedList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView = findViewById(R.id.textView)
        button  = findViewById(R.id.captureButton)
        textureView = findViewById(R.id.textureView)
        timerTextView = findViewById(R.id.timerTextView)
        imageViewCapturedImage = findViewById(R.id.imageViewCapturedImage)

        // Set up ImageCapture use case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Set up output directory for captured images
        outputDirectory = getOutputDirectory()

        if(!isAudioPermissionGranted()){
            requestRecordAudioPermission()
        }else{
            setUpMediaPlayer()
        }

        button?.setOnClickListener {
            // Check for camera permissions
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST_CODE
                )
            } else {
                // If permission already granted, start the camera
                startCamera()
                Handler(Looper.getMainLooper()).postDelayed({
                    startCountdown()
                }, 1000)
                //Toast.makeText(this,"Camera Permission Already Granted",Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "Camera").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun startCountdown() {
        isCountdownRunning = true
        object : CountDownTimer(countdownDuration.toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = millisUntilFinished / 1000
                timerTextView.text = secondsRemaining.toString()
            }

            override fun onFinish() {
                timerTextView.visibility = View.INVISIBLE
                isCountdownRunning = false
                captureImage()
            }
        }.start()

        timerTextView.visibility = View.VISIBLE
    }

    private fun captureImage() {
        Log.i("AUDIO VAMSI","Coming into the function");
        try {
            val executor = Executors.newSingleThreadExecutor()
            val imageCaptureOptions = ImageCapture.OutputFileOptions.Builder(createImageFile()).build()
            imageCapture.takePicture(imageCaptureOptions, executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val savedUri = outputFileResults.savedUri ?: Uri.fromFile(createImageFile())
                        // Display the captured image to the user (you can use an ImageView)
                        runOnUiThread {
                            Log.i("AUDIO VAMSI","Saved Uri ${savedUri.path}");
                            Toast.makeText(this@MainActivity,"Image Captured",Toast.LENGTH_SHORT).show()
                            textureView?.visibility = View.INVISIBLE
                            imageViewCapturedImage.setImageURI(savedUri)
                            imageViewCapturedImage.visibility = View.VISIBLE
                            button?.isEnabled = true;
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.i("AUDIO VAMSI","Error while capturing image ${exception.message}");
                        Toast.makeText(this@MainActivity,"Error while capturing image",Toast.LENGTH_SHORT).show()
                    }
                })
        }catch (e : Exception){
            Log.i("AUDIO VAMSI","Error catched${e.message}");
        }

    }

    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
        val fileName = "IMG_${timeStamp}.jpg"
        return File(outputDirectory, fileName)
    }


    private fun startCamera() {
        button?.isEnabled = false;
        textureView?.visibility = View.VISIBLE
        imageViewCapturedImage.visibility = View.INVISIBLE
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()

            // Select the front camera
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            // Set up preview use case
            val preview = Preview.Builder()
                .build()

            // Set up the target surface for the preview using SurfaceTexture
            val surfaceTexture = textureView?.surfaceTexture
            val surface = Surface(surfaceTexture)
            preview.setSurfaceProvider { request ->
                request.provideSurface(surface, Executors.newSingleThreadExecutor()) {
                }
            }
            try {
                // Unbind any bound use cases before rebinding
                cameraProvider.unbindAll()

                // Bind the use cases to the camera
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (exc: Exception) {
                // Handle errors
            }
        }, ContextCompat.getMainExecutor(this))
    }




    private fun isAudioPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RECORD_AUDIO_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    //Toast.makeText(this,"Audio Permission Granted",Toast.LENGTH_SHORT).show()
                    setUpMediaPlayer()
                } else {
                    //Toast.makeText(this,"Audio Permission Denied",Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                    Handler(Looper.getMainLooper()).postDelayed({
                        startCountdown()
                    }, 1000)
                } else {
                    //Toast.makeText(this,"Camera Permission Denied",Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setUpMediaPlayer(){
        // Initialize MediaRecorder
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        }else{
            MediaRecorder()
        }
        mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        val path = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM
        ).absolutePath
        val outputFile = "${path}/${System.currentTimeMillis()}.3gp"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaRecorder?.setOutputFile(outputFile)
        }

        try {
            mediaRecorder?.prepare()
            mediaRecorder?.start()
            handler.post(updateAmplitudeTask)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val handler = Handler()

    private val updateAmplitudeTask = object : Runnable {
        override fun run() {
            val amplitude = mediaRecorder?.maxAmplitude?:0
            val audioInDecible = amplitudeToDecibel(amplitude)
            textView?.text = "Audio Level in decibels: $audioInDecible"
            if(showAudioAlert(audioInDecible)){
                Snackbar.make(findViewById(android.R.id.content), "Alert: Some alert message here!", Snackbar.LENGTH_SHORT).setTextColor(Color.WHITE).setBackgroundTint(Color.RED).show()
            }
            handler.postDelayed(this, measuringFrequency.toLong()) // Adjust the delay as needed
        }
    }

    private fun showAudioAlert(audioInDecibel: Double): Boolean {
        if (queue.size >= averageDecibelValuesListLength) {
            queue.poll()
        }
        queue.add(audioInDecibel)

        val average = queue.sum() / queue.size
        if (queue.size == averageDecibelValuesListLength && average >= threshold) {
            return true;
        }
        return false;
    }

    fun amplitudeToDecibel(amplitude: Int): Double {
        if(amplitude == 0) return 0.0
        return 20 * log10(amplitude.toDouble())
    }

    override fun onDestroy() {
        super.onDestroy()
        if(mediaRecorder != null){
            mediaRecorder?.stop()
            mediaRecorder?.release()
        }
    }
}