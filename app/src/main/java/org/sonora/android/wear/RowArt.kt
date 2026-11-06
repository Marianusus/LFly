package org.sonora.android.wear

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import java.io.ByteArrayOutputStream
import org.sonora.android.wear.WearProtocol as P

/**
 * The small covers next to the watch's list rows. Each cover is made once (Coil already has most
 * of them on disk) and kept, so opening the same list again costs the watch nothing new to fetch.
 */
internal object RowArt {
    private val cache = LruCache<String, Asset>(160)

    suspend fun of(context: Context, url: String?): Asset? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        val app = context.applicationContext
        val result = runCatching {
            app.imageLoader.execute(ImageRequest.Builder(app).data(url).size(P.ROW_ART_PX).allowHardware(false).build())
        }.getOrNull() as? SuccessResult ?: return null
        val bitmap = result.drawable.toBitmap(P.ROW_ART_PX, P.ROW_ART_PX, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        return Asset.createFromBytes(bytes).also { cache.put(url, it) }
    }

    /** Puts the covers of the first [P.ROW_ART_MAX] [urls] on [map], keyed by their position. */
    suspend fun putAll(context: Context, map: DataMap, urls: List<String?>) {
        urls.take(P.ROW_ART_MAX).forEachIndexed { position, url ->
            of(context, url)?.let { map.putAsset(P.ROW_ART_PREFIX + position, it) }
        }
    }
}
