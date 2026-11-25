package com.badshah222.rani.ai.features.contacts

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.badshah222.rani.ai.data.phone.ContactEntry
import com.badshah222.rani.ai.data.phone.ContactsHelper
import com.badshah222.rani.ai.features.settings.TopBar
import com.badshah222.rani.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * ALL phone contacts (spec §14.3) — RANI reads them on-device so she can
 * call / message anyone by name. Contacts are NEVER uploaded and NEVER
 * leave the phone. Tap a contact to call, WhatsApp or SMS them.
 */
class ContactsViewModel(app: Application) : AndroidViewModel(app) {
    val contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val loading = MutableStateFlow(false)

    fun load() {
        val ctx = getApplication<Application>()
        if (!ContactsHelper.hasPermission(ctx)) { contacts.value = emptyList(); return }
        loading.value = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { ContactsHelper.loadAll(ctx) }
            contacts.value = list
            loading.value = false
        }
    }
}

@Composable
fun ContactsScreen(vm: ContactsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val loading by vm.loading.collectAsState()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ContactEntry?>(null) }
    val granted = remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted.value = ok
        if (ok) vm.load()
    }
    LaunchedEffect(Unit) { if (granted.value) vm.load() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Contacts", onBack)
        if (!granted.value) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("RANI tumhare saare contacts isi phone par parh sakti hai — taake naam se call ya message kar sake.", style = MaterialTheme.typography.bodyMedium)
                Text("Contacts kabhi upload nahi hote aur kabhi phone se bahar nahi jate.", style = MaterialTheme.typography.bodySmall, color = Mute)
                Button({ launcher.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts") }
            }
            return@Column
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Naam dhoondo…") }, singleLine = true)
        Text("${contacts.size} contacts — sirf is phone par", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Mute, style = MaterialTheme.typography.bodySmall)
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val shown = remember(query, contacts) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) contacts else contacts.filter { it.name.lowercase().contains(q) || it.numbers.any { n -> n.contains(q) } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown) { c ->
                Card(Modifier.fillMaxWidth().clickable { selected = c }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = VioletSoft)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(c.numbers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Mute)
                        }
                    }
                }
            }
            if (shown.isEmpty()) item { Text("Koi contact nahi mila.", Modifier.padding(16.dp), color = Mute) }
        }
    }
    selected?.let { c ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(c.name) },
            text = { Text(c.numbers.joinToString("\n")) },
            confirmButton = {
                TextButton({
                    selected = null
                    val num = ContactsHelper.bestNumber(c) ?: return@TextButton
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Default.Call, "Call"); Text(" Call") }
            },
            dismissButton = {
                Row {
                    TextButton({
                        selected = null
                        val num = ContactsHelper.bestNumber(c) ?: return@TextButton
                        val e164 = ContactsHelper.toE164(ctx, num) ?: num.filter { it.isDigit() || it == '+' }
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${e164.removePrefix("+")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("WhatsApp") }
                    TextButton({
                        selected = null
                        val num = ContactsHelper.bestNumber(c) ?: return@TextButton
                        ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Default.Message, "SMS"); Text(" SMS") }
                }
            },
        )
    }
}
