package com.example.blackbox.sharing

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.blackbox.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private val resultDelivered = AtomicBoolean(false)

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(FORMAT_QR_CODE)
            .build()
    )

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            finishWithError("Camera permission denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        previewView = findViewById(R.id.qrScannerPreview)
        statusText = findViewById(R.id.qrScannerStatus)
        findViewById<Button>(R.id.qrScannerCancelButton).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        scanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        statusText.text = "Point camera at a Blackbox location QR code"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also { useCase ->
                        useCase.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(Size(1280, 720))
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                                analyzeImage(imageProxy)
                            }
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure { throwable ->
                    Log.e(SHARING_DEBUG_TAG, "Failed to start QR camera scanner", throwable)
                    finishWithError("Failed to start camera scanner.")
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (resultDelivered.get()) return@addOnSuccessListener
                val value = firstQrValue(barcodes)
                if (!value.isNullOrBlank() && resultDelivered.compareAndSet(false, true)) {
                    val resultIntent = Intent().putExtra(EXTRA_QR_TEXT, value)
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }
            .addOnFailureListener { throwable ->
                Log.d(SHARING_DEBUG_TAG, "QR scan frame decode failed: ${throwable.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun firstQrValue(barcodes: List<Barcode>): String? {
        return barcodes.firstNotNullOfOrNull { barcode ->
            barcode.rawValue?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun finishWithError(error: String) {
        if (resultDelivered.compareAndSet(false, true)) {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, error))
        }
        finish()
    }

    companion object {
        const val EXTRA_QR_TEXT = "qr_text"
        const val EXTRA_ERROR = "error"
    }
}
