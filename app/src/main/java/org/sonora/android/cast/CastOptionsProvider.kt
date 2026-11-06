package org.sonora.android.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Default Media Receiver; no custom receiver needs to be published. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_RECEIVER)
            // LFly's own notification and lock screen already show what plays: no second set from Cast.
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setNotificationOptions(null)
                    .setMediaSessionEnabled(false)
                    .build()
            )
            // Disconnecting closes the player on the TV too, instead of leaving it on the last song.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val DEFAULT_RECEIVER = "CC1AD845"
    }
}
