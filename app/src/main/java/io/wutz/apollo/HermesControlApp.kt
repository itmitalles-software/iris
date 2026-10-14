package io.wutz.apollo

import android.app.Application
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.SessionListCacheStore
import io.wutz.apollo.data.remote.CertificateImageInterceptor
import io.wutz.apollo.data.remote.ClientCertificates
import io.wutz.apollo.data.remote.NetworkMonitor
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.ServerTrust
import io.wutz.apollo.notification.TurnCorrelationTracker
import io.wutz.apollo.ui.analytics.AnalyticsPreloader
import io.wutz.apollo.ui.chat.cleanStaleCameraPhotos
import io.wutz.apollo.ui.chat.cleanStalePastedImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HermesControlApp :
    Application(),
    SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        val startupTimeMs = System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            cleanStalePastedImages(cacheDir, startupTimeMs)
            cleanStaleCameraPhotos(cacheDir, startupTimeMs)
        }
        OkHttpProvider.initReleaseMetadataCache(cacheDir)
        ServerTrust.initialize(this)
        ClientCertificates.initialize(this)
        AuthManager.init(this)
        NetworkMonitor.init(this)
        SessionListCacheStore.init(this)
        // Reply-notification turn boundaries survive process death (armed before
        // each mobile prompt, consumed by the matching completion).
        TurnCorrelationTracker.attach(this)
        appScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            // Issue #537 follow-up (A): preload analytics in the background after launch
            // so the tab renders instantly when opened (the usage endpoint is slow on a
            // cold backend). Fire-and-forget; never blocks UI startup.
            AnalyticsPreloader.preload(this@HermesControlApp)
        }
    }

    /**
     * Debug builds log main-thread disk and network access, leaked closeables and leaked
     * receiver/service registrations (logcat tag `StrictMode`) so regressions show up while
     * developing. Log only: known startup reads (trust and certificate settings) are still flagged.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(CertificateImageInterceptor())
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { OkHttpProvider.base },
                    ),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }.crossfade(true)
            .build()
}
