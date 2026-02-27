package com.example.instantvoicetranslate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.instantvoicetranslate.audio.AudioCaptureManager
import com.example.instantvoicetranslate.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val audioSource: AudioCaptureManager.Source = AudioCaptureManager.Source.MICROPHONE,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "ru",
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showOriginalText: Boolean = true,
    val showPartialText: Boolean = true,
    val autoSpeak: Boolean = true,
    val muteMicDuringTts: Boolean = false,
    /** Record raw audio to WAV files for debugging (off by default). */
    val audioDiagnostics: Boolean = false,
    /** Custom output directory for diagnostic WAV files (empty = default). */
    val diagOutputDir: String = "",
)

@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_AUDIO_SOURCE = stringPreferencesKey("audio_source")
        private val KEY_SOURCE_LANG = stringPreferencesKey("source_language")
        private val KEY_TARGET_LANG = stringPreferencesKey("target_language")
        private val KEY_TTS_SPEED = floatPreferencesKey("tts_speed")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_SHOW_ORIGINAL = booleanPreferencesKey("show_original_text")
        private val KEY_SHOW_PARTIAL = booleanPreferencesKey("show_partial_text")
        private val KEY_AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        private val KEY_MUTE_MIC_DURING_TTS = booleanPreferencesKey("mute_mic_during_tts")
        private val KEY_AUDIO_DIAGNOSTICS = booleanPreferencesKey("audio_diagnostics")
        private val KEY_DIAG_OUTPUT_DIR = stringPreferencesKey("diag_output_dir")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            audioSource = prefs[KEY_AUDIO_SOURCE]?.let {
                try { AudioCaptureManager.Source.valueOf(it) }
                catch (_: Exception) { AudioCaptureManager.Source.MICROPHONE }
            } ?: AudioCaptureManager.Source.MICROPHONE,
            sourceLanguage = prefs[KEY_SOURCE_LANG] ?: "en",
            targetLanguage = prefs[KEY_TARGET_LANG] ?: "ru",
            ttsSpeed = prefs[KEY_TTS_SPEED] ?: 1.0f,
            ttsPitch = prefs[KEY_TTS_PITCH] ?: 1.0f,
            themeMode = prefs[KEY_THEME_MODE]?.let {
                try { ThemeMode.valueOf(it) }
                catch (_: Exception) { ThemeMode.SYSTEM }
            } ?: ThemeMode.SYSTEM,
            showOriginalText = prefs[KEY_SHOW_ORIGINAL] ?: true,
            showPartialText = prefs[KEY_SHOW_PARTIAL] ?: true,
            autoSpeak = prefs[KEY_AUTO_SPEAK] ?: true,
            muteMicDuringTts = prefs[KEY_MUTE_MIC_DURING_TTS] ?: false,
            audioDiagnostics = prefs[KEY_AUDIO_DIAGNOSTICS] ?: false,
            diagOutputDir = prefs[KEY_DIAG_OUTPUT_DIR] ?: "",
        )
    }

    suspend fun updateAudioSource(source: AudioCaptureManager.Source) {
        context.dataStore.edit { it[KEY_AUDIO_SOURCE] = source.name }
    }

    suspend fun updateSourceLanguage(lang: String) {
        context.dataStore.edit { it[KEY_SOURCE_LANG] = lang }
    }

    suspend fun updateTargetLanguage(lang: String) {
        context.dataStore.edit { it[KEY_TARGET_LANG] = lang }
    }

    suspend fun updateTtsSpeed(speed: Float) {
        context.dataStore.edit { it[KEY_TTS_SPEED] = speed }
    }

    suspend fun updateTtsPitch(pitch: Float) {
        context.dataStore.edit { it[KEY_TTS_PITCH] = pitch }
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun updateShowOriginalText(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_ORIGINAL] = show }
    }

    suspend fun updateShowPartialText(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_PARTIAL] = show }
    }

    suspend fun updateAutoSpeak(auto: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_SPEAK] = auto }
    }

    suspend fun updateMuteMicDuringTts(mute: Boolean) {
        context.dataStore.edit { it[KEY_MUTE_MIC_DURING_TTS] = mute }
    }

    suspend fun updateAudioDiagnostics(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUDIO_DIAGNOSTICS] = enabled }
    }

    suspend fun updateDiagOutputDir(dir: String) {
        context.dataStore.edit { it[KEY_DIAG_OUTPUT_DIR] = dir }
    }
}
