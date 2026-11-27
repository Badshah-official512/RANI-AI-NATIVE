package com.badshah222.rani.ai.core

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * v1.9.0 §21.3 — Awareness actually SEES. The front camera streams via
 * the EXISTING CameraX pipeline (no new camera stack, no new permission)
 * and, while a Live call is live AND Awareness is ON, a small JPEG frame
 * is captured every 2 seconds and handed to the caller, which forwards it
 * to the Live page (realtimeInput.video). The stream stops when the call
 * ends; the toggle stays ON as the preference and re-arms on the next call.
 * Awareness never streams outside a live call.
 *
 * Truthful state (a stuck ON is a fail): OFF (camera stopped) ->
 * STARTING (spinner, <= 3 s) -> SEEING (camera streaming) -> FAILED (back
 * to OFF + the reason in plain words). The state comes from the camera
 * itself, not from the stored preference.
 *
 * Privacy: frames go to Gemini Live ONLY while a call is live and
 * Awareness is ON. Frames are never recorded, never stored, never sent
 * anywhere else.
 */
object AwarenessCamera {

    /** "off" | "starting" | "seeing" | "failed" */
    private val _visionState = MutableStateFlow("off")
    val visionState: StateFlow<String> = _visionState

    private val _visionError = MutableStateFlow<String?>(null)
    val visionError: StateFlow<String?> = _visionError

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var running = false
    @Volatile private var lastSentAt = 0L
    private var onFrame: ((String) -> Unit)? = null

    fun start(activity: Activity, onFrame: (String) -> Unit) {
        if (running) return
        this.onFrame = onFrame
        _visionState.value = "starting"
        _visionError.value = null
        val owner = activity as? LifecycleOwner ?: run {
            _visionState.value = "failed"
            _visionError.value = "Camera could not start."
            return
        }
        try {
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    // F9: unbind ONLY our previously bound analysis use case.
                    // unbindAll() also killed the Camera View screen's preview
                    // (or any other camera use) if it was open.
                    val prev = analysis
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    this.analysis = analysis
                    analysis.setAnalyzer(executor) { image -> onImage(image) }
                    prev?.let { runCatching { p.unbind(it) } }
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                    running = true
                    _visionState.value = "seeing"
                } catch (e: Exception) {
                    Log.d("RaniAwareness", "camera bind failed: ${e.message}")
                    _visionState.value = "failed"
                    _visionError.value = "Camera is busy"
                    running = false
                }
            }, ContextCompat.getMainExecutor(activity))
        } catch (e: Exception) {
            _visionState.value = "failed"
            _visionError.value = "Camera could not start."
            running = false
        }
    }

    private fun onImage(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastSentAt < 2000) return
            val jpeg = yuvToJpeg(image) ?: return
            lastSentAt = now
            _visionState.value = "seeing"
            onFrame?.invoke(Base64.encodeToString(jpeg, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.d("RaniAwareness", "frame failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    fun stop() {
        running = false
        onFrame = null
        // F9 (same as start): unbind only our own analysis use case, so a
        // Camera View preview open elsewhere keeps working.
        try { analysis?.let { provider?.unbind(it) } } catch (e: Exception) { }
        analysis = null
        provider = null
        _visionState.value = "off"
        _visionError.value = null
    }

    /** YUV_420_888 -> NV21 -> JPEG bytes (small, ~640x480, quality 70). */
    private fun yuvToJpeg(image: ImageProxy): ByteArray? {
        return try {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val ySize = yPlane.buffer.remaining()
            val uvSize = uPlane.buffer.remaining() + vPlane.buffer.remaining()
            val nv21 = ByteArray(ySize + uvSize)
            // Y plane (respect row stride).
            val yBuf = yPlane.buffer
            val yRowStride = yPlane.rowStride
            if (yRowStride == w) {
                yBuf.get(nv21, 0, ySize)
            } else {
                var dst = 0
                val row = ByteArray(w)
                for (r in 0 until h) {
                    yBuf.position(r * yRowStride)
                    yBuf.get(row, 0, w)
                    row.copyInto(nv21, dst)
                    dst += w
                }
            }
            // Interleaved VU (NV21): V first, then U, respecting pixel stride.
            // F10: the V plane has its OWN row/pixel stride — YUV_420_888
            // does not guarantee it shares the U plane's. Using the U
            // strides for V garbled frames on some devices.
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            var dst = w * h // Y was copied compacted (w*h bytes), not ySize
            val uvH = h / 2
            val uvW = w / 2
            for (r in 0 until uvH) {
                for (c in 0 until uvW) {
                    val vIndex = r * vPlane.rowStride + c * vPlane.pixelStride
                    val uIndex = r * uPlane.rowStride + c * uPlane.pixelStride
                    if (dst >= nv21.size) break
                    if (vIndex < vBuf.limit()) nv21[dst++] = vBuf.get(vIndex)
                    if (dst >= nv21.size) break
                    if (uIndex < uBuf.limit()) nv21[dst++] = uBuf.get(uIndex)
                }
            }
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            if (!yuv.compressToJpeg(Rect(0, 0, w, h), 70, out)) return null
            out.toByteArray()
        } catch (e: Exception) {
            Log.d("RaniAwareness", "yuv->jpeg failed: ${e.message}")
            null
        }
    }
}
