package org.sonora.android.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Default Media Receiver; no custom receiver needs to be published. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_RECEIVER)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    private companion object {
        const val DEFAULT_RECEIVER = "CC1AD845"
    }
}
