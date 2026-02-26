package com.example.instantvoicetranslate.translation

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeTranslator @Inject constructor() : TextTranslator {

    companion object {
        private const val TAG = "FreeTranslator"
        private const val PRIMARY_URL = "https://translate.yandex.net/api/v1/tr.json/translate"
        private const val FALLBACK_URL = "https://translate.toil.cc/v2/translate"
        private const val USER_AGENT =
            "ru.yandex.translate/22.11.8.22364114 (samsung SM-A505GM; Android 12)"
        private const val CACHE_SIZE = 200
    }

    private val _isAvailable = MutableStateFlow(true)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val ucid = UUID.randomUUID().toString().replace("-", "")
    private val counter = AtomicInteger(0)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, String>(CACHE_SIZE)

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        if (text.isBlank()) return Result.success("")

        val cacheKey = "$from|$to|$text"
        cache.get(cacheKey)?.let { return Result.success(it) }

        return withContext(Dispatchers.IO) {
            try {
                val result = translatePrimary(text, from, to)
                cache.put(cacheKey, result)
                _isAvailable.value = true
                Result.success(result)
            } catch (e: Exception) {
                Log.w(TAG, "Primary translation failed, trying fallback", e)
                try {
                    val result = translateFallback(text, from, to)
                    cache.put(cacheKey, result)
                    _isAvailable.value = true
                    Result.success(result)
                } catch (e2: Exception) {
                    Log.e(TAG, "All translation backends failed", e2)
                    _isAvailable.value = false
                    Result.failure(e2)
                }
            }
        }
    }

    private fun translatePrimary(text: String, from: String, to: String): String {
        val sid = "$ucid-${counter.getAndIncrement()}-0"
        val langPair = "$from-$to"

        val body = FormBody.Builder()
            .add("sid", sid)
            .add("srv", "android")
            .add("text", text)
            .add("lang", langPair)
            .build()

        val request = Request.Builder()
            .url(PRIMARY_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from primary")

        if (!response.isSuccessful) {
            throw Exception("Primary HTTP ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val code = json.optInt("code", -1)
        if (code != 200) {
            throw Exception("Primary error code: $code")
        }

        val textArray = json.getJSONArray("text")
        return (0 until textArray.length())
            .joinToString(" ") { textArray.getString(it) }
    }

    private fun translateFallback(text: String, from: String, to: String): String {
        val json = JSONObject().apply {
            put("text", text)
            put("source_lang", from)
            put("target_lang", to)
        }

        val mediaType = "application/json".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(FALLBACK_URL)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from fallback")

        if (!response.isSuccessful) {
            throw Exception("Fallback HTTP ${response.code}: $responseBody")
        }

        val responseJson = JSONObject(responseBody)
        return responseJson.getString("translatedText")
    }
}
