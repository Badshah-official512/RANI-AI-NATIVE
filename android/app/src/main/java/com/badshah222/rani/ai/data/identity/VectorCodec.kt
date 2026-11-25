package com.badshah222.rani.ai.data.identity

import android.util.Base64
import kotlin.math.sqrt

/** Shared helpers for the on-device identity vectors (voice-print, face-print).
 *  Vectors are unit-normalised float arrays, stored Base64 in the encrypted
 *  SecureStore and never sent anywhere. */
internal object VectorCodec {
    fun encode(v: FloatArray): String {
        val buf = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    fun decode(s: String): FloatArray? = runCatching {
        val bytes = Base64.decode(s, Base64.DEFAULT)
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        out
    }.getOrNull()

    /** Cosine similarity of two unit-normalised vectors: 1 = identical, 0 = unrelated. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d > 0f) dot / d else 0f
    }

    fun normalise(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}
