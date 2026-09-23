package com.yp.luminote.app.ui.language

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding

private enum class AppLanguage {
    SYSTEM_DEFAULT,
    ENGLISH,
    UKRAINIAN
}

@Composable
fun LanguageScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val localeManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(LocaleManager::class.java)
    }
    var applicationLocales by remember(localeManager) {
        mutableStateOf(localeManager.applicationLocales)
    }
    val selectedLanguage = applicationLocales.toAppLanguageOrNull()
    val backDescription = stringResource(R.string.back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .luminoteSafeHorizontalPadding()
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹",
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = backDescription }
                    .clickable(onClick = onBackClick),
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )

            Text(
                text = stringResource(R.string.language),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.language_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFBDBDBD)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageOption(
                title = stringResource(R.string.language_system_default),
                description = stringResource(R.string.language_system_default_description),
                selected = selectedLanguage == AppLanguage.SYSTEM_DEFAULT,
                onClick = {
                    localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
                    applicationLocales = localeManager.applicationLocales
                }
            )

            LanguageOption(
                title = stringResource(R.string.language_english),
                selected = selectedLanguage == AppLanguage.ENGLISH,
                onClick = {
                    localeManager.applicationLocales = LocaleList.forLanguageTags("en")
                    applicationLocales = localeManager.applicationLocales
                }
            )

            LanguageOption(
                title = stringResource(R.string.language_ukrainian),
                selected = selectedLanguage == AppLanguage.UKRAINIAN,
                onClick = {
                    localeManager.applicationLocales = LocaleList.forLanguageTags("uk")
                    applicationLocales = localeManager.applicationLocales
                }
            )
        }
    }
}

@Composable
private fun LanguageOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    val shape = RoundedCornerShape(24.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0xFF303030) else Color(0xFF101010))
            .border(
                width = 1.dp,
                color = if (selected) Color.White else Color(0xFF3D3D3D),
                shape = shape
            )
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            if (description != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFBDBDBD)
                )
            }
        }

        Text(
            text = if (selected) "✓" else "",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
    }
}

private fun LocaleList.toAppLanguageOrNull(): AppLanguage? =
    when {
        isEmpty -> AppLanguage.SYSTEM_DEFAULT
        size() == 1 && get(0).toLanguageTag() == "en" -> AppLanguage.ENGLISH
        size() == 1 && get(0).toLanguageTag() == "uk" -> AppLanguage.UKRAINIAN
        else -> null
    }
