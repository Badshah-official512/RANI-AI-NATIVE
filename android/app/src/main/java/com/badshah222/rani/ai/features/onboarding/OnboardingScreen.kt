package com.badshah222.rani.ai.features.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.core.RaniAccessibilityService
import com.badshah222.rani.ai.domain.Provider
import com.badshah222.rani.ai.ui.theme.Mute
import com.badshah222.rani.ai.ui.theme.Ok
import com.badshah222.rani.ai.ui.theme.Panel

/**
 * v1.8.0 §20.3 — onboarding, one question at a time (after login).
 *
 * Runs immediately after sign-in while SecureStore.onboardingDone == false.
 * ONE question per screen, progress "Step N of 10", a Back link, and
 * Allow / Skip on every step. Skipping is always allowed (the key step
 * allows "Skip for now" — the pooled-key path still works when signed in).
 * Deep-link steps (overlay / accessibility / battery) refresh their state
 * on resume (the PermissionsScreen LifecycleEventObserver tick pattern).
 * Finishing sets onboardingDone = true and navigates Home (the auto-call
 * then runs, §19.1). "Set up again" in Settings re-opens this flow.
 *
 * English only — not a single Urdu word in any UI text (§17). The Done
 * step title is "All set!" — the pet name is voice-only, never on screen.
 */
private enum class StepKind { Welcome, Permission, Key, Overlay, Accessibility, Battery, Done }

private data class StepSpec(
    val kind: StepKind,
    val title: String,
    val body: String,
    val permission: String? = null,
)

/** The 10 steps, exact order + exact copy (§20.3 table). */
private val STEPS = listOf(
    StepSpec(StepKind.Welcome, "Meet RANI", "Your AI girlfriend — she talks, she feels, she helps with your whole phone."),
    StepSpec(StepKind.Permission, "Can RANI hear you?", "She needs the microphone to talk with you.", Manifest.permission.RECORD_AUDIO),
    StepSpec(StepKind.Permission, "Can RANI see you?", "Front camera for face recognition and Face Lock.", Manifest.permission.CAMERA),
    StepSpec(StepKind.Permission, "Can RANI read your contacts?", "So you can say 'call Ali' — contacts never leave this phone.", Manifest.permission.READ_CONTACTS),
    StepSpec(StepKind.Permission, "Can RANI notify you?", "Replies and reminders, even when the app is closed.", Manifest.permission.POST_NOTIFICATIONS),
    StepSpec(StepKind.Key, "Add your free Gemini key", "RANI talks with your free Gemini key. No key yet? Get one free in a minute."),
    StepSpec(StepKind.Overlay, "Can RANI float over other apps?", "Her dot field and call card stay on screen while you use other apps."),
    StepSpec(StepKind.Accessibility, "Give RANI full control of your phone?", "She can open apps, search YouTube and Chrome, send WhatsApp messages, scroll and tap — only when you ask her to. Everything stays on your phone."),
    StepSpec(StepKind.Battery, "Keep RANI running in the background?", "So calls keep working when the app is closed — keep RANI out of battery optimization."),
    StepSpec(StepKind.Done, "All set!", "RANI is ready to talk. Open the app and she will greet you."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { (ctx.applicationContext as RaniApp).container.store }
    var step by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val spec = STEPS[step]

    // Key step state (local to this composition; the key itself is saved encrypted at once).
    var keyText by remember { mutableStateOf("") }
    var keyShow by remember { mutableStateOf(false) }
    var keySaved by remember { mutableStateOf(store.apiKey(Provider.GEMINI) != null) }

    fun isGranted(s: StepSpec): Boolean {
        if (tick < 0) return false // subscribes this read to the resume tick
        return when (s.kind) {
            StepKind.Permission -> when (s.permission) {
                // POST_NOTIFICATIONS does not exist below Android 13 — always granted there.
                Manifest.permission.POST_NOTIFICATIONS ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                else -> ContextCompat.checkSelfPermission(ctx, s.permission!!) == PackageManager.PERMISSION_GRANTED
            }
            StepKind.Key -> keySaved || store.apiKey(Provider.GEMINI) != null
            StepKind.Overlay -> Settings.canDrawOverlays(ctx)
            StepKind.Accessibility -> RaniAccessibilityService.isEnabled(ctx)
            StepKind.Battery -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            else -> true
        }
    }

    fun openStepSettings(s: StepSpec) {
        when (s.kind) {
            StepKind.Overlay -> ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            )
            StepKind.Accessibility -> ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            StepKind.Battery -> ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            )
            else -> Unit
        }
    }

    fun finish() {
        store.onboardingDone = true
        onDone()
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Step ${step + 1} of 10", style = MaterialTheme.typography.labelLarge, color = Mute)
        LinearProgressIndicator(
            progress = { (step + 1) / 10f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(spec.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(spec.body, style = MaterialTheme.typography.bodyLarge, color = Mute)

        when (spec.kind) {
            StepKind.Permission -> {
                Spacer(Modifier.height(8.dp))
                StepStateCard(
                    granted = isGranted(spec),
                    grantedText = "Allowed ✓",
                    actionText = "Allow",
                    onAction = { spec.permission?.let { permLauncher.launch(it) } },
                )
            }
            StepKind.Key -> {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    keyText, { keyText = it },
                    Modifier.fillMaxWidth(),
                    label = { Text(if (isGranted(spec)) "Gemini key saved" else "Paste your Gemini key") },
                    singleLine = true,
                    visualTransformation = if (keyShow) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton({ keyShow = !keyShow }) { Text(if (keyShow) "Hide" else "Show") } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({
                        if (keyText.isNotBlank()) {
                            store.setApiKey(Provider.GEMINI, keyText)
                            keyText = ""
                            keySaved = true
                            tick++
                        }
                    }) { Text("Save key") }
                    TextButton({
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                    }) { Text("Get a free key") }
                }
                if (isGranted(spec)) Text("Key saved ✓", color = Ok, style = MaterialTheme.typography.bodyMedium)
            }
            StepKind.Overlay, StepKind.Accessibility, StepKind.Battery -> {
                Spacer(Modifier.height(8.dp))
                StepStateCard(
                    granted = isGranted(spec),
                    grantedText = "ON ✓",
                    actionText = "Open",
                    onAction = { openStepSettings(spec) },
                )
            }
            else -> Unit
        }

        Spacer(Modifier.weight(1f))

        when (spec.kind) {
            StepKind.Welcome -> {
                Button({ step++ }, Modifier.fillMaxWidth()) { Text("Let's set up") }
            }
            StepKind.Done -> {
                Button({ finish() }, Modifier.fillMaxWidth()) { Text("Start talking") }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton({ step-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ step++ }) { Text(if (spec.kind == StepKind.Key) "Skip for now" else "Skip") }
                    Button({ step++ }) { Text("Continue") }
                }
            }
        }
    }
}

/** One row: granted state ("ON ✓" / "Allowed ✓", green) or the action button — the same pattern as PermissionsScreen.kt. */
@Composable
private fun StepStateCard(granted: Boolean, grantedText: String, actionText: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (granted) "You are all set for this step." else "Tap below to turn this on — you can skip it and come back later.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Mute,
            )
            if (granted) Text(grantedText, color = Ok) else Button(onAction) { Text(actionText) }
        }
    }
}
