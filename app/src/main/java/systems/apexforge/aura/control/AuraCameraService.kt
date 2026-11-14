package systems.apexforge.aura.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.hardware.display.DisplayManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import systems.apexforge.aura.accessibility.AuraAccessibilityService
import systems.apexforge.aura.gesture.AuraCommand
import systems.apexforge.aura.gesture.GestureEngine
import systems.apexforge.aura.gesture.HandFrame
import systems.apexforge.aura.gesture.HandLandmarkAnalyzer
import systems.apexforge.aura.gesture.SystemAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the front camera and on-device single-hand inference alive while the
 * user switches to other apps. It starts only after an explicit user action.
 */
class AuraCameraService : LifecycleService() {
    inner class LocalBinder : Binder() {
        fun service(): AuraCameraService = this@AuraCameraService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureEngine = GestureEngine()
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var attachedPreviewProvider: Preview.SurfaceProvider? = null
    private var analyzer: HandLandmarkAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var displayManager: DisplayManager? = null
    private var targetRotation: Int = Surface.ROTATION_0
    private var listener: ((String, List<HandFrame>) -> Unit)? = null
    private var started = false
    private data class PointerUpdate(val command: AuraCommand.Pointer, val hands: List<HandFrame>)
    private val latestPointer = AtomicReference<PointerUpdate?>(null)
    private val pointerDispatchScheduled = AtomicBoolean(false)
    private val lastContinuousCommand = AtomicReference<String?>(null)
    private val trackingLost = AtomicBoolean(false)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = updateTargetRotation()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        isRunning = true
        startForegroundCompat()
        if (!started) startCameraPipeline()
        return START_NOT_STICKY
    }

    fun setUiListener(callback: ((String, List<HandFrame>) -> Unit)?) {
        listener = callback
    }

    fun statusText(): String = currentStatus

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        attachedPreviewProvider = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    fun detachPreview() {
        attachedPreviewProvider = null
        preview?.setSurfaceProvider(null)
    }

    fun stopSession() {
        releaseCameraPipeline()
        publishStatus("SYSTEM STANDBY · CAMERA RELEASED")
        listener?.invoke(currentStatus, emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        stopSelf()
    }

    private fun releaseCameraPipeline() {
        started = false
        latestPointer.set(null)
        lastContinuousCommand.set(null)
        trackingLost.set(false)
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        attachedPreviewProvider = null
        val oldAnalyzer = analyzer
        val oldExecutor = analysisExecutor
        analyzer = null
        analysisExecutor = null
        if (oldAnalyzer != null && oldExecutor != null) {
            // Close the GPU delegate on the same worker thread that created and
            // used it; then shut down the single-thread analysis executor.
            oldExecutor.execute { oldAnalyzer.close() }
            oldExecutor.shutdown()
        }
        AuraAccessibilityService.activeInstance?.hidePointer()
        isRunning = false
    }

    private fun startCameraPipeline() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            publishStatus("CAMERA PERMISSION REQUIRED")
            stopSelf()
            return
        }

        val executor = Executors.newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                task.run()
            }, "AURA-Inference")
        }
        val handAnalyzer = HandLandmarkAnalyzer(
            applicationContext,
            onHands = { hands, timestamp ->
                val hasConfidentHand = hands.any {
                    it.confidence >= 0.88f && it.landmarks.size >= 21
                }
                if (!hasConfidentHand) {
                    latestPointer.set(null)
                    lastContinuousCommand.set(null)
                    if (trackingLost.compareAndSet(false, true)) {
                        mainHandler.post { handleTrackingLost() }
                    }
                } else {
                    trackingLost.set(false)
                    val command = gestureEngine.process(hands, timestamp)
                    when (command) {
                    is AuraCommand.Pointer -> {
                        lastContinuousCommand.set("POINTER")
                        latestPointer.set(PointerUpdate(command, hands))
                        dispatchLatestPointer()
                    }
                    AuraCommand.Pause -> {
                        // Open-palm pause is a sustained pose, not a new event
                        // on every frame. Dispatch it once per transition.
                        if (lastContinuousCommand.getAndSet("PAUSE") != "PAUSE") {
                            mainHandler.post { handleCommand(command, hands) }
                        }
                    }
                    AuraCommand.CommandModeArming -> {
                        if (lastContinuousCommand.getAndSet("ARMING") != "ARMING") {
                            mainHandler.post { handleCommand(command, hands) }
                        }
                    }
                    AuraCommand.NoOp -> lastContinuousCommand.set(null)
                    else -> {
                        lastContinuousCommand.set(null)
                        // Tap, long press, drag and system actions are discrete
                        // and must never be overwritten by pointer coalescing.
                        mainHandler.post { handleCommand(command, hands) }
                    }
                }
                }
            },
            onError = { message ->
                mainHandler.post {
                    publishStatus("TRACKING ERROR · $message")
                    listener?.invoke(currentStatus, emptyList())
                }
            },
            onMetrics = { metrics ->
                mainHandler.post {
                    performanceText = metrics
                    if (started && currentStatus.startsWith("TRACKING")) {
                        publishStatus("TRACKING · ONE HAND · $performanceText")
                        listener?.invoke(currentStatus, latestPointer.get()?.hands ?: emptyList())
                    }
                }
            }
        )

        targetRotation = currentDisplayRotation()
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // MediaPipe's Android MediaPipe Task input path requires RGBA_8888
            // for CameraX frames; the analyzer reuses one ARGB bitmap per session.
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(android.util.Size(360, 480))
            .build()
            .also { it.setAnalyzer(executor, handAnalyzer) }
        val cameraPreview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .setTargetResolution(android.util.Size(480, 640))
            .build()
            .also { it.setSurfaceProvider(attachedPreviewProvider) }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (!isRunning) {
                    handAnalyzer.close()
                    executor.shutdown()
                    return@addListener
                }
                val provider = future.get()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, analysis)
                cameraProvider = provider
                preview = cameraPreview
                imageAnalysis = analysis
                analyzer = handAnalyzer
                analysisExecutor = executor
                started = true
                displayManager = getSystemService(DisplayManager::class.java).also {
                    it.registerDisplayListener(displayListener, mainHandler)
                }
                publishStatus("AURA ACTIVE · SINGLE-HAND GPU/CPU TRACKING")
                listener?.invoke(currentStatus, emptyList())
            } catch (failure: Exception) {
                Log.e(TAG, "Camera binding failed", failure)
                handAnalyzer.close()
                executor.shutdown()
                publishStatus("CAMERA ERROR · " + failure.javaClass.simpleName)
                listener?.invoke(currentStatus, emptyList())
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Pointer tracking may emit one result per camera frame. Keep only the
     * newest point and post at most one UI runnable at a time. Discrete
     * gestures use a separate lossless path.
     */
    private fun dispatchLatestPointer() {
        if (!pointerDispatchScheduled.compareAndSet(false, true)) return
        mainHandler.post {
            val update = latestPointer.getAndSet(null)
            pointerDispatchScheduled.set(false)
            update?.let { handleCommand(it.command, it.hands) }
            // Close the race where another frame arrived while the scheduled
            // flag was being cleared.
            if (latestPointer.get() != null) dispatchLatestPointer()
        }
    }

    private fun handleTrackingLost() {
        if (!started) return
        AuraAccessibilityService.activeInstance?.hidePointer()
        publishStatus("NO CONFIDENT HAND · SAFE IDLE")
        listener?.invoke(currentStatus, emptyList())
    }


    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        getSystemService(WindowManager::class.java).defaultDisplay.rotation

    private fun updateTargetRotation() {
        if (!started) return
        val rotation = currentDisplayRotation()
        if (rotation == targetRotation) return
        targetRotation = rotation
        // CameraX updates the ImageInfo rotation metadata and selects matching
        // buffer dimensions without tearing down the running camera session.
        imageAnalysis?.targetRotation = rotation
        preview?.targetRotation = rotation
        Log.i(TAG, "Display rotation changed: $rotation")
    }

    private fun handleCommand(command: AuraCommand, hands: List<HandFrame>) {
        if (!started || hands.none { it.confidence >= 0.88f && it.landmarks.size >= 21 }) return
        val service = AuraAccessibilityService.activeInstance
        when (command) {
            is AuraCommand.Pointer -> {
                service?.showPointer(command.x, command.y)
                if (service == null) publishStatus("HAND FOUND · ENABLE AURA ACCESSIBILITY")
                else publishStatus("TRACKING · ONE HAND · $performanceText")
            }
            is AuraCommand.Tap -> {
                val ok = service?.tapNormalized(command.x, command.y) == true
                publishStatus(if (ok) "SELECT" else "SELECT · ACCESSIBILITY REQUIRED OR BUSY")
            }
            is AuraCommand.LongPress -> {
                val ok = service?.longPressNormalized(command.x, command.y) == true
                publishStatus(if (ok) "LONG PRESS" else "LONG PRESS · ACCESSIBILITY REQUIRED OR BUSY")
            }
            is AuraCommand.Swipe -> {
                val ok = service?.swipeNormalized(command.x1, command.y1, command.x2, command.y2, command.durationMs) == true
                publishStatus(if (ok) "DRAG / SWIPE" else "DRAG · ACCESSIBILITY REQUIRED OR BUSY")
            }
            is AuraCommand.System -> {
                service?.hidePointer()
                val ok = service?.performSystemAction(command.action) == true
                publishStatus(if (ok) "SYSTEM · ${command.action.name}" else "SYSTEM ACTION UNAVAILABLE")
            }
            AuraCommand.CommandModeEntered -> {
                service?.hidePointer()
                publishStatus("COMMAND MODE · V-SWIPE")
            }
            AuraCommand.CommandModeArming -> {
                service?.hidePointer()
                publishStatus("HOLD FIST · ARMING COMMAND MODE")
            }
            AuraCommand.Pause -> {
                service?.hidePointer()
                publishStatus("PAUSED · OPEN PALM")
            }
            AuraCommand.NoOp -> return
        }
        listener?.invoke(currentStatus, hands)
    }

    @Volatile private var currentStatus = "AURA STARTING"
    @Volatile private var performanceText = "WARMING UP"

    private fun publishStatus(value: String) {
        if (currentStatus == value) return
        currentStatus = value
        val notification = buildNotification(value)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("AURA STARTING")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AURA active control", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when AURA is actively using the front camera for local hand tracking."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, AuraCameraService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 71, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AURA · Stark Control")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP AURA", stopPending)
            .build()
    }

    override fun onDestroy() {
        releaseCameraPipeline()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        listener = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "systems.apexforge.aura.action.STOP"
        private const val TAG = "AURA-CameraService"
        private const val CHANNEL_ID = "aura_active_control"
        private const val NOTIFICATION_ID = 8041
        @Volatile var isRunning: Boolean = false
            private set
    }
}
