package com.example.instantvoicetranslate.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.instantvoicetranslate.ui.theme.ThemeMode
import com.example.instantvoicetranslate.ui.utils.LanguageUtils
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

            LanguageDropdown(
                label = "Source language",
                selected = settings.sourceLanguage,
                languages = LanguageUtils.sourceLanguages,
                onSelect = { viewModel.updateSourceLanguage(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LanguageDropdown(
                label = "Target language",
                selected = settings.targetLanguage,
                languages = LanguageUtils.targetLanguages,
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Diagnostics
            SectionHeader("Diagnostics")

            SwitchSetting(
                label = "Record audio to WAV files",
                checked = settings.audioDiagnostics,
                onCheckedChange = { viewModel.updateAudioDiagnostics(it) }
            )

            if (settings.audioDiagnostics) {
                val context = LocalContext.current
                val dirPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        viewModel.updateDiagOutputDir(uri.toString())
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { dirPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Choose output directory")
                    }
                    if (settings.diagOutputDir.isNotBlank()) {
                        TextButton(onClick = { viewModel.updateDiagOutputDir("") }) {
                            Text("Reset")
                        }
                    }
                }

                // Show selected path
                val displayPath = if (settings.diagOutputDir.isBlank()) {
                    "Default: Android/data/${context.packageName}/files/audio_diag/"
                } else {
                    val decoded = settings.diagOutputDir.toUri().lastPathSegment
                        ?.replace("primary:", "/storage/emulated/0/")
                        ?: settings.diagOutputDir
                    "Path: $decoded"
                }
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            }

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
private fun LanguageDropdown(
    label: String,
    selected: String,
    languages: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = languages.toMap()[selected] ?: selected.uppercase()

    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = "$selectedName (${selected.uppercase()})",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                languages.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text("$name (${code.uppercase()})") },
                        onClick = {
                            onSelect(code)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
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
