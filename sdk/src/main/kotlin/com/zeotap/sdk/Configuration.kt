package com.zeotap.sdk

/**
 * SDK configuration — the browser SDK's `LoadOptions` with the browser-only
 * keys removed (storage/cookie/firstPartyCookies/credentials/autoPage) and the
 * mobile-only keys added. Names and units are kept identical to the web so the
 * React Native bridge passes one options object to both platforms.
 *
 * `apiHost` is REQUIRED: the web default `""` means "same origin", which has no
 * meaning on a device.
 */
public class Configuration private constructor(
    /** Ingest base URL, e.g. `https://events.zeotap.com` or a first-party proxy. */
    public val apiHost: String,
    /** Storage-key namespace (`{namespace}_anonymous_id`, …). Default `zeotap`. */
    public val namespace: String,
    /** Flush queued events after this many accumulate. Default 10. */
    public val flushAt: Int,
    /** Flush queued events at this interval in ms. Default 5000. */
    public val flushIntervalMs: Long,
    /** Debug logging. Default false. */
    public val debug: Boolean,
    /** Snowflake Native App install id → `X-Native-App-ID`. Default empty. */
    public val nativeAppId: String,
    public val consent: ConsentOptions,
    /** Wrapping integration (e.g. the React Native bridge) → `context.library.integration`. */
    public val integration: IntegrationMeta?,
    // ---- mobile additions ----
    /** Emit Application Installed / Updated / Opened / Backgrounded. Default false. */
    public val trackApplicationLifecycleEvents: Boolean,
    /** Collect the Google Advertising ID (requires play-services-ads-identifier on the host). Default false. */
    public val collectAdvertisingId: Boolean,
    /** Durable queue capacity; the OLDEST event is dropped beyond it. Default 500. */
    public val maxQueueSize: Int,
    /** Flush when the app moves to the background. Default true. */
    public val flushOnBackground: Boolean,
    /** Override the log sink. Default: android.util.Log with tag `ZeotapSDK`. */
    public val logger: Logger?,
    /** Override the identity/consent key-value store (e.g. an encrypted one). Default: SharedPreferences. */
    public val storage: Storage?,
) {
    public class Builder(private val apiHost: String) {
        private var namespace: String = DEFAULT_NAMESPACE
        private var flushAt: Int = DEFAULT_FLUSH_AT
        private var flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS
        private var debug: Boolean = false
        private var nativeAppId: String = ""
        private var consent: ConsentOptions = ConsentOptions.default()
        private var integration: IntegrationMeta? = null
        private var trackApplicationLifecycleEvents: Boolean = false
        private var collectAdvertisingId: Boolean = false
        private var maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE
        private var flushOnBackground: Boolean = true
        private var logger: Logger? = null
        private var storage: Storage? = null

        public fun namespace(value: String): Builder = apply {
            require(NAMESPACE_PATTERN.matches(value)) { "[SDK] namespace must match ${NAMESPACE_PATTERN.pattern}" }
            namespace = value
        }
        public fun flushAt(value: Int): Builder = apply {
            require(value >= 1) { "[SDK] flushAt must be >= 1" }
            flushAt = value
        }
        public fun flushIntervalMs(value: Long): Builder = apply {
            require(value > 0) { "[SDK] flushIntervalMs must be > 0" }
            flushIntervalMs = value
        }
        public fun debug(value: Boolean): Builder = apply { debug = value }
        public fun nativeAppId(value: String): Builder = apply { nativeAppId = value }
        public fun consent(value: ConsentOptions): Builder = apply { consent = value }
        /** Both fields are required or the integration is dropped (web parity). */
        public fun integration(name: String, version: String): Builder = apply {
            integration = if (name.isNotEmpty() && version.isNotEmpty()) IntegrationMeta(name, version) else null
        }
        public fun trackApplicationLifecycleEvents(value: Boolean): Builder = apply { trackApplicationLifecycleEvents = value }
        public fun collectAdvertisingId(value: Boolean): Builder = apply { collectAdvertisingId = value }
        public fun maxQueueSize(value: Int): Builder = apply {
            require(value >= 1) { "[SDK] maxQueueSize must be >= 1" }
            maxQueueSize = value
        }
        public fun flushOnBackground(value: Boolean): Builder = apply { flushOnBackground = value }
        public fun logger(value: Logger?): Builder = apply { logger = value }
        public fun storage(value: Storage?): Builder = apply { storage = value }

        public fun build(): Configuration {
            require(apiHost.isNotBlank()) { "[SDK] apiHost is required" }
            require(apiHost.startsWith("http://") || apiHost.startsWith("https://")) {
                "[SDK] apiHost must be an http(s) URL"
            }
            return Configuration(
                apiHost = apiHost.trimEnd('/'),
                namespace = namespace,
                flushAt = flushAt,
                flushIntervalMs = flushIntervalMs,
                debug = debug,
                nativeAppId = nativeAppId,
                consent = consent,
                integration = integration,
                trackApplicationLifecycleEvents = trackApplicationLifecycleEvents,
                collectAdvertisingId = collectAdvertisingId,
                maxQueueSize = maxQueueSize,
                flushOnBackground = flushOnBackground,
                logger = logger,
                storage = storage,
            )
        }
    }

    public companion object {
        public const val DEFAULT_NAMESPACE: String = "zeotap"
        public const val DEFAULT_FLUSH_AT: Int = 10
        public const val DEFAULT_FLUSH_INTERVAL_MS: Long = 5_000L
        public const val DEFAULT_MAX_QUEUE_SIZE: Int = 500
        private val NAMESPACE_PATTERN = Regex("^[a-z0-9_-]{1,32}$")
    }
}
