package com.thomaswcode.whatsappscheduler.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.text.format.DateFormat
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.thomaswcode.whatsappscheduler.R
import com.thomaswcode.whatsappscheduler.util.Texts
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
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
        actions = actions,
    )
}

private val stampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
private val stampFormatter12: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a")

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

/** Formats epoch millis in the device zone for display, in 24-hour or ([is24Hour] false) 12-hour time. */
fun formatStamp(millis: Long?, zone: ZoneId = ZoneId.systemDefault(), is24Hour: Boolean = true): String =
    millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(if (is24Hour) stampFormatter else stampFormatter12) } ?: "—"

/** [formatStamp] with the phone's own 12/24-hour choice (improvements I1). */
@Composable
fun rememberStampFormat(): (Long?) -> String {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(is24) {
        val f: (Long?) -> String = { formatStamp(it, is24Hour = is24) }
        f
    }
}

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
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) =
                onFailure(errString?.toString() ?: getString(R.string.auth_error_code, errorCode))
        },
    )
}

/** strings.xml as [Texts], for helpers that code outside composition shares; follows configuration changes. */
@Composable
fun rememberTexts(): Texts {
    val resources = LocalResources.current
    return remember(resources) { Texts.of(resources) }
}
