package com.badshah222.rani.ai.features.monitor

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ProcInfo(val name: String, val importance: Int, val pid: Int)
data class MonitorSnapshot(
    val cpuPct: Float, val ramUsedPct: Float, val ramUsedMb: Long, val ramTotalMb: Long,
    val diskUsedPct: Float, val diskUsedGb: Float, val diskTotalGb: Float,
    val batteryPct: Int, val charging: Boolean, val uptimeMin: Long, val procCount: Int,
    val topProcs: List<ProcInfo>,
)

/**
 * MYRA SystemMonitorPage — CPU / RAM / Disk / Battery gauges (green <60,
 * amber <85, red above), uptime + process count, top processes + kill.
 * On-device only; nothing is uploaded.
 */
class SystemMonitorViewModel(app: Application) : AndroidViewModel(app) {
    val snap = MutableStateFlow<MonitorSnapshot?>(null)

    private fun readCpuTimes(): Pair<Long, Long>? = runCatching {
        val parts = File("/proc/stat").readLines().first().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + parts.getOrElse(4) { 0 }
        val total = parts.sum()
        total to idle
    }.getOrNull()

    private suspend fun cpuPct(): Float {
        val a = readCpuTimes() ?: return 0f
        delay(500)
        val b = readCpuTimes() ?: return 0f
        val totalDiff = (b.first - a.first).toFloat()
        val idleDiff = (b.second - a.second).toFloat()
        return if (totalDiff <= 0) 0f else ((1f - idleDiff / totalDiff) * 100f).coerceIn(0f, 100f)
    }

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val cpu = cpuPct()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val ramTotalMb = mem.totalMem / (1024 * 1024)
            val ramUsedMb = (mem.totalMem - mem.availMem) / (1024 * 1024)
            val stat = StatFs(ctx.filesDir.path)
            val totalB = stat.totalBytes.toFloat()
            val availB = stat.availableBytes.toFloat()
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val procs = am.runningAppProcesses.orEmpty()
            val top = procs.sortedBy { it.importance }.take(10).map { ProcInfo(it.processName, it.importance, it.pid) }
            snap.value = MonitorSnapshot(
                cpuPct = cpu,
                ramUsedPct = if (ramTotalMb > 0) ramUsedMb * 100f / ramTotalMb else 0f, ramUsedMb = ramUsedMb, ramTotalMb = ramTotalMb,
                diskUsedPct = if (totalB > 0) (1f - availB / totalB) * 100f else 0f,
                diskUsedGb = (totalB - availB) / (1024f * 1024f * 1024f), diskTotalGb = totalB / (1024f * 1024f * 1024f),
                batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = bm.isCharging,
                uptimeMin = SystemClock.elapsedRealtime() / 60000,
                procCount = procs.size, topProcs = top,
            )
        }
    }

    fun kill(name: String) {
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(name)
        refresh()
    }
}

@Composable
private fun Gauge(label: String, pct: Float, detail: String) {
    val color = when { pct < 60 -> Ok; pct < 85 -> Gold; else -> Bad }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { (pct / 100f).coerceIn(0f, 1f) }, Modifier.size(56.dp), color = color, strokeWidth = 5.dp)
                Text("${pct.toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.padding(start = 12.dp)) { Text(label, style = MaterialTheme.typography.titleMedium); Text(detail, color = Mute, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun SystemMonitorScreen(vm: SystemMonitorViewModel, onBack: () -> Unit) {
    val snap by vm.snap.collectAsState()
    LaunchedEffect(Unit) {
        while (true) { vm.refresh(); delay(2000) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("System Monitor", onBack)
        val s = snap
        if (s == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Gauge("CPU", s.cpuPct, "Processor load — live") }
            item { Gauge("RAM", s.ramUsedPct, "${s.ramUsedMb} MB / ${s.ramTotalMb} MB") }
            item { Gauge("Disk", s.diskUsedPct, "%.1f GB / %.1f GB".format(s.diskUsedGb, s.diskTotalGb)) }
            item { Gauge("Battery", s.batteryPct.toFloat(), if (s.charging) "Charging" else "Not charging") }
            item {
                Text("Uptime: ${s.uptimeMin / 60}h ${s.uptimeMin % 60}m · Processes: ${s.procCount}", color = Mute, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            }
            item { Text("Top processes", style = MaterialTheme.typography.titleMedium, color = Gold) }
            items(s.topProcs) { p ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.bodyMedium); Text("pid ${p.pid} · importance ${p.importance}", color = Mute, style = MaterialTheme.typography.bodySmall) }
                        TextButton({ vm.kill(p.name) }) { Text("Kill", color = Bad) }
                    }
                }
            }
        }
    }
}
