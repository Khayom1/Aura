package systems.apexforge.aura
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "CAMERA PERMISSION REQUIRED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else cameraPermission.launch(Manifest.permission.CAMERA)
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
        }
        root.addView(status)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "FRONT CAMERA • STRICT SAFETY • ON-DEVICE PIPELINE"
            textSize = 10f; setTextColor(Color.LTGRAY); setPadding(0, 16, 0, 0)
        })
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
                status.text = "CAMERA READY · GESTURE ENGINE NOT YET ARMED"
            } catch (failure: Exception) {
                status.text = "CAMERA ERROR · " + failure.javaClass.simpleName
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
