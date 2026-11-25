package com.badshah222.rani.ai.features.auth

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.badshah222.rani.ai.RaniApp
import com.badshah222.rani.ai.R
import com.badshah222.rani.ai.ui.theme.Gold
import com.badshah222.rani.ai.ui.theme.Mute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Session state shared by the whole app: who is signed in, whether cloud sync works, blocked / maintenance flags. */
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RaniApp).container
    val auth = c.auth
    private val _user = MutableStateFlow<FirebaseUser?>(auth.current); val user: StateFlow<FirebaseUser?> = _user
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null); val error: StateFlow<String?> = _error
    private val _info = MutableStateFlow<String?>(null); val info: StateFlow<String?> = _info
    private val _blocked = MutableStateFlow(false); val blocked: StateFlow<Boolean> = _blocked
    private val _cloudReady = MutableStateFlow(false); val cloudReady: StateFlow<Boolean> = _cloudReady
    private val _maintenance = MutableStateFlow<String?>(null); val maintenance: StateFlow<String?> = _maintenance
    private val _announcements = MutableStateFlow<List<Pair<String, String>>>(emptyList()); val announcements: StateFlow<List<Pair<String, String>>> = _announcements

    init {
        viewModelScope.launch { auth.userFlow.collect { u -> _user.value = u; if (u != null) bootstrap(false) else { _blocked.value = false; _cloudReady.value = false } } }
    }

    /** After sign-in: stamp token claim, register the profile, record the login. Never blocks local use if it fails. */
    fun bootstrap(isNew: Boolean) = viewModelScope.launch {
        val u = auth.current ?: return@launch
        _maintenance.value = c.cloud.maintenanceMessage()
        val token = auth.supabaseToken()
        _cloudReady.value = token != null
        if (token == null) return@launch
        runCatching {
            _blocked.value = c.cloud.touchProfile(u.email, u.displayName, u.photoUrl?.toString())
            c.cloud.recordLogin(auth.providerId(), if (isNew) "register" else "login")
            _announcements.value = c.cloud.announcements()
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is FirebaseNetworkException -> "No internet connection."
        e is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_LOGIN_CREDENTIALS" -> "Wrong email or password."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "This email already has an account. Sign in instead."
            "ERROR_WEAK_PASSWORD" -> "Choose a stronger password (at least 6 characters)."
            "ERROR_INVALID_EMAIL" -> "That email address does not look right."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a few minutes and try again."
            else -> "Sign-in failed (${e.errorCode})."
        }
        e is GetCredentialCancellationException -> "Google sign-in was cancelled."
        e is NoCredentialException -> "No Google account found on this phone. Add one in Settings > Accounts."
        e is GetCredentialException -> "Google sign-in failed. Make sure Google Play services is up to date."
        else -> "Something went wrong. Please try again."
    }

    private fun run(block: suspend () -> Unit) { viewModelScope.launch { _loading.value = true; _error.value = null; try { block() } catch (e: Exception) { _error.value = friendly(e) } finally { _loading.value = false } } }
    fun signIn(email: String, pw: String) = run { auth.signInEmail(email, pw) }
    fun register(email: String, pw: String) = run { auth.registerEmail(email, pw); bootstrap(true) }
    fun google(activity: Activity) = run { val (_, isNew) = auth.signInGoogle(activity); bootstrap(isNew) }
    fun reset(email: String) = run { if (email.isBlank()) throw IllegalArgumentException(); auth.sendPasswordReset(email); _info.value = "Password reset email sent (if the account exists)." }
    fun signOut() { c.tts.stop(); auth.signOut() }
    fun clearMessages() { _error.value = null; _info.value = null }
}

@Composable
fun AuthScreen(vm: SessionViewModel, activity: Activity) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val loading by vm.loading.collectAsState(); val error by vm.error.collectAsState(); val info by vm.info.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.rani_face), null, Modifier.size(120.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(12.dp))
        Text("RANI AI", style = MaterialTheme.typography.headlineMedium, color = Gold)
        Text(stringResourceTagline(), style = MaterialTheme.typography.labelMedium, color = Mute, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        info?.let { Text(it, color = com.badshah222.rani.ai.ui.theme.Ok, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button({ vm.clearMessages(); if (register) vm.register(email, pw) else vm.signIn(email, pw) }, Modifier.fillMaxWidth(), enabled = !loading && email.isNotBlank() && pw.length >= 6) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (register) "Create account" else "Sign in")
        }
        OutlinedButton({ vm.clearMessages(); vm.google(activity) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !loading) { Text("Continue with Google") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ register = !register; vm.clearMessages() }) { Text(if (register) "I already have an account" else "Create an account") }
            if (!register) TextButton({ vm.clearMessages(); vm.reset(email) }) { Text("Forgot password?") }
        }
        Text("Your AI keys stay on this phone, encrypted. RANI AI has no shared key.", color = Mute, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable private fun stringResourceTagline() = androidx.compose.ui.res.stringResource(R.string.tagline)
