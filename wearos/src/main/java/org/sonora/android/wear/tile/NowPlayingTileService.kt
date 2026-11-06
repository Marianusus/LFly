package org.sonora.android.wear.tile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_CROP
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.sonora.android.wear.R
import org.sonora.android.wear.WearActivity
import java.nio.ByteBuffer
import org.sonora.android.wear.WearProtocol as P

/**
 * The now-playing tile: cover, title, artist and previous / play-pause / next, one swipe from the
 * watch face. It reads the phone's last [P.PATH_NOW_PLAYING] DataItem on each request and is redrawn
 * only when that changes ([refresh] from NowPlayingListener), so it never polls.
 *
 * Buttons are LoadActions: a tap comes back here as a tile request carrying the clicked id, which
 * becomes a command to the phone. Ids carry the render's stamp, so a request that repeats the last
 * clicked id (a redraw, not a tap) cannot send the command twice.
 */
class NowPlayingTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.IO) {
            val map = nowPlaying()
            // Nothing from the phone yet: ask it, its answer redraws the tile.
            if (map == null) send(P.CMD_SYNC)
            val clicked = requestParams.currentState.lastClickableId
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val command = clicked.substringBefore('@', "").takeIf { it in COMMANDS && clicked != prefs.getString(KEY_HANDLED, "") }
            if (command != null) {
                prefs.edit().putString(KEY_HANDLED, clicked).apply()
                send(command)
            }
            val track = map?.takeIf { it.getBoolean(P.KEY_HAS_TRACK) }
            // Play/pause answers the tap at once; the phone's write confirms it.
            val playing = (track?.getBoolean(P.KEY_PLAYING) ?: false) != (command == P.CMD_TOGGLE)
            val device = requestParams.deviceConfiguration
            val width = (device.screenWidthDp.takeIf { it > 0 } ?: 192) * 0.76f
            val content = if (track == null) idleLayout(device, width) else playerLayout(track, playing, width)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(resourcesVersion(track))
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(Timeline.fromLayoutElement(
                    Box.Builder().setWidth(expand()).setHeight(expand())
                        .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                        .addContent(content).build()
                ))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.IO) {
            val resources = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
            ICONS.forEach { (id, res) ->
                resources.addIdToImageMapping(id, ResourceBuilders.ImageResource.Builder()
                    .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
                    .build())
            }
            // Only the cover of the track the tile asked for: a version caches what it is given.
            val track = nowPlaying()?.takeIf { it.getBoolean(P.KEY_HAS_TRACK) }
            if (track != null && resourcesVersion(track) == requestParams.version) {
                cover(track)?.let { resources.addIdToImageMapping(ID_COVER, it) }
            }
            resources.build()
        }

    private fun playerLayout(track: DataMap, playing: Boolean, width: Float): LayoutElement {
        val stamp = System.currentTimeMillis().toString(36)
        val art = if (track.getAsset(P.KEY_ART) != null) {
            Image.Builder().setResourceId(ID_COVER).setWidth(dp(COVER_DP)).setHeight(dp(COVER_DP))
                .setContentScaleMode(CONTENT_SCALE_MODE_CROP)
                .setModifiers(Modifiers.Builder().setBackground(rounded(TRANSPARENT)).build())
                .build()
        } else {
            Box.Builder().setWidth(dp(COVER_DP)).setHeight(dp(COVER_DP))
                .setModifiers(Modifiers.Builder().setBackground(rounded(SURFACE)).build())
                .addContent(logo(20f)).build()
        }
        val header = Column.Builder().setWidth(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(Modifiers.Builder().setClickable(openApp()).build())
            .addContent(art)
            .addContent(gap(4f))
            .addContent(text(track.getString(P.KEY_TITLE).orEmpty(), Typography.TYPOGRAPHY_BUTTON, WHITE))
            .addContent(text(track.getString(P.KEY_ARTIST).orEmpty(), Typography.TYPOGRAPHY_CAPTION2, DIM))
            .build()
        val side = ButtonColors(SURFACE, ACCENT)
        val controls = Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .addContent(button(P.CMD_PREVIOUS, stamp, ID_PREV, R.string.tile_previous, 40f, side))
            .addContent(gap(6f))
            .addContent(
                button(
                    P.CMD_TOGGLE, stamp, if (playing) ID_PAUSE else ID_PLAY,
                    if (playing) R.string.tile_pause else R.string.tile_play, 52f, ButtonColors(WHITE, BLACK)
                )
            )
            .addContent(gap(6f))
            .addContent(button(P.CMD_NEXT, stamp, ID_NEXT, R.string.tile_next, 40f, side))
            .build()
        return Column.Builder().setWidth(dp(width)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(header).addContent(gap(6f)).addContent(controls).build()
    }

    /** No track, or no phone heard yet: the app's name and a way in. */
    private fun idleLayout(device: DeviceParameters, width: Float): LayoutElement =
        Column.Builder().setWidth(dp(width)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .addContent(logo(28f))
            .addContent(gap(4f))
            .addContent(text(getString(R.string.app_name), Typography.TYPOGRAPHY_TITLE2, WHITE))
            .addContent(text(getString(R.string.tile_nothing_playing), Typography.TYPOGRAPHY_CAPTION2, DIM))
            .addContent(gap(10f))
            .addContent(
                CompactChip.Builder(this, getString(R.string.tile_open), openApp(), device)
                    .setChipColors(ChipColors(ACCENT, WHITE)).build()
            )
            .build()

    private fun button(command: String, stamp: String, icon: String, label: Int, size: Float, colors: ButtonColors) =
        Button.Builder(this, Clickable.Builder().setId("$command@$stamp").setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
            .setIconContent(icon, dp(size / 2))
            .setSize(size)
            .setButtonColors(colors)
            .setContentDescription(getString(label))
            .build()

    private fun openApp() = Clickable.Builder().setId(ID_OPEN).setOnClick(
        ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearActivity::class.java.name).build()
        ).build()
    ).build()

    /** ELLIPSIZE_END, not its replacement: that one needs a newer tile renderer than Wear OS 3 has. */
    @Suppress("DEPRECATION")
    private fun text(value: String, typography: Int, color: Int) =
        Text.Builder(this, value).setTypography(typography).setColor(argb(color))
            .setMaxLines(1).setOverflow(TEXT_OVERFLOW_ELLIPSIZE_END).build()

    private fun logo(size: Float) = Image.Builder().setResourceId(ID_LOGO).setWidth(dp(size)).setHeight(dp(size))
        .setColorFilter(ColorFilter.Builder().setTint(argb(ACCENT)).build()).build()

    private fun gap(size: Float) = Spacer.Builder().setWidth(dp(size)).setHeight(dp(size)).build()

    private fun rounded(color: Int) =
        Background.Builder().setColor(argb(color)).setCorner(Corner.Builder().setRadius(dp(8f)).build()).build()

    /** The phone's last now-playing write, null if it never wrote one. */
    private suspend fun nowPlaying(): DataMap? = runCatching {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(P.PATH_NOW_PLAYING).build()
        val items = Wearable.getDataClient(this).getDataItems(uri).await()
        try {
            items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
        } finally {
            items.release()
        }
    }.getOrNull()

    /** The cover as raw RGB_565, which every tile renderer can draw (compressed JPEG is newer). */
    private suspend fun cover(track: DataMap): ResourceBuilders.ImageResource? {
        val asset = track.getAsset(P.KEY_ART) ?: return null
        val decoded = runCatching {
            Wearable.getDataClient(this).getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val bitmap = Bitmap.createScaledBitmap(decoded, COVER_PX, COVER_PX, true).copy(Bitmap.Config.RGB_565, false)
        val bytes = ByteBuffer.allocate(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it) }.array()
        return ResourceBuilders.ImageResource.Builder().setInlineResource(
            ResourceBuilders.InlineImageResource.Builder().setData(bytes).setWidthPx(COVER_PX).setHeightPx(COVER_PX)
                .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565).build()
        ).build()
    }

    /** Sent on a scope of its own, so the tile is drawn without waiting for the phone. */
    private fun send(command: String) {
        val context = applicationContext
        scope.launch {
            runCatching {
                val info = Wearable.getCapabilityClient(context)
                    .getCapability(P.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
                val node = info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull() ?: return@launch
                Wearable.getMessageClient(context).sendMessage(node.id, "${P.PATH_COMMAND}/$command", null).await()
            }
        }
    }

    companion object {
        private const val ID_COVER = "cover"
        private const val ID_LOGO = "logo"
        private const val ID_PLAY = "play"
        private const val ID_PAUSE = "pause"
        private const val ID_NEXT = "next"
        private const val ID_PREV = "prev"
        private const val ID_OPEN = "open"
        private val ICONS = mapOf(
            ID_LOGO to R.drawable.ic_notification,
            ID_PLAY to R.drawable.tile_ic_play,
            ID_PAUSE to R.drawable.tile_ic_pause,
            ID_NEXT to R.drawable.tile_ic_next,
            ID_PREV to R.drawable.tile_ic_prev
        )
        private val COMMANDS = setOf(P.CMD_PREVIOUS, P.CMD_TOGGLE, P.CMD_NEXT)

        private const val COVER_DP = 36f
        private const val COVER_PX = 96
        private const val ACCENT = 0xFF4F8DFF.toInt()
        private const val SURFACE = 0xFF262626.toInt()
        private const val DIM = 0xFF9A9AA0.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val TRANSPARENT = 0x00000000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The last clicked id acted on, kept on disk: a later redraw may carry it again, even after
         * the watch killed this process, and must not skip a second song.
         */
        private const val PREFS = "lfly_tile"
        private const val KEY_HANDLED = "handled"

        /** Per track, and per cover once it arrives, so the tile fetches each cover once. */
        private fun resourcesVersion(track: DataMap?): String = when {
            track == null -> "none"
            track.getAsset(P.KEY_ART) == null -> "${track.getString(P.KEY_TRACK_ID)}#noart"
            else -> track.getString(P.KEY_TRACK_ID).orEmpty()
        }

        /** Redraw the tile: the phone wrote a new now-playing. */
        fun refresh(context: Context) = TileService.getUpdater(context).requestUpdate(NowPlayingTileService::class.java)
    }
}
