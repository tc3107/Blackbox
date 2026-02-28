package com.example.blackbox.sharing

import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import com.example.blackbox.logging.AppLog as Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.blackbox.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(markerClass = [ExperimentalGetImage::class])
class QrScannerActivity : ComponentActivity() {
    private lateinit var overlayContainer: android.view.View
    private lateinit var cameraDisabledContainer: android.view.View
    private lateinit var previewView: PreviewView
    private lateinit var manualEntryButton: MaterialButton
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private val resultDelivered = AtomicBoolean(false)
    private var scanner: BarcodeScanner? = null
    private var sharingCrypto: SharingCrypto? = null
    private var lastInvalidCode: String? = null
    private var lastInvalidAtMs: Long = 0L
    private var lastValidatedCode: String? = null
    private var lastValidatedError: String? = null
    private var lastValidatedAtMs: Long = 0L

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hideCameraDisabledUi()
            startCamera()
        } else {
            showCameraDisabledUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        previewView = findViewById(R.id.qrScannerPreview)
        manualEntryButton = findViewById(R.id.qrScannerManualEntryButton)
        overlayContainer = findViewById(R.id.qrScannerOverlayContainer)
        cameraDisabledContainer = findViewById(R.id.qrScannerCameraDisabledContainer)
        applyStatusBarInsets(overlayContainer)
        scanner = runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(FORMAT_QR_CODE)
                    .build()
            )
        }.getOrElse { throwable ->
            Log.e(SHARING_DEBUG_TAG, "Failed to initialize QR scanner", throwable)
            finishWithError("Failed to initialize QR scanner.")
            return
        }
        sharingCrypto = runCatching { SharingCrypto() }.getOrElse { throwable ->
            Log.e(SHARING_DEBUG_TAG, "Failed to initialize contact code validator", throwable)
            finishWithError(getString(R.string.qr_scanner_error_validator_init))
            return
        }

        manualEntryButton.setOnClickListener {
            openManualEntryDialog()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            hideCameraDisabledUi()
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        scanner?.close()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        super.onDestroy()
    }

    private fun startCamera() {
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

    private fun showCameraDisabledUi() {
        cameraProvider?.unbindAll()
        previewView.setBackgroundColor(Color.BLACK)
        previewView.visibility = android.view.View.VISIBLE
        overlayContainer.visibility = android.view.View.GONE
        cameraDisabledContainer.visibility = android.view.View.VISIBLE
    }

    private fun hideCameraDisabledUi() {
        cameraDisabledContainer.visibility = android.view.View.GONE
        overlayContainer.visibility = android.view.View.VISIBLE
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val scanner = scanner
        if (scanner == null) {
            imageProxy.close()
            finishWithError("QR scanner is unavailable.")
            return
        }
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
                if (!value.isNullOrBlank()) {
                    submitCodeIfValid(
                        rawCode = value,
                        showInvalidInStatus = true
                    )
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

    private fun openManualEntryDialog() {
        if (resultDelivered.get()) return
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.qr_scanner_manual_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            isHintEnabled = true
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 5
        }
        inputLayout.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        val container = FrameLayout(this).apply {
            val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
            val verticalPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
            addView(
                inputLayout,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_scanner_manual_title)
            .setMessage(R.string.qr_scanner_manual_message)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.qr_scanner_manual_use_code, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val rawCode = input.text?.toString().orEmpty()
                inputLayout.error = null
                val error = submitCodeIfValid(
                    rawCode = rawCode,
                    showInvalidInStatus = false
                )
                if (error == null) {
                    dialog.dismiss()
                } else {
                    inputLayout.error = error
                    showInAppError(error)
                }
            }
        }
        dialog.show()
    }

    private fun submitCodeIfValid(rawCode: String, showInvalidInStatus: Boolean): String? {
        val code = rawCode.trim()
        val error = validateCodeError(code)
        if (error != null) {
            if (showInvalidInStatus) {
                showInvalidStatusForScannedCode(code, error)
            }
            return error
        }
        if (!resultDelivered.compareAndSet(false, true)) return null
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QR_TEXT, code))
        finish()
        return null
    }

    private fun validateCodeError(code: String): String? {
        val now = System.currentTimeMillis()
        if (code == lastValidatedCode && (now - lastValidatedAtMs) < VALIDATION_CACHE_MS) {
            return lastValidatedError
        }
        if (code.isBlank()) {
            return getString(R.string.qr_scanner_error_empty_code)
        }
        val validator = sharingCrypto ?: return getString(R.string.qr_scanner_error_validator_unavailable)
        val error = runCatching {
            validator.importContactCode(code)
            null
        }.getOrElse { throwable ->
            throwable.message?.takeIf { it.isNotBlank() } ?: getString(R.string.qr_scanner_error_invalid_code)
        }
        lastValidatedCode = code
        lastValidatedError = error
        lastValidatedAtMs = now
        return error
    }

    private fun showInvalidStatusForScannedCode(code: String, error: String) {
        val now = System.currentTimeMillis()
        if (lastInvalidCode == code && (now - lastInvalidAtMs) < INVALID_STATUS_DEBOUNCE_MS) return
        lastInvalidCode = code
        lastInvalidAtMs = now
        showInAppError(error)
    }

    private fun showInAppError(error: String) {
        Snackbar.make(
            overlayContainer,
            getString(R.string.qr_scanner_status_invalid_reason, error),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun applyStatusBarInsets(view: android.view.View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(baseLeft, baseTop + topInset, baseRight, baseBottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    companion object {
        private const val INVALID_STATUS_DEBOUNCE_MS = 1_500L
        private const val VALIDATION_CACHE_MS = 1_200L
        const val EXTRA_QR_TEXT = "qr_text"
        const val EXTRA_ERROR = "error"
    }
}
