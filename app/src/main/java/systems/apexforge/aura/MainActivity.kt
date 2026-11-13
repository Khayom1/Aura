package systems.apexforge.aura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import systems.apexforge.aura.accessibility.AuraAccessibilityService
import systems.apexforge.aura.gesture.AuraCommand
import systems.apexforge.aura.gesture.GestureEngine
import systems.apexforge.aura.gesture.HandLandmarkAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var sessionButton: Button
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: HandLandmarkAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private val gestureEngine = GestureEngine()
    private var sessionActive = false
    private var lastInjectedAt = 0L

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "CAMERA PERMISSION REQUIRED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 17, 31))
            setPadding(24, 36, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "A U R A"; textSize = 30f; setTextColor(Color.rgb(66, 217, 255))
        })
        status = TextView(this).apply {
            text = "SYSTEM STANDBY · HYBRID MODE"; textSize = 12f
            setTextColor(Color.WHITE); setPadding(0, 8, 0, 18)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        sessionButton = Button(this).apply {
            text = "START SESSION"
            setOnClickListener {
                if (sessionActive) stopSession()
                else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        val accessibilityButton = Button(this).apply {
            text = "ACCESSIBILITY SETTINGS"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(status)
        root.addView(sessionButton)
        root.addView(accessibilityButton)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "FRONT CAMERA • STRICT SAFETY • ON-DEVICE HAND TRACKING"
            textSize = 10f; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0)
        })
        setContentView(root)
    }

    private fun startCamera() {
        if (sessionActive) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val executor = Executors.newSingleThreadExecutor()
            val handAnalyzer = try {
                HandLandmarkAnalyzer(
                    applicationContext,
                    onHands = { hands, timestamp ->
                        val command = gestureEngine.process(hands, timestamp)
                        runOnUiThread { handleCommand(command, hands.size) }
                    },
                    onError = { message -> runOnUiThread { status.text = "TRACKING ERROR · $message" } }
                )
            } catch (failure: Exception) {
                executor.shutdown()
                status.text = "MODEL INIT ERROR · " + failure.javaClass.simpleName
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
                .also { it.setAnalyzer(executor, handAnalyzer) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                cameraProvider = provider
                analyzer = handAnalyzer
                analysisExecutor = executor
                sessionActive = true
                sessionButton.text = "STOP SESSION"
                status.text = "AURA ACTIVE · SHOW ONE OR TWO HANDS"
            } catch (failure: Exception) {
                handAnalyzer.close()
                executor.shutdown()
                status.text = "CAMERA ERROR · " + failure.javaClass.simpleName
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleCommand(command: AuraCommand, handCount: Int) {
        if (!sessionActive) return
        val service = AuraAccessibilityService.activeInstance
        when (command) {
            is AuraCommand.Pointer -> {
                service?.showPointer(command.x, command.y)
                status.text = if (service == null) "HANDS FOUND · ENABLE AURA ACCESSIBILITY" else "TRACKING · $handCount HAND"
            }
            is AuraCommand.Tap -> {
                if (service == null) status.text = "PINCH CLICK · ACCESSIBILITY REQUIRED"
                else status.text = if (service.tapNormalized(command.x, command.y)) "SELECT" else "ACTION BUSY"
            }
            is AuraCommand.LongPress -> {
                if (service == null) status.text = "LONG PRESS · ACCESSIBILITY REQUIRED"
                else status.text = if (service.longPressNormalized(command.x, command.y)) "LONG PRESS" else "ACTION BUSY"
            }
            is AuraCommand.Swipe -> {
                if (service == null) status.text = "DRAG · ACCESSIBILITY REQUIRED"
                else status.text = if (service.swipeNormalized(command.x1, command.y1, command.x2, command.y2, command.durationMs)) "DRAG" else "ACTION BUSY"
            }
            is AuraCommand.Zoom -> {
                val now = android.os.SystemClock.uptimeMillis()
                if (service != null && now - lastInjectedAt >= 125L) {
                    lastInjectedAt = now
                    val ok = service.pinchNormalized(
                        command.fromA.x, command.fromA.y, command.fromB.x, command.fromB.y,
                        command.toA.x, command.toA.y, command.toB.x, command.toB.y
                    )
                    status.text = if (ok) {
                        if (command.scaleDelta > 0f) "ZOOM IN" else "ZOOM OUT"
                    } else "ZOOM · WAITING FOR TOUCH PIPELINE"
                } else if (service == null) status.text = "TWO-HAND ZOOM · ENABLE ACCESSIBILITY"
            }
            AuraCommand.Pause -> {
                service?.hidePointer()
                status.text = "PAUSED · OPEN HANDS DETECTED"
            }
            AuraCommand.NoOp -> Unit
        }
    }

    private fun stopSession() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        AuraAccessibilityService.activeInstance?.hidePointer()
        sessionActive = false
        sessionButton.text = "START SESSION"
        status.text = "SYSTEM STANDBY · CAMERA RELEASED"
    }

    override fun onStop() {
        if (sessionActive) stopSession()
        super.onStop()
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }
}
