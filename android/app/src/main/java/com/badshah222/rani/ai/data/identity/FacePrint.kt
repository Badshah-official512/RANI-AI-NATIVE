package com.badshah222.rani.ai.data.identity

import android.graphics.Bitmap
import com.badshah222.rani.ai.core.SecureStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * On-device face recognition for Boss recognition / Face Lock (Stonic's
 * Face Lock idea, built for Android).
 *
 * ML Kit detects the face (bundled model, works offline). The "face print"
 * is a normalised 32x32 grayscale crop of the detected face, stored AES-256
 * encrypted in SecureStore and never sent anywhere. Matching is cosine
 * similarity (>= 0.72 = the boss).
 *
 * Honest limit: this is a lightweight on-device match, not a biometric-grade
 * system — Face Lock is a convenience lock, and the user can switch it off
 * in Settings. Enrolment needs one clear front-camera photo of the boss.
 */
class FacePrint(private val store: SecureStore) {
    val enrolled: Boolean get() = store.facePrintProfile != null
    val lockOn: Boolean get() = store.faceLockEnabled

    fun setLock(on: Boolean) {
        store.faceLockEnabled = on
    }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    /** Detects the largest face in [bitmap]; returns its embedding, or null when no face is found. */
    suspend fun embedding(bitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = suspendCancellableCoroutine<List<Face>> { cont ->
            detector.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return@withContext null
        val r = face.boundingBox
        val pad = (r.width() * 0.2f).toInt()
        val left = (r.left - pad).coerceIn(0, bitmap.width - 1)
        val top = (r.top - pad).coerceIn(0, bitmap.height - 1)
        val right = (r.right + pad).coerceIn(left + 1, bitmap.width)
        val bottom = (r.bottom + pad).coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val small = Bitmap.createScaledBitmap(crop, 32, 32, true)
        if (small !== crop) crop.recycle()
        val out = FloatArray(32 * 32)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        var mean = 0f
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f
            out[i] = g
            mean += g
        }
        mean /= out.size
        var sd = 0f
        for (v in out) sd += (v - mean) * (v - mean)
        sd = sqrt(sd / out.size)
        for (i in out.indices) out[i] = if (sd > 1e-4f) (out[i] - mean) / sd else 0f
        small.recycle()
        VectorCodec.normalise(out)
    }

    /** Enrols the boss from one clear front-camera photo. Returns false when no face was found. */
    suspend fun enroll(bitmap: Bitmap): Boolean {
        val emb = embedding(bitmap) ?: return false
        store.facePrintProfile = VectorCodec.encode(emb)
        return true
    }

    /** Matches a photo against the enrolled boss. Returns (matched, score 0..1). */
    suspend fun match(bitmap: Bitmap): Pair<Boolean, Float> {
        val stored = store.facePrintProfile?.let { VectorCodec.decode(it) }
            ?: return false to 0f
        val emb = embedding(bitmap) ?: return false to 0f
        val score = VectorCodec.cosine(emb, stored)
        return (score >= 0.72f) to score
    }

    fun clear() {
        store.facePrintProfile = null
        store.faceLockEnabled = false
    }
}
