package com.badshah222.rani.ai.data.auth

import android.app.Activity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import com.badshah222.rani.ai.core.Config
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Firebase Auth is the identity. The Firebase UID is the user id everywhere (Supabase `sub`). */
class AuthRepository(private val webClientId: String) {
    private val auth = FirebaseAuth.getInstance()
    val current: FirebaseUser? get() = auth.currentUser

    val userFlow: Flow<FirebaseUser?> = callbackFlow {
        val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(l); awaitClose { auth.removeAuthStateListener(l) }
    }

    suspend fun signInEmail(email: String, password: String) = auth.signInWithEmailAndPassword(email.trim(), password).await().user!!
    suspend fun registerEmail(email: String, password: String) = auth.createUserWithEmailAndPassword(email.trim(), password).await().user!!
    suspend fun sendPasswordReset(email: String) { auth.sendPasswordResetEmail(email.trim()).await() }

    suspend fun signInGoogle(activity: Activity): Pair<FirebaseUser, Boolean> {
        val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(activity).getCredential(activity, request).credential
        require(cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Unexpected credential type" }
        val google = GoogleIdTokenCredential.createFrom(cred.data)
        val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        return result.user!! to (result.additionalUserInfo?.isNewUser == true)
    }

    fun providerId(): String = current?.providerData?.firstOrNull { it.providerId != "firebase" }?.providerId ?: "password"
    fun signOut() = auth.signOut()

    /**
     * Returns a Firebase ID token carrying role=authenticated (needed by Supabase), or null.
     * If the claim is missing, asks the deployed `setSupabaseClaims` Cloud Function to stamp it, then refreshes.
     */
    suspend fun supabaseToken(): String? {
        val user = auth.currentUser ?: return null
        return runCatching {
            var r = user.getIdToken(false).await()
            if (r.claims["role"] != "authenticated") {
                FirebaseFunctions.getInstance(Config.FUNCTIONS_REGION).getHttpsCallable("setSupabaseClaims").call().await()
                r = user.getIdToken(true).await()
            }
            if (r.claims["role"] == "authenticated") r.token else null
        }.getOrNull()
    }
}
