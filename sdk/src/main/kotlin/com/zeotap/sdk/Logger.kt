package com.zeotap.sdk

/** Log sink. Messages keep the browser SDK's `[SDK] …` wording. PII is never logged. */
public interface Logger {
    public fun debug(message: String)
    public fun warn(message: String)
    public fun error(message: String, error: Throwable? = null)
}
