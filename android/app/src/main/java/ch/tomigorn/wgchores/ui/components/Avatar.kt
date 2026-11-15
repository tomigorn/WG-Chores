package ch.tomigorn.wgchores.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ch.tomigorn.wgchores.api.ApiClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Profile pictures: https only, at most 512 KiB, decoded down to about 256 px, kept in memory. */
object Pictures {
    private const val MAX_BYTES = 512L * 1024
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    // redirects followed, but never from https to http
    private val http by lazy { ApiClient.defaultHttp.newBuilder().followRedirects(true).followSslRedirects(false).build() }

    fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        if (!url.startsWith("https://") || url in failed) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val src = r.body.source()
                    if (src.request(MAX_BYTES + 1)) return@use null
                    val bytes = src.buffer.readByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }
            }.getOrNull().also { if (it != null) cache[url] = it else failed += url }
        }
    }
}

/** The picture when there is one that loads, else the initials. */
@Composable
fun Avatar(name: String, pictureUrl: String?, size: Int = 36) {
    var bitmap by remember(pictureUrl) { mutableStateOf(pictureUrl?.let { Pictures.cached(it) }) }
    LaunchedEffect(pictureUrl) {
        if (pictureUrl != null && bitmap == null) bitmap = Pictures.load(pictureUrl)
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(CircleShape))
    } else {
        Initials(name, size)
    }
}
