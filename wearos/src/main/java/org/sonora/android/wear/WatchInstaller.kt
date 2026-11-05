package org.sonora.android.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import org.sonora.android.wear.WearProtocol as P

/**
 * The watch's side of "update the watch app" in the phone's Settings: it says which build it runs,
 * takes a new APK the phone streams over a channel, and installs it with the system installer
 * (which asks the wearer to confirm, as any sideloaded update does).
 */
object WatchInstaller {
    private const val TAG = "LFlyWear"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Publishes this build's version for the phone to compare with the one it carries. */
    fun publishVersion(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            val request = PutDataMapRequest.create(P.PATH_WATCH_VERSION).apply {
                dataMap.putLong(P.KEY_VERSION_CODE, code)
                dataMap.putString(P.KEY_VERSION_NAME, info.versionName.orEmpty())
                // Always a change, so asking twice still answers.
                dataMap.putLong(P.KEY_POSITION_AT, System.currentTimeMillis())
            }
            runCatching { Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()).await() }
                .onFailure { Log.w(TAG, "Could not publish the watch version: ${it.message}") }
        }
    }

    /** The phone opened the APK channel: write it to a file, then install it once it is complete. */
    fun receive(context: Context, channel: ChannelClient.Channel) {
        val app = context.applicationContext
        val file = apkFile(app)
        file.delete()
        Wearable.getChannelClient(app).receiveFile(channel, android.net.Uri.fromFile(file), false)
    }

    /** Play services finished writing the file [receive] asked for. */
    fun onReceived(context: Context, closeReason: Int) {
        val app = context.applicationContext
        val file = apkFile(app)
        if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL || !file.exists() || file.length() == 0L) {
            Log.w(TAG, "Watch update did not arrive whole (reason $closeReason)")
            return
        }
        scope.launch { install(app, file) }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("lfly-watch.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(status.intentSender)
        }
        Log.i(TAG, "Watch update handed to the installer (${apk.length()} bytes)")
    }

    private fun apkFile(context: Context) = File(context.cacheDir, "lfly-watch-update.apk")
}

/** The installer's answer: when it needs the wearer to confirm, it hands back a screen to show. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("LFlyWear", "Watch app updated")
            else -> Log.w("LFlyWear", "Watch update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
