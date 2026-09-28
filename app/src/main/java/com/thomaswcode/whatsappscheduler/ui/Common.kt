package com.thomaswcode.whatsappscheduler.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = actions,
    )
}

private val stampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

/**
 * The time now, then again at the start of every minute, so relative times ("in 18 h 52 min") and
 * time chips stay right while a screen is open.
 */
fun minuteClock(): Flow<ZonedDateTime> = flow {
    while (true) {
        val now = ZonedDateTime.now()
        emit(now)
        delay(60_000L - now.second * 1_000L - now.nano / 1_000_000)
    }
}

/** Formats epoch millis in the device zone for display. */
fun formatStamp(millis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
    millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(stampFormatter) } ?: "—"

/**
 * Gates an action behind the device's biometric or credential prompt. Calls [onSuccess] directly
 * when the device has no secure lock, since there is nothing to authenticate against.
 */
fun Activity.authenticateThen(title: String, onFailure: (String) -> Unit = {}, onSuccess: () -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val manager = getSystemService(BiometricManager::class.java)
    if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        onSuccess()
        return
    }
    val prompt = BiometricPrompt.Builder(this)
        .setTitle(title)
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(
        CancellationSignal(),
        mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFailure(errString?.toString() ?: "error $errorCode")
        },
    )
}
