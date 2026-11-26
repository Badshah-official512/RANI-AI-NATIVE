package com.badshah222.rani.ai.features.permissions

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.badshah222.rani.ai.core.RaniAccessibilityService
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*

private data class Perm(val permission: String, val title: String, val why: String, val minSdk: Int = 1, val maxSdk: Int = Int.MAX_VALUE)

/** One row per runtime permission in the manifest (spec §5). */
private val PERMS = listOf(
    Perm(Manifest.permission.RECORD_AUDIO, "Microphone", "Lets you talk to RANI. Audio is processed by Android speech recognition; the app does not record or store it."),
    Perm(Manifest.permission.CAMERA, "Camera", "Camera Share / vision — only runs when you tap the button in a call."),
    Perm(Manifest.permission.READ_CONTACTS, "Contacts — all of them", "RANI reads ALL your contacts on this phone, so she can call or message anyone by name. Contacts stay on this phone — they are never uploaded and never leave the phone."),
    Perm(Manifest.permission.CALL_PHONE, "Phone calls", "Lets RANI open the dialer for a contact you named. You always press the call button yourself."),
    Perm(Manifest.permission.ACCESS_FINE_LOCATION, "Location", "\"Near me\" answers and location-aware replies. Only used when you ask."),
    Perm(Manifest.permission.READ_MEDIA_IMAGES, "Photos", "Share photos with RANI (vision) and save the images she makes.", Build.VERSION_CODES.TIRAMISU),
    Perm(Manifest.permission.READ_MEDIA_VIDEO, "Videos", "Share videos with RANI.", Build.VERSION_CODES.TIRAMISU),
    Perm(Manifest.permission.READ_MEDIA_AUDIO, "Audio files", "Share audio with RANI.", Build.VERSION_CODES.TIRAMISU),
    Perm(Manifest.permission.READ_EXTERNAL_STORAGE, "Storage (Android 12 and below)", "Read photos and files on older Android versions.", 1, Build.VERSION_CODES.S_V2),
    Perm(Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth", "Bluetooth headset on calls.", Build.VERSION_CODES.S),
    Perm(Manifest.permission.BLUETOOTH, "Bluetooth (Android 11 and below)", "Bluetooth headset on calls, on older Android versions.", 1, Build.VERSION_CODES.R),
    Perm(Manifest.permission.READ_CALENDAR, "Calendar", "Reminders and special days (birthdays, anniversaries)."),
    Perm(Manifest.permission.WRITE_CALENDAR, "Calendar (write)", "Save reminders and special days to your calendar."),
    Perm(Manifest.permission.POST_NOTIFICATIONS, "Notifications", "Tells you when support replies, announcements or reminders arrive.", Build.VERSION_CODES.TIRAMISU),
)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) { val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }; owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Permissions", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "RANI asks only for what a feature needs. Messages are sent only after you confirm the exact text — and type + send only works when Accessibility is ON (see below). Contacts and accessibility data never leave this phone.",
                    Modifier.padding(4.dp), color = Mute, style = MaterialTheme.typography.bodySmall,
                )
            }
            // ---- Phone control — every permission the full control needs, one by one ----
            item { Text("Phone control — you turn these on yourself", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Accessibility — full control", style = MaterialTheme.typography.titleMedium)
                            Text("Type, send, read the screen, scroll, tap buttons — only on your command. Without it, \"type and send a message\" will not work.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                        if (tick >= 0 && RaniAccessibilityService.isEnabled(ctx)) Text("ON ✓", color = Ok)
                        else Button({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Floating orb (overlay)", style = MaterialTheme.typography.titleMedium)
                            Text("A small orb over other apps — tap it for a voice call.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                        if (tick >= 0 && Settings.canDrawOverlays(ctx)) Text("ON ✓", color = Ok)
                        else Button({ ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) }) { Text("Open") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Exact alarms", style = MaterialTheme.typography.titleMedium)
                            Text("Reminders and automations fire exactly on time.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                        val alarmsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                        if (tick >= 0 && alarmsOk) Text("ON ✓", color = Ok)
                        else Button({ ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }) { Text("Open") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Battery — unrestricted", style = MaterialTheme.typography.titleMedium)
                            Text("So calls keep working in the background — keep RANI out of battery optimization.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                        val batteryOk = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
                        if (tick >= 0 && batteryOk) Text("ON ✓", color = Ok)
                        else Button({ ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) }) { Text("Open") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Modify system settings", style = MaterialTheme.typography.titleMedium)
                            Text("Automations change brightness and system settings for you.", style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                        if (tick >= 0 && Settings.System.canWrite(ctx)) Text("ON ✓", color = Ok)
                        else Button({ ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) { Text("Open") }
                    }
                } }
            }
            item { Text("App permissions", style = MaterialTheme.typography.titleMedium, color = Gold, modifier = Modifier.padding(top = 8.dp)) }
            items(PERMS.filter { Build.VERSION.SDK_INT >= it.minSdk && Build.VERSION.SDK_INT <= it.maxSdk }) { p ->
                val granted = tick >= 0 && ContextCompat.checkSelfPermission(ctx, p.permission) == PackageManager.PERMISSION_GRANTED
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(p.title, style = MaterialTheme.typography.titleMedium); Text(p.why, style = MaterialTheme.typography.bodySmall, color = Mute) }
                    if (granted) Text("Allowed ✓", color = Ok) else Button({ launcher.launch(p.permission) }) { Text("Allow") }
                } }
            }
            item { OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Open Android app settings") } }
        }
    }
}
