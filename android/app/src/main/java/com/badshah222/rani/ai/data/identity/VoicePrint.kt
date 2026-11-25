package com.badshah222.rani.ai.data.identity

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.badshah222.rani.ai.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice-print memory (a RANI extra beyond Stonic — Stonic does NOT claim
 * voice-print; its identity is face-based).
 *
 * How it works, all on-device: the boss records three short samples of their
 * voice ("RANI, main Babu Jaan hoon"). Each sample is turned into a
 * 22-dimension feature vector (8 log band energies + spectral centroid +
 * zero-crossing rate + RMS, each as mean + standard deviation over 32 ms
 * frames, unit-normalised). The mean of the three samples is stored AES-256
 * encrypted in SecureStore. Verification records one fresh sample and scores
 * it with cosine similarity (>= 0.80 = the boss).
 *
 * When the voice-print gate is ON, RANI only starts a voice session for a
 * verified boss voice — anyone else hears "Sorry — I only talk to Babu Jaan."
 * This is a lightweight speaker check, not a biometric-grade system; it is a
 * convenience gate, and the user can switch it off in Settings.
 */
class VoicePrint(private val store: SecureStore) {
    val enrolled: Boolean get() = store.voicePrintProfile != null
    val gateOn: Boolean get() = store.voicePrintGate

    fun setGate(on: Boolean) {
        store.voicePrintGate = on
    }

    /** Records one ~2.5 s sample and returns its feature vector, or null on failure. */
    suspend fun recordSample(): FloatArray? = withContext(Dispatchers.IO) {
        recordPcm()?.let { features(it) }
    }

    /** Enrols the boss from three freshly recorded samples. Returns true when stored. */
    suspend fun enroll(onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val vecs = mutableListOf<FloatArray>()
        for (i in 1..3) {
            onProgress(i)
            val v = recordPcm()?.let { features(it) } ?: return@withContext false
            vecs.add(v)
        }
        val mean = FloatArray(22)
        for (v in vecs) for (i in v.indices) mean[i] += v[i] / vecs.size
        store.voicePrintProfile = VectorCodec.encode(VectorCodec.normalise(mean))
        true
    }

    /** One verification sample. Returns (matched, score 0..1). */
    suspend fun verify(): Pair<Boolean, Float> = withContext(Dispatchers.IO) {
        val stored = store.voicePrintProfile?.let { VectorCodec.decode(it) }
            ?: return@withContext false to 0f
        val v = recordPcm()?.let { features(it) } ?: return@withContext false to 0f
        val score = VectorCodec.cosine(v, stored)
        (score >= 0.80f) to score
    }

    fun clear() {
        store.voicePrintProfile = null
        store.voicePrintGate = false
    }

    private fun recordPcm(seconds: Float = 2.5f): ShortArray? {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: Exception) {
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val total = (rate * seconds).toInt()
        val out = ShortArray(total)
        return try {
            rec.startRecording()
            var read = 0
            while (read < total) {
                val n = rec.read(out, read, total - read)
                if (n <= 0) break
                read += n
            }
            if (read >= rate) out.copyOf(read) else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** 22-dim vector: 8 log band energies + centroid + ZCR + RMS, mean + std per frame. */
    internal fun features(pcm: ShortArray): FloatArray {
        val frame = 512
        val hop = 256
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + frame <= pcm.size) {
            val re = FloatArray(frame)
            val im = FloatArray(frame)
            for (i in 0 until frame) {
                val w = (0.5f - 0.5f * cos(2 * PI * i / (frame - 1)).toFloat()) // Hann window
                re[i] = pcm[start + i] / 32768f * w
            }
            fft(re, im)
            val bands = FloatArray(8)
            for (b in 0 until 8) {
                var sum = 0f
                val from = 1 + b * 32
                val to = min(256, from + 32)
                for (k in from until to) sum += re[k] * re[k] + im[k] * im[k]
                bands[b] = ln(1f + sum)
            }
            var num = 0f
            var den = 0f
            for (k in 1..256) {
                val m = re[k] * re[k] + im[k] * im[k]
                num += k * m
                den += m
            }
            val centroid = if (den > 0) num / den / 256f else 0f
            var zcr = 0
            var rms = 0f
            for (i in 1 until frame) {
                if ((pcm[start + i] >= 0) != (pcm[start + i - 1] >= 0)) zcr++
                rms += pcm[start + i] * pcm[start + i]
            }
            rms = sqrt(rms / frame) / 32768f
            val f = FloatArray(11)
            bands.copyInto(f)
            f[8] = centroid
            f[9] = zcr.toFloat() / frame
            f[10] = rms
            frames.add(f)
            start += hop
        }
        if (frames.isEmpty()) return FloatArray(22)
        val dims = 11
        val out = FloatArray(dims * 2)
        for (d in 0 until dims) {
            var mean = 0f
            for (fr in frames) mean += fr[d]
            mean /= frames.size
            var sd = 0f
            for (fr in frames) sd += (fr[d] - mean) * (fr[d] - mean)
            sd = sqrt(sd / frames.size)
            out[d] = mean
            out[dims + d] = sd
        }
        return VectorCodec.normalise(out)
    }

    /** Iterative radix-2 FFT, in place. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wR - im[i + k + len / 2] * wI
                    val vI = re[i + k + len / 2] * wI + im[i + k + len / 2] * wR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nWR = wR * wr - wI * wi
                    wI = wR * wi + wI * wr
                    wR = nWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
