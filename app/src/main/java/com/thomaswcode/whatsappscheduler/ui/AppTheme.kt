package com.thomaswcode.whatsappscheduler.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val green = Color(0xFF128C7E)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }.let { dynamic ->
        if (dark) dynamic else dynamic.copy(primary = green)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
