package com.yp.luminote.app.ui.language

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yp.luminote.app.R
import com.yp.luminote.app.ui.adaptive.luminoteSafeHorizontalPadding
import com.yp.luminote.app.ui.components.LuminoteScreenHeader
import com.yp.luminote.app.ui.components.LuminoteSettingsCard
import com.yp.luminote.app.ui.components.LuminoteSelectionControl
import com.yp.luminote.app.ui.components.LuminoteSelectionRow

private enum class AppLanguage {
    SYSTEM_DEFAULT,
    ENGLISH,
    POLISH,
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
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .luminoteSafeHorizontalPadding()
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        LuminoteScreenHeader(
            title = stringResource(R.string.language),
            backContentDescription = backDescription,
            onBackClick = onBackClick
        )

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.language_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        LuminoteSettingsCard {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
                    title = stringResource(R.string.language_polish),
                    selected = selectedLanguage == AppLanguage.POLISH,
                    onClick = {
                        localeManager.applicationLocales = LocaleList.forLanguageTags("pl")
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
}

@Composable
private fun LanguageOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    LuminoteSelectionRow(
        title = title,
        description = description,
        selected = selected,
        onClick = onClick,
        control = LuminoteSelectionControl.Radio
    )
}

private fun LocaleList.toAppLanguageOrNull(): AppLanguage? =
    when {
        isEmpty -> AppLanguage.SYSTEM_DEFAULT
        size() == 1 && get(0).toLanguageTag() == "en" -> AppLanguage.ENGLISH
        size() == 1 && get(0).toLanguageTag() == "pl" -> AppLanguage.POLISH
        size() == 1 && get(0).toLanguageTag() == "uk" -> AppLanguage.UKRAINIAN
        else -> null
    }
