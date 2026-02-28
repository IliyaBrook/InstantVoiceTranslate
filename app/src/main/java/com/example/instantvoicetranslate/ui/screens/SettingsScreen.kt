package com.example.instantvoicetranslate.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.instantvoicetranslate.R
import com.example.instantvoicetranslate.data.ModelStatus
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
    val nllbStatus by viewModel.nllbModelStatus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            // App Language
            SectionHeader(stringResource(R.string.section_app_language))
            AppLanguageSelector()

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Language settings
            SectionHeader(stringResource(R.string.section_languages))

            LanguageDropdown(
                label = stringResource(R.string.label_source_language),
                selected = settings.sourceLanguage,
                languages = LanguageUtils.sourceLanguages,
                onSelect = { viewModel.updateSourceLanguage(it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            LanguageDropdown(
                label = stringResource(R.string.label_target_language),
                selected = settings.targetLanguage,
                languages = LanguageUtils.targetLanguagesForMode(settings.offlineMode),
                onSelect = { viewModel.updateTargetLanguage(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Offline Translation section
            SectionHeader(stringResource(R.string.section_offline_translation))

            OfflineModeSection(
                offlineMode = settings.offlineMode,
                nllbStatus = nllbStatus,
                onToggleOfflineMode = { viewModel.updateOfflineMode(it) },
                onDownload = { viewModel.downloadNllbModel() },
                onDelete = { viewModel.deleteNllbModel() },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // TTS settings
            SectionHeader(stringResource(R.string.section_tts))

            SliderSetting(
                label = stringResource(R.string.label_speech_speed),
                value = settings.ttsSpeed,
                valueRange = 0.5f..2.0f,
                onValueChange = { viewModel.updateTtsSpeed(it) }
            )

            SliderSetting(
                label = stringResource(R.string.label_pitch),
                value = settings.ttsPitch,
                valueRange = 0.5f..2.0f,
                onValueChange = { viewModel.updateTtsPitch(it) }
            )

            SwitchSetting(
                label = stringResource(R.string.label_auto_speak),
                checked = settings.autoSpeak,
                onCheckedChange = { viewModel.updateAutoSpeak(it) }
            )

            SwitchSetting(
                label = stringResource(R.string.label_mute_mic_during_tts),
                checked = settings.muteMicDuringTts,
                onCheckedChange = { viewModel.updateMuteMicDuringTts(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Display settings
            SectionHeader(stringResource(R.string.section_display))

            SwitchSetting(
                label = stringResource(R.string.label_show_original_text),
                checked = settings.showOriginalText,
                onCheckedChange = { viewModel.updateShowOriginalText(it) }
            )

            SwitchSetting(
                label = stringResource(R.string.label_show_partial_asr),
                checked = settings.showPartialText,
                onCheckedChange = { viewModel.updateShowPartialText(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Theme
            SectionHeader(stringResource(R.string.section_theme))
            ThemeSelector(
                selected = settings.themeMode,
                onSelect = { viewModel.updateThemeMode(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Diagnostics
            SectionHeader(stringResource(R.string.section_diagnostics))

            SwitchSetting(
                label = stringResource(R.string.label_record_audio_wav),
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
                        Text(stringResource(R.string.action_choose_output_dir))
                    }
                    if (settings.diagOutputDir.isNotBlank()) {
                        TextButton(onClick = { viewModel.updateDiagOutputDir("") }) {
                            Text(stringResource(R.string.action_reset))
                        }
                    }
                }

                // Show selected path
                val context2 = LocalContext.current
                val displayPath = if (settings.diagOutputDir.isBlank()) {
                    stringResource(R.string.diag_default_path, context2.packageName)
                } else {
                    val decoded = settings.diagOutputDir.toUri().lastPathSegment
                        ?.replace("primary:", "/storage/emulated/0/")
                        ?: settings.diagOutputDir
                    stringResource(R.string.diag_custom_path, decoded)
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

/**
 * App language selector using Android Per-App Language Preferences API.
 * Options: System default, English, Russian.
 * Uses AppCompatDelegate.setApplicationLocales() for backward compatibility.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLanguageSelector() {
    // Available app languages: tag -> display name (in native language)
    val appLanguages = remember {
        listOf(
            "" to null,       // System default — display name resolved from string resource
            "en" to "English",
            "ru" to "\u0420\u0443\u0441\u0441\u043a\u0438\u0439",
        )
    }

    val systemDefaultLabel = stringResource(R.string.app_language_system)

    // Read current locale from AppCompat
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val currentTag = if (currentLocales.isEmpty) "" else currentLocales.toLanguageTags()

    // Find current selection
    val selectedTag = appLanguages.firstOrNull { (tag, _) ->
        if (tag.isEmpty()) currentLocales.isEmpty
        else currentTag.startsWith(tag, ignoreCase = true)
    }?.first ?: ""

    val selectedDisplayName = appLanguages.firstOrNull { it.first == selectedTag }
        ?.let { (tag, name) -> if (tag.isEmpty()) systemDefaultLabel else name }
        ?: systemDefaultLabel

    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedDisplayName,
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
            appLanguages.forEach { (tag, name) ->
                val displayName = if (tag.isEmpty()) systemDefaultLabel else name ?: tag
                DropdownMenuItem(
                    text = { Text(displayName) },
                    onClick = {
                        expanded = false
                        val newLocales = if (tag.isEmpty()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(tag)
                        }
                        AppCompatDelegate.setApplicationLocales(newLocales)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun OfflineModeSection(
    offlineMode: Boolean,
    nllbStatus: ModelStatus,
    onToggleOfflineMode: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val isModelReady = nllbStatus is ModelStatus.Ready

    // Offline mode toggle — disabled until model is downloaded
    SwitchSetting(
        label = stringResource(R.string.offline_mode),
        checked = offlineMode,
        onCheckedChange = onToggleOfflineMode,
        enabled = isModelReady,
    )

    Spacer(modifier = Modifier.height(8.dp))

    when (nllbStatus) {
        is ModelStatus.NotDownloaded -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_download_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.offline_download_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_download))
                    }
                }
            }
        }

        is ModelStatus.Downloading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        nllbStatus.currentFile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { nllbStatus.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${(nllbStatus.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is ModelStatus.Initializing -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_initializing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (nllbStatus.step.isNotBlank()) {
                        Text(
                            nllbStatus.step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        is ModelStatus.Ready -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_model_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.offline_model_size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text(stringResource(R.string.action_delete_model))
                    }
                }
            }
        }

        is ModelStatus.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.offline_download_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        nllbStatus.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
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
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    }
                )
            }
        }
    }
}
