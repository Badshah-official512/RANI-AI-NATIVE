package com.badshah222.rani.ai.features.home

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager // FIX (CI run #45): missing import — PackageManager.PERMISSION_GRANTED used at line 81
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.badshah222.rani.ai.core.CallEngineHolder
import android.webkit.PermissionRequest

/**
 * v1.9.0 §21.4 — the hero is a SLOT, not an owner.
 *
 * The call WebView lives in CallEngine (owned by MainActivity) and is only
 * ATTACHED into this hero slot while Home is visible. When Home leaves
 * composition the slot DETACHES the WebView back into the engine's
 * persistent holder — it is NEVER destroyed here. The old
 * DisposableEffect destroy (which killed the call the moment the user
 * opened Chat, Tools or Settings) is DELETED — that dispose was the bug.
 *
 * Reparenting (removeView -> addView) never recreates the WebView, so the
 * socket, the mic pipeline and the greeting all survive navigation.
 * The hero itself is a pure LiveState mirror (pill, caption, Mute/End,
 * emotion chip, character) — unchanged from v1.8.0.
 */
@Composable
fun HeroLiveSlot(modifier: Modifier = Modifier) {
    val engine = CallEngineHolder.engine
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { slot ->
            // The update block ONLY attaches the engine's WebView into the
            // slot — never loadUrl() here, never create a WebView here.
            engine?.attachTo(slot)
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            // Leaving Home detaches the call back to the engine's holder.
            // The call keeps running; returning Home re-attaches it.
            CallEngineHolder.engine?.detachToHolder()
        }
    }
}

/**
 * v1.8.0 §20.1.2 — static holder for the hero WebView's pending mic
 * permission request. MainActivity.onRequestPermissionsResult delegates
 * here (same REQ code + grant-mapping as RaniLiveActivity, REQ_WEB = 45).
 * v1.9.0: the holder is now used by CallEngine's WebChromeClient — the
 * WebView lives in the engine, but the permission flow is unchanged.
 */
object HeroMicPermission {
    const val REQ_WEB = 45

    @Volatile var pending: PermissionRequest? = null

    fun handleResult(activity: Activity, requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != REQ_WEB) return
        val req = pending ?: return
        pending = null
        val grant = ArrayList<String>()
        for (res in req.resources) {
            val perm = when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                else -> null
            }
            if (perm != null && ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) grant.add(res)
        }
        if (grant.isNotEmpty()) req.grant(grant.toTypedArray()) else {
            req.deny()
            Toast.makeText(activity, "Microphone permission is needed for the voice call.", Toast.LENGTH_LONG).show()
        }
    }
}
