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
import androidx.compose.ui.unit.sp
import ch.tomigorn.wgchores.api.ApiClient
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Profile pictures: https only, at most 512 KiB, decoded down to about 256 px, kept in memory. */
object Pictures {
    sealed interface Failure {
        data class Status(val code: Int) : Failure
        data object Undecodable : Failure
        data object TooBig : Failure
        data object NotHttps : Failure
        data object Network : Failure
    }

    /** Missing, refused, too big or not a picture: not asked again. A network or server hiccup is retried next time. */
    fun permanent(f: Failure): Boolean = when (f) {
        is Failure.Status -> f.code in 400..499 && f.code != 408 && f.code != 429
        Failure.Network -> false
        else -> true
    }

    private const val MAX_BYTES = 512L * 1024
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()
    // redirects followed, but never from https to http
    private val http by lazy { ApiClient.defaultHttp.newBuilder().followRedirects(true).followSslRedirects(false).build() }

    private class Failed(val f: Failure) : Exception()

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            try {
                if (!url.startsWith("https://")) throw Failed(Failure.NotHttps)
                fetch(url).also { cache.put(url, it) }
            } catch (e: Failed) {
                if (permanent(e.f)) failed += url
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun fetch(url: String): ImageBitmap {
        val r = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: java.io.IOException) {
            throw Failed(Failure.Network)
        }
        return r.use {
            if (!it.isSuccessful) throw Failed(Failure.Status(it.code))
            val bytes = try {
                val src = it.body.source()
                if (src.request(MAX_BYTES + 1)) throw Failed(Failure.TooBig)
                src.buffer.readByteArray()
            } catch (e: java.io.IOException) {
                throw Failed(Failure.Network)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Failed(Failure.Undecodable)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                ?: throw Failed(Failure.Undecodable)
        }
    }
}

/** The picture when there is one that loads, else the initials; [size] in sp so it grows with the font. */
@Composable
fun Avatar(name: String, pictureUrl: String?, size: Int = 36) {
    val dp = with(androidx.compose.ui.platform.LocalDensity.current) { size.sp.toDp() }
    var bitmap by remember(pictureUrl) { mutableStateOf(pictureUrl?.let { Pictures.cached(it) }) }
    LaunchedEffect(pictureUrl) {
        if (pictureUrl != null && bitmap == null) bitmap = Pictures.load(pictureUrl)
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(dp).clip(CircleShape))
    } else {
        Initials(name, dp.value.toInt())
    }
}
