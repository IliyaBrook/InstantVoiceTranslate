package com.example.instantvoicetranslate.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.instantvoicetranslate.ui.theme.ThemeMode
import com.example.instantvoicetranslate.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Language settings
            SectionHeader("Languages")

            LanguageSelector(
                label = "Source language",
                selected = settings.sourceLanguage,
                onSelect = { viewModel.updateSourceLanguage(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LanguageSelector(
                label = "Target language",
                selected = settings.targetLanguage,
                onSelect = { viewModel.updateTargetLanguage(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // TTS settings
            SectionHeader("Text-to-Speech")

            SliderSetting(
                label = "Speech speed",
                value = settings.ttsSpeed,
                valueRange = 0.5f..2.0f,
                onValueChange = { viewModel.updateTtsSpeed(it) }
            )

            SliderSetting(
                label = "Pitch",
                value = settings.ttsPitch,
                valueRange = 0.5f..2.0f,
                onValueChange = { viewModel.updateTtsPitch(it) }
            )

            SwitchSetting(
                label = "Auto-speak translations",
                checked = settings.autoSpeak,
                onCheckedChange = { viewModel.updateAutoSpeak(it) }
            )

            SwitchSetting(
                label = "Mute mic during TTS",
                checked = settings.muteMicDuringTts,
                onCheckedChange = { viewModel.updateMuteMicDuringTts(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Display settings
            SectionHeader("Display")

            SwitchSetting(
                label = "Show original text",
                checked = settings.showOriginalText,
                onCheckedChange = { viewModel.updateShowOriginalText(it) }
            )

            SwitchSetting(
                label = "Show partial ASR text",
                checked = settings.showPartialText,
                onCheckedChange = { viewModel.updateShowPartialText(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Theme
            SectionHeader("Theme")
            ThemeSelector(
                selected = settings.themeMode,
                onSelect = { viewModel.updateThemeMode(it) }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val languages = listOf("en" to "English", "ru" to "Russian", "es" to "Spanish", "de" to "German", "fr" to "French", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean")
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val displayLangs = languages.take(4) // Show only first 4 to fit
            displayLangs.forEachIndexed { index, (code, name) ->
                SegmentedButton(
                    selected = selected == code,
                    onClick = { onSelect(code) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = displayLangs.size)
                ) {
                    Text(code.uppercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "%.1f".format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = 5,
        )
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size)
            ) {
                Text(
                    when (mode) {
                        ThemeMode.SYSTEM -> "System"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    }
                )
            }
        }
    }
}
