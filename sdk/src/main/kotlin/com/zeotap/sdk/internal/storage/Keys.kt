package com.zeotap.sdk.internal.storage

/**
 * Storage key names — identical to the browser SDK's so a hybrid app's
 * WebView and native layer can share identity/consent (sdk/conformance
 * `storage-keys` vectors). `appVersion`/`appBuild` are mobile-only.
 */
internal class Keys(namespace: String) {
    val anonymousId: String = "${namespace}_anonymous_id"
    val userId: String = "${namespace}_user_id"
    val userIdentities: String = "${namespace}_user_identities"
    val consent: String = "${namespace}_consent"
    val consentTcf: String = "${namespace}_consent_tcf"
    val appVersion: String = "${namespace}_app_version"
    val appBuild: String = "${namespace}_app_build"
}
