package systems.apexforge.aura.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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

/**
 * Keeps the front camera and on-device hand inference alive while the user
 * switches to other apps. It is started only by an explicit foreground action.
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
    private var analyzer: HandLandmarkAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private var lastInjectedAt = 0L
    private var listener: ((String, List<HandFrame>) -> Unit)? = null
    private var started = false

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
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!started) startCameraPipeline()
        return START_NOT_STICKY
    }

    fun setUiListener(callback: ((String, List<HandFrame>) -> Unit)?) {
        listener = callback
    }

    fun statusText(): String = currentStatus

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    fun detachPreview() {
        preview?.setSurfaceProvider(null)
    }

    fun stopSession() {
        stopSelf()
    }

    private fun startCameraPipeline() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            publishStatus("CAMERA PERMISSION REQUIRED")
            stopSelf()
            return
        }
        val executor = Executors.newSingleThreadExecutor()
        val handAnalyzer = try {
            HandLandmarkAnalyzer(
                applicationContext,
                onHands = { hands, timestamp ->
                    val command = gestureEngine.process(hands, timestamp)
                    mainHandler.post { handleCommand(command, hands) }
                },
                onError = { message -> mainHandler.post { publishStatus("TRACKING ERROR · $message") } }
            )
        } catch (failure: Exception) {
            executor.shutdown()
            Log.e(TAG, "Could not initialize hand model", failure)
            publishStatus("MODEL INIT ERROR · " + failure.javaClass.simpleName)
            listener?.invoke(currentStatus, emptyList())
            stopSelf()
            return
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .build()
            .also { it.setAnalyzer(executor, handAnalyzer) }
        val cameraPreview = Preview.Builder().build()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, analysis)
                cameraProvider = provider
                preview = cameraPreview
                analyzer = handAnalyzer
                analysisExecutor = executor
                started = true
                publishStatus("AURA ACTIVE · HAND TRACKING READY")
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

    private fun handleCommand(command: AuraCommand, hands: List<HandFrame>) {
        if (!started) return
        val service = AuraAccessibilityService.activeInstance
        if (hands.isEmpty()) {
            service?.hidePointer()
            publishStatus("NO HANDS · SAFE IDLE")
            listener?.invoke(currentStatus, emptyList())
            return
        }
        when (command) {
            is AuraCommand.Pointer -> {
                service?.showPointer(command.x, command.y)
                if (service == null) publishStatus("HANDS FOUND · ENABLE AURA ACCESSIBILITY")
                else publishStatus("TRACKING · ${hands.size} HAND")
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
            is AuraCommand.Zoom -> {
                service?.hidePointer()
                val now = android.os.SystemClock.uptimeMillis()
                if (service != null && now - lastInjectedAt >= 125L) {
                    lastInjectedAt = now
                    val ok = service.pinchNormalized(
                        command.fromA.x, command.fromA.y, command.fromB.x, command.fromB.y,
                        command.toA.x, command.toA.y, command.toB.x, command.toB.y
                    )
                    publishStatus(if (ok) {
                        if (command.scaleDelta > 0f) "ZOOM IN" else "ZOOM OUT"
                    } else "ZOOM · ACTION BUSY")
                } else if (service == null) publishStatus("TWO-HAND ZOOM · ENABLE ACCESSIBILITY")
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
            AuraCommand.Pause -> {
                service?.hidePointer()
                publishStatus("PAUSED · OPEN HANDS")
            }
            AuraCommand.NoOp -> if (hands.size >= 2) service?.hidePointer()
        }
        listener?.invoke(currentStatus, hands)
    }

    @Volatile private var currentStatus = "AURA STARTING"

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
        started = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        val oldAnalyzer = analyzer
        val oldExecutor = analysisExecutor
        analyzer = null
        analysisExecutor = null
        if (oldAnalyzer != null && oldExecutor != null) {
            oldExecutor.execute { oldAnalyzer.close() }
            oldExecutor.shutdown()
        }
        AuraAccessibilityService.activeInstance?.hidePointer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        listener = null
        isRunning = false
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
