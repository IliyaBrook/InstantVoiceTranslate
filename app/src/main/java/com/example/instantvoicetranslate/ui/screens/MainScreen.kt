package com.example.instantvoicetranslate.ui.screens

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.data.ModelStatus
import com.example.instantvoicetranslate.ui.utils.LanguageUtils
import com.example.instantvoicetranslate.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
) {
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val partialText by viewModel.partialText.collectAsStateWithLifecycle()
    val originalText by viewModel.originalText.collectAsStateWithLifecycle()
    val translatedText by viewModel.translatedText.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // MediaProjection consent launcher for system audio.
    // On Android 14+ the MediaProjection must be created inside a foreground
    // service, so we only forward the resultCode + data Intent to the service.
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startTranslationWithProjection(result.resultCode, result.data!!)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = LanguageUtils.displayName(settings.sourceLanguage),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = " \u2192 ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = LanguageUtils.displayName(settings.targetLanguage),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (modelStatus is ModelStatus.Ready) {
                FloatingActionButton(
                    onClick = {
                        if (isRunning) {
                            viewModel.stopTranslation()
                        } else if (!isStarting) {
                            if (settings.audioSource == AudioCaptureManager.Source.SYSTEM_AUDIO) {
                                val projectionManager = context.getSystemService(
                                    Context.MEDIA_PROJECTION_SERVICE
                                ) as MediaProjectionManager
                                mediaProjectionLauncher.launch(
                                    projectionManager.createScreenCaptureIntent()
                                )
                            } else {
                                viewModel.startTranslation()
                            }
                        }
                    },
                    containerColor = when {
                        isRunning -> MaterialTheme.colorScheme.error
                        isStarting -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    }
                ) {
                    when {
                        isStarting -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        isRunning -> Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                        )
                        else -> Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start",
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Audio source selector
            AudioSourceSelector(
                selected = settings.audioSource,
                onSelect = { viewModel.updateAudioSource(it) },
                enabled = !isRunning,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Model status
            when (val status = modelStatus) {
                is ModelStatus.NotDownloaded -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "STT model not downloaded",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = { viewModel.downloadModel() }) {
                                Text("Download (~44 MB)")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                is ModelStatus.Downloading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Downloading model...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                status.currentFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(status.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                is ModelStatus.Initializing -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Initializing...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (status.step.isNotBlank()) {
                                Text(
                                    status.step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                is ModelStatus.Error -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Download error: ${status.message}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = { viewModel.downloadModel() }) {
                                Text("Retry")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                ModelStatus.Ready -> { /* Ready, no card needed */ }
            }

            // --- Text sections (each scrollable, fill remaining space equally) ---

            // Translation (always visible)
            TextSection(
                label = "Translation",
                text = translatedText,
                placeholder = "Press the mic button to start translating",
                textStyle = MaterialTheme.typography.headlineSmall,
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.weight(1f),
            )

            // Original text
            if (settings.showOriginalText) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TextSection(
                    label = "Original",
                    text = originalText,
                    placeholder = "...",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            // Partial ASR
            if (settings.showPartialText) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TextSection(
                    label = "Partial ASR",
                    text = if (isRunning) partialText else "",
                    placeholder = "...",
                    textStyle = MaterialTheme.typography.bodySmall,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.weight(1f),
                )
            }

            // Listening indicator
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Listening...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
        }
    }
}

@Composable
private fun TextSection(
    label: String,
    text: String,
    placeholder: String,
    textStyle: TextStyle,
    textColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
            ) {
                Text(
                    text = text.ifBlank { placeholder },
                    style = textStyle,
                    color = if (text.isBlank()) textColor.copy(alpha = 0.4f) else textColor,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioSourceSelector(
    selected: AudioCaptureManager.Source,
    onSelect: (AudioCaptureManager.Source) -> Unit,
    enabled: Boolean,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == AudioCaptureManager.Source.MICROPHONE,
            onClick = { onSelect(AudioCaptureManager.Source.MICROPHONE) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = enabled,
            icon = {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        ) {
            Text("Microphone")
        }
        SegmentedButton(
            selected = selected == AudioCaptureManager.Source.SYSTEM_AUDIO,
            onClick = { onSelect(AudioCaptureManager.Source.SYSTEM_AUDIO) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = enabled,
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        ) {
            Text("System Audio")
        }
    }
}
