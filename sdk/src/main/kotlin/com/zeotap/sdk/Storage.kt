package com.zeotap.sdk

/**
 * Key-value store for identifiers and consent (`{ns}_anonymous_id`,
 * `{ns}_user_id`, `{ns}_user_identities`, `{ns}_consent`, `{ns}_consent_tcf`,
 * `{ns}_app_version`, `{ns}_app_build`). Key names and JSON record shapes are
 * identical to the browser SDK's localStorage layout.
 *
 * The default implementation is plain SharedPreferences (the stored values are
 * already non-raw: a UUID, hashes, consent booleans). Hosts with stricter
 * requirements inject their own — e.g. an encrypted store — via
 * [Configuration.Builder.storage]. Implementations must be thread-safe; the
 * SDK calls them from its own single thread.
 */
public interface Storage {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}
