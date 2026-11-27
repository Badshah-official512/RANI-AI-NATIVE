package com.badshah222.rani.ai.features.auth

import android.app.Activity
import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.badshah222.rani.ai.ui.character.DotField
import com.badshah222.rani.ai.ui.stonic.RaniAvatar
import com.badshah222.rani.ai.ui.theme.Gold
import com.badshah222.rani.ai.ui.theme.Ink
import com.badshah222.rani.ai.ui.theme.Mute
import com.badshah222.rani.ai.ui.theme.Panel
import com.badshah222.rani.ai.ui.theme.Violet
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
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_LOGIN_CREDENTIALS" -> "Wrong email or password. Try again, or reset it."
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

/**
 * v1.9.0 §21.5 — Stonic-level login + signup. The auth logic (Firebase
 * email/password + Google via Credential Manager, reset email,
 * blocked-account state, min 6-char password) does not change — only the
 * presentation: dark stage gradient, compact dot-circle brand mark (the
 * §20.2 dot field, small, calm drift — she is not speaking yet),
 * RANI medallion on the radial glow, one-line H1, Stonic panel card
 * (28 dp radius, violet stroke) holding the fields and buttons, honest
 * inline validation in plain English, gradient primary button, and the
 * corrected privacy line: "Your Gemini key stays on this phone, encrypted."
 * English only, no Urdu word anywhere.
 */
@Composable
fun AuthScreen(vm: SessionViewModel, activity: Activity) {
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var pwError by remember { mutableStateOf<String?>(null) }
    val loading by vm.loading.collectAsState(); val error by vm.error.collectAsState(); val info by vm.info.collectAsState()

    // Entry: fields and buttons fade + rise (transform + opacity only, max 650 ms, once).
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(650), label = "authAlpha")
    val rise by animateFloatAsState(if (visible) 0f else 24f, tween(650), label = "authRise")

    fun emailLooksOk(s: String) = s.contains("@") && s.substringAfter("@").contains(".")
    fun submit() {
        emailError = null; pwError = null
        var bad = false
        if (!emailLooksOk(email.trim())) { emailError = "That email does not look right."; bad = true }
        if (pw.length < 6) { pwError = "Password needs at least 6 characters."; bad = true }
        if (bad) return
        vm.clearMessages()
        if (register) vm.register(email.trim(), pw) else vm.signIn(email.trim(), pw)
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, Panel, Ink))),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp)
                .graphicsLayer { this.alpha = alpha; translationY = rise },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            // (1) Compact dot-circle brand mark — the §20.2 dot field,
            // small, calm drift, no vibration (she is not speaking yet).
            Box(
                Modifier.size(110.dp).clip(CircleShape)
                    .background(Color(0xFF171130))
                    .border(1.5.dp, Violet.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DotField(audioLevel = 0f, glowColor = Violet, energy = 0.5f, lowPower = false, modifier = Modifier.size(96.dp))
            }
            Spacer(Modifier.height(12.dp))
            // (2) RANI head/shoulders medallion on the radial emotion glow.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(120.dp)
                        .background(Brush.radialGradient(listOf(Violet.copy(alpha = 0.35f), Color.Transparent))),
                )
                RaniAvatar(96.dp, corner = 48.dp)
            }
            Spacer(Modifier.height(10.dp))
            // (3) One-line H1 + short tagline.
            Text("RANI AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Gold)
            Text(stringResourceTagline(), style = MaterialTheme.typography.labelMedium, color = Mute, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            // (5) Stonic panel card holding the fields and buttons.
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(28.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Violet.copy(alpha = 0.55f)),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (register) "Create your account" else "Welcome back",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        email, { email = it; emailError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        isError = emailError != null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Violet, unfocusedBorderColor = Mute.copy(alpha = 0.5f),
                            focusedLabelColor = Violet,
                        ),
                    )
                    emailError?.let { FieldError(it) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        pw, { pw = it; pwError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pwError != null,
                        trailingIcon = {
                            IconButton({ pwVisible = !pwVisible }) {
                                Icon(
                                    if (pwVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (pwVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Violet, unfocusedBorderColor = Mute.copy(alpha = 0.5f),
                            focusedLabelColor = Violet,
                        ),
                    )
                    pwError?.let { FieldError(it) }
                    // "Forgot password?" sits right-aligned under the
                    // password field (sign-in mode only), not at the bottom.
                    if (!register) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton({ vm.clearMessages(); vm.reset(email.trim()) }) {
                                Text("Forgot password?", color = Violet)
                            }
                        }
                    }
                    error?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    info?.let { Text(it, color = com.badshah222.rani.ai.ui.theme.Ok, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(12.dp))
                    // Primary: full-width gradient pill, spinner INSIDE the
                    // button while loading (no double-tap double-submit).
                    Box(
                        Modifier.fillMaxWidth().height(56.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF2F7CF6), Color(0xFF7C4DFF))))
                            .clickable(enabled = !loading && email.isNotBlank() && pw.length >= 6) { submit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text(
                            if (register) "Create account" else "Sign in",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        { vm.clearMessages(); vm.google(activity) },
                        Modifier.fillMaxWidth().height(56.dp),
                        enabled = !loading,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text("G", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Continue with Google", color = Color.White)
                    }
                    Spacer(Modifier.height(6.dp))
                    // Mode switch: swaps the form WITHOUT clearing the
                    // email field (only the password clears, for safety).
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton({
                            register = !register
                            vm.clearMessages()
                            pw = ""; pwError = null; emailError = null
                        }) {
                            Text(
                                if (register) "I already have an account" else "Create an account",
                                color = Violet,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // (6) Corrected privacy line — the pooled-key fallback exists,
            // so "RANI AI has no shared key" is no longer true.
            Text(
                "Your Gemini key stays on this phone, encrypted.",
                color = Mute, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text("Made by BADSHAH222", color = Mute, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FieldError(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun stringResourceTagline() = androidx.compose.ui.res.stringResource(R.string.tagline)
