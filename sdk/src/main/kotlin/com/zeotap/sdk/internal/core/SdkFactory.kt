package com.zeotap.sdk.internal.core

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.zeotap.sdk.BuildConfig
import com.zeotap.sdk.CmpMode
import com.zeotap.sdk.Configuration
import com.zeotap.sdk.internal.consent.ConsentManager
import com.zeotap.sdk.internal.consent.ConsentPersistence
import com.zeotap.sdk.internal.consent.tcf.IabTcfPrefsReader
import com.zeotap.sdk.internal.consent.tcf.TcfAppsIntegration
import com.zeotap.sdk.internal.context.DefaultContextCollector
import com.zeotap.sdk.internal.context.CachingAdvertisingIdProvider
import com.zeotap.sdk.internal.context.HostFirstAdvertisingIdProvider
import com.zeotap.sdk.internal.context.ManualAdvertisingIdProvider
import com.zeotap.sdk.internal.context.ReflectiveAdvertisingIdProvider
import com.zeotap.sdk.internal.identity.IdentityStore
import com.zeotap.sdk.internal.identity.UserIdentityManager
import com.zeotap.sdk.internal.lifecycle.AppLifecycleTracker
import com.zeotap.sdk.internal.lifecycle.ConnectivityNetworkMonitor
import com.zeotap.sdk.internal.lifecycle.LifecycleEvents
import com.zeotap.sdk.internal.queue.SqliteEventQueue
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.storage.SharedPrefsStorage
import com.zeotap.sdk.internal.transport.Transport
import com.zeotap.sdk.internal.transport.UrlConnectionHttpClient
import com.zeotap.sdk.internal.util.AndroidLogger
import com.zeotap.sdk.internal.util.SecureRandomIdGenerator
import com.zeotap.sdk.internal.util.SystemClock

/** Wires the production collaborators for one [AnalyticsClient] and starts it. */
internal object SdkFactory {
    fun create(appContext: Context, writeKey: String, config: Configuration): AnalyticsClient {
        val logger = config.logger ?: AndroidLogger(config.debug)
        val keys = Keys(config.namespace)
        val storage = config.storage ?: SharedPrefsStorage(appContext, "${config.namespace}_sdk")
        val clock = SystemClock
        val ids = SecureRandomIdGenerator
        val sdk = SdkScope.production { logger.error("[SDK] Uncaught error", it) }

        val identity = IdentityStore(keys, storage, ids)
        val userIdentities = UserIdentityManager(keys, storage, logger)
        val consent = ConsentManager(clock)
        val consentPersistence = if (config.consent.persist) ConsentPersistence(keys, storage, config.consent.expiryDays, clock) else null
        val tcfFactory: ((() -> Unit) -> TcfAppsIntegration)? = if (config.consent.cmp == CmpMode.AUTO) {
            { onDetected ->
                TcfAppsIntegration(IabTcfPrefsReader(appContext), consent, config.consent, sdk.scope, clock, logger, onDetected)
            }
        } else {
            null
        }
        val queue = SqliteEventQueue(appContext, "${config.namespace}_sdk_events.db", config.maxQueueSize)
        val transport = Transport(config.apiHost, writeKey, config.nativeAppId, UrlConnectionHttpClient(), clock, logger)
        // Host-supplied first, the reflective Play Services lookup second — an app
        // that called setAdvertisingId knows something the lookup does not.
        val manualAdvertisingId = ManualAdvertisingIdProvider()
        val contextCollector = DefaultContextCollector(
            appContext,
            config.collectAdvertisingId,
            // Manual first (checked per payload, so a later setAdvertisingId
            // takes effect), then the reflective lookup behind a cache so Play
            // Services is asked at most once per process.
            HostFirstAdvertisingIdProvider(
                manualAdvertisingId,
                CachingAdvertisingIdProvider(ReflectiveAdvertisingIdProvider),
            ),
        )
        val payloadBuilder = PayloadBuilder(BuildConfig.SDK_NAME, BuildConfig.SDK_VERSION, config.integration, contextCollector)
        val lifecycle = (appContext as? Application)?.let { AppLifecycleTracker(it) }
        val network = ConnectivityNetworkMonitor(appContext, logger)
        val lifecycleEvents = if (config.trackApplicationLifecycleEvents) {
            val (versionName, versionCode) = appVersion(appContext)
            LifecycleEvents(keys, storage, versionName, versionCode)
        } else {
            null
        }

        val client = AnalyticsClient(
            writeKey = writeKey,
            config = config,
            sdk = sdk,
            clock = clock,
            ids = ids,
            identity = identity,
            userIdentities = userIdentities,
            consent = consent,
            consentPersistence = consentPersistence,
            tcfFactory = tcfFactory,
            queue = queue,
            transport = transport,
            payloadBuilder = payloadBuilder,
            lifecycle = lifecycle,
            network = network,
            lifecycleEvents = lifecycleEvents,
            manualAdvertisingId = manualAdvertisingId,
            logger = logger,
        )
        client.start()
        return client
    }

    private fun appVersion(context: Context): Pair<String, String> = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        (info.versionName ?: "") to code.toString()
    } catch (_: PackageManager.NameNotFoundException) {
        "" to ""
    }
}
