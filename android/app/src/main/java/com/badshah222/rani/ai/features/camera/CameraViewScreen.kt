package com.badshah222.rani.ai.features.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.ScreenRecordService
import com.badshah222.rani.ai.data.ai.AiException
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Camera View — the boss's favourite screen. A live front-camera preview so
 * the user can see exactly what RANI sees ("AI kya dekh raha hai"), with:
 *  - "Describe what RANI sees": one still photo -> Gemini Vision describes it.
 *  - "Check boss face": one still photo -> on-device boss recognition
 *    (Face Lock: only the enrolled boss face passes).
 *  - Screen recording toggle: saves what RANI sees as an on-device MP4.
 *
 * Privacy: the live preview never leaves the phone. A still is sent to
 * Gemini ONLY when the user taps Describe. Recordings are saved on-device
 * only and never uploaded.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container

    val description = MutableStateFlow<String?>(null)
    val describing = MutableStateFlow(false)
    val bossStatus = MutableStateFlow("Boss check not run yet — tap 'Check boss face'.")
    val bossMatched = MutableStateFlow<Boolean?>(null)
    val error = MutableStateFlow<String?>(null)
    val recording = ScreenRecordService.recording
    val recordAllowed: Boolean get() = c.remoteConfig.screenRecordingOn(c.store.screenRecordingEnabled)
    val cameraAllowed: Boolean get() = c.remoteConfig.cameraEnabled(true)

    fun describe(bmp: Bitmap) {
        describing.value = true
        description.value = null
        error.value = null
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                }
                val text = c.ai.describeImage(
                    bytes,
                    "Describe everything you see in this photo in detail — people, objects, text, mood, " +
                        "colours — as RANI would tell Babu Jaan. Keep it warm and short."
                )
                description.value = text
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "camera_event", mapOf("source" to "camera_view")) }
            } catch (e: AiException) {
                error.value = e.userMessage
            } catch (e: Exception) {
                error.value = "Could not describe the photo — try again."
            } finally {
                describing.value = false
            }
        }
    }

    /** Starts a screen recording (on-device MP4) and logs the start (metadata only). */
    fun startRecording(resultCode: Int, data: Intent) {
        ScreenRecordService.start(getApplication(), resultCode, data)
        viewModelScope.launch { runCatching { c.cloud.recordActivity(c.auth.current?.uid, "screen_recording", mapOf("action" to "start")) } }
    }

    /** Stops the recording and logs its metadata only (file stays on the phone). */
    fun stopRecording() {
        val file = ScreenRecordService.currentFile.value
        val started = ScreenRecordService.startedAt.value
        ScreenRecordService.stop(getApplication())
        if (file != null && started > 0L) {
            val duration = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(0)
            val size = runCatching { file.length() }.getOrDefault(0L)
            viewModelScope.launch {
                runCatching { c.cloud.recordScreenRecording(c.auth.current?.uid, file.name, duration, size) }
                runCatching { c.cloud.recordActivity(c.auth.current?.uid, "screen_recording", mapOf("action" to "stop", "duration_sec" to duration)) }
            }
        }
    }

    /** One still photo -> on-device boss face match. */
    fun checkBoss(bmp: Bitmap) {
        if (!c.facePrint.enrolled) {
            bossStatus.value = "No boss face enrolled yet — enrol it in Settings > Stonic & Safety."
            bossMatched.value = null
            return
        }
        bossStatus.value = "Checking face…"
        viewModelScope.launch {
            val (ok, score) = c.facePrint.match(bmp)
            bossMatched.value = ok
            val pct = (score * 100).toInt()
            bossStatus.value = if (ok) "Boss recognised (match $pct%)"
            else "This face does not match the enrolled boss (score $pct%). Face Lock stays on."
            runCatching { c.cloud.recordActivity(c.auth.current?.uid, "face_recognition", mapOf("matched" to ok)) }
        }
    }
}

@Composable
fun CameraViewScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val description by vm.description.collectAsState()
    val describing by vm.describing.collectAsState()
    val bossStatus by vm.bossStatus.collectAsState()
    val bossMatched by vm.bossMatched.collectAsState()
    val error by vm.error.collectAsState()
    val recording by vm.recording.collectAsState()
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCam = ok
    }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var starting by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            vm.startRecording(res.resultCode, res.data!!)
        }
    }

    fun takeStill(onBitmap: (Bitmap) -> Unit) {
        val cap = capture ?: run { captureError = "Camera is not ready yet."; return }
        captureError = null
        cap.takePicture(
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = try {
                        image.toBitmap()
                    } catch (e: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    if (bmp != null) onBitmap(bmp) else captureError = "Could not capture the photo."
                }

                override fun onError(exception: ImageCaptureException) {
                    captureError = "Camera error: ${exception.message}"
                }
            }
        )
    }

    // Bind the front camera once permission is granted.
    LaunchedEffect(hasCam) {
        if (!hasCam || starting) return@LaunchedEffect
        starting = true
        runCatching {
            val p = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(ctx))
                }
            }
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            capture = cap
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, cap)
        }.onFailure { captureError = "Could not start the camera: ${it.message}" }
        starting = false
    }
    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        TopBar("Camera View — What RANI Sees", onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            if (!vm.cameraAllowed) {
                Text(
                    "Camera is switched off by the admin panel. Ask the admin to turn camera_enabled on.",
                    color = Bad, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (!hasCam) {
                Button({ camPerm.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth()) {
                    Text("Allow camera")
                }
                Text(
                    "RANI needs the camera to show you what she sees. The feed stays on this phone.",
                    color = Mute, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(420.dp)
                        .background(Color.Black, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView({ previewView }, Modifier.fillMaxSize())
                    Text(
                        "LIVE · FRONT CAMERA",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(10.dp).background(Bad.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    if (recording) {
                        Text(
                            "● RECORDING",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(10.dp).background(Bad.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            captureError?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

            // Boss recognition status
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .background(Panel, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Face, "Boss", tint = when (bossMatched) {
                    true -> Ok
                    false -> Bad
                    null -> VioletSoft
                })
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Boss recognition", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(bossStatus, style = MaterialTheme.typography.bodySmall, color = Mute)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    { takeStill { vm.describe(it) } },
                    Modifier.weight(1f), enabled = hasCam && !describing
                ) {
                    Icon(Icons.Default.Visibility, null, Modifier.size(18.dp))
                    Text(" Describe what RANI sees", maxLines = 1)
                }
                OutlinedButton({ takeStill { vm.checkBoss(it) } }, Modifier.weight(1f), enabled = hasCam) {
                    Icon(Icons.Default.Face, null, Modifier.size(18.dp))
                    Text(" Check boss face", maxLines = 1)
                }
            }
            if (describing) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(" RANI is watching…", color = Mute, style = MaterialTheme.typography.bodySmall)
                }
            }
            description?.let {
                Card(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)
                ) {
                    Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Screen recording (on-device only)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .background(Panel, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null, tint = if (recording) Bad else VioletSoft)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Screen recording", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (recording) "Recording — saved on this phone only."
                        else "Save what RANI sees as a video on this phone.",
                        style = MaterialTheme.typography.bodySmall, color = Mute
                    )
                }
                Button(
                    onClick = {
                        if (recording) {
                            vm.stopRecording()
                        } else if (!vm.recordAllowed) {
                            vm.error.value = "Screen recording is switched off (Settings or admin panel)."
                        } else {
                            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    }
                ) { Text(if (recording) "Stop" else "Record") }
            }
            Text(
                "Privacy: the live feed never leaves this phone. A still is sent to Gemini only when you tap " +
                    "'Describe what RANI sees'. Recordings are saved on-device only — view or delete them on the Recordings screen.",
                color = Mute, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
            )
        }
    }
}
