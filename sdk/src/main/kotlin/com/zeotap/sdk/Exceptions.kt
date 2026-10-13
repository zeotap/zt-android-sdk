package com.zeotap.sdk

/**
 * Thrown by the [Zeotap] facade when a method is called before [Zeotap.load].
 * Message mirrors the browser SDK: `[SDK] Not initialized. Call load() before <method>().`
 */
public class NotInitializedException internal constructor(method: String) :
    IllegalStateException("[SDK] Not initialized. Call load() before $method().")

/**
 * A call the SDK deliberately dropped. Delivered to the call's [Callback]
 * (never thrown). Messages are byte-identical to the browser SDK's.
 */
public class SdkException internal constructor(message: String) : RuntimeException(message)
