package com.example.instantvoicetranslate.ui.screens

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
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
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val partialText by viewModel.partialText.collectAsStateWithLifecycle()
    val originalText by viewModel.originalText.collectAsStateWithLifecycle()
    val translatedText by viewModel.translatedText.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // MediaProjection launcher for system audio
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection != null) {
                viewModel.setMediaProjection(projection)
                viewModel.startTranslation()
            }
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
                        } else {
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
                    containerColor = if (isRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRunning) "Stop" else "Start"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
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

            // Translated text (large)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (translatedText.isBlank() && !isRunning) {
                        Text(
                            text = "Press the mic button to start translating",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = translatedText.ifBlank { "..." },
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Original text (smaller, italic)
            AnimatedVisibility(
                visible = settings.showOriginalText && originalText.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = originalText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Partial ASR text (animated)
            AnimatedVisibility(
                visible = settings.showPartialText && isRunning && partialText.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "partial")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "partialAlpha"
                )

                Text(
                    text = partialText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .padding(horizontal = 4.dp),
                )
            }

            // Listening indicator
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
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
