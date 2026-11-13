package systems.apexforge.aura

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import systems.apexforge.aura.control.AuraCameraService
import systems.apexforge.aura.gesture.HandFrame
import systems.apexforge.aura.gesture.HandOverlayView

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var handOverlay: HandOverlayView
    private lateinit var status: TextView
    private lateinit var sessionButton: Button
    private var auraService: AuraCameraService? = null
    private var serviceBound = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionAndStart()
        else status.text = "CAMERA PERMISSION REQUIRED"
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startSessionService()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AuraCameraService.LocalBinder ?: return
            val service = local.service()
            auraService = service
            service.setUiListener { message, hands ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = message
                        handOverlay.updateHands(hands)
                        sessionButton.text = if (AuraCameraService.isRunning) "STOP SESSION" else "START SESSION"
                    }
                }
            }
            service.attachPreview(previewView.surfaceProvider)
            status.text = service.statusText()
            sessionButton.text = "STOP SESSION"
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            auraService = null
            sessionButton.text = "START SESSION"
            status.text = "AURA STOPPED"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
    }

    override fun onStart() {
        super.onStart()
        if (AuraCameraService.isRunning) {
            bindAuraService()
        } else {
            sessionButton.text = "START SESSION"
            status.text = "SYSTEM STANDBY · HYBRID MODE"
            handOverlay.updateHands(emptyList())
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 17, 31))
            setPadding(24, 36, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "A U R A"
            textSize = 30f
            setTextColor(Color.rgb(66, 217, 255))
        })
        status = TextView(this).apply {
            text = "SYSTEM STANDBY · HYBRID MODE"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 18)
        }
        previewView = PreviewView(this).apply {
            // SurfaceView-backed rendering avoids an extra app-composited preview
            // layer; the transparent landmark HUD is drawn above it.
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        handOverlay = HandOverlayView(this).apply {
            isClickable = false
            isFocusable = false
        }
        val cameraContainer = FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(handOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        sessionButton = Button(this).apply {
            text = "START SESSION"
            setOnClickListener {
                if (AuraCameraService.isRunning) stopSessionService()
                else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) requestNotificationPermissionAndStart()
                else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        val accessibilityButton = Button(this).apply {
            text = "ACCESSIBILITY SETTINGS"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(status)
        root.addView(sessionButton)
        root.addView(accessibilityButton)
        root.addView(cameraContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(TextView(this).apply {
            text = "FRONT CAMERA • ONE-HAND CONTROL • ON-DEVICE INFERENCE"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 0)
        })
        setContentView(root)
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startSessionService()
    }

    private fun startSessionService() {
        val intent = Intent(this, AuraCameraService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
            bindAuraService()
            sessionButton.text = "STOP SESSION"
            status.text = "STARTING AURA · CAMERA INITIALIZING"
        } catch (failure: Exception) {
            status.text = "SERVICE START FAILED · " + failure.javaClass.simpleName
            sessionButton.text = "START SESSION"
        }
    }

    private fun bindAuraService() {
        if (serviceBound) return
        serviceBound = bindService(
            Intent(this, AuraCameraService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun stopSessionService() {
        auraService?.setUiListener(null)
        auraService?.detachPreview()
        if (auraService != null) auraService?.stopSession()
        else stopService(Intent(this, AuraCameraService::class.java))
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) { }
            serviceBound = false
        }
        auraService = null
        handOverlay.updateHands(emptyList())
        sessionButton.text = "START SESSION"
        status.text = "SYSTEM STANDBY · CAMERA RELEASED"
    }

    override fun onStop() {
        auraService?.detachPreview()
        auraService?.setUiListener(null)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: IllegalArgumentException) { }
            serviceBound = false
        }
        auraService = null
        super.onStop()
    }
}