package com.zeotap.sdk.internal.context

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdvertisingIdProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `returns null when Play Services ads identifier is absent`() {
        // play-services-ads-identifier is compileOnly: it is NOT on the unit-test runtime classpath.
        assertThat(runCatching { Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient") }.isFailure).isTrue()
        val context: Context = ApplicationProvider.getApplicationContext()
        assertThat(ReflectiveAdvertisingIdProvider.fetch(context)).isNull()
    }

    // ── Host-supplied id (increment 247 follow-up) ───────────────────────────
    // The precedence below is the part worth pinning: an app that called
    // setAdvertisingId knows something the reflective lookup does not (a
    // mediation SDK's value, or a deliberate opt-out), so overriding it with a
    // Play Services answer would be the SDK second-guessing the integrator.

    /** Stands in for the reflective provider, which needs Play Services. */
    private class Fixed(private val info: AdvertisingIdProvider.Info?) : AdvertisingIdProvider {
        var fetched = 0
            private set

        override fun fetch(context: Context): AdvertisingIdProvider.Info? {
            fetched++
            return info
        }
    }

    @Test
    fun `an unset manual provider yields nothing`() {
        val manual = ManualAdvertisingIdProvider()
        assertThat(manual.isSet()).isFalse()
        assertThat(manual.fetch(context)).isNull()
    }

    @Test
    fun `a set manual provider yields the host's value`() {
        val manual = ManualAdvertisingIdProvider()
        manual.set("gaid-1", limitAdTracking = false)
        assertThat(manual.isSet()).isTrue()
        assertThat(manual.fetch(context)).isEqualTo(AdvertisingIdProvider.Info("gaid-1", false))
    }

    @Test
    fun `a null id is still SET - it withdraws a previous value`() {
        // Withdrawing must be distinguishable from never having supplied one, or
        // a host could not revoke an id after the user opted out.
        val manual = ManualAdvertisingIdProvider()
        manual.set("gaid-1", limitAdTracking = false)
        manual.set(null, limitAdTracking = true)
        assertThat(manual.isSet()).isTrue()
        assertThat(manual.fetch(context)).isEqualTo(AdvertisingIdProvider.Info(null, true))
    }

    @Test
    fun `the host's value wins over the SDK's own lookup`() {
        val delegate = Fixed(AdvertisingIdProvider.Info("from-play-services", false))
        val manual = ManualAdvertisingIdProvider()
        val provider = HostFirstAdvertisingIdProvider(manual, delegate)

        manual.set("from-host", limitAdTracking = false)
        assertThat(provider.fetch(context)?.id).isEqualTo("from-host")
        // Not merely overridden — the lookup is not performed at all, which
        // matters because it is a blocking IPC call.
        assertThat(delegate.fetched).isEqualTo(0)
    }

    @Test
    fun `without a host value the SDK falls back to its own lookup`() {
        val delegate = Fixed(AdvertisingIdProvider.Info("from-play-services", false))
        val provider = HostFirstAdvertisingIdProvider(ManualAdvertisingIdProvider(), delegate)

        assertThat(provider.fetch(context)?.id).isEqualTo("from-play-services")
        assertThat(delegate.fetched).isEqualTo(1)
    }

    @Test
    fun `the caching provider asks the delegate once, including for a null answer`() {
        // The Play Services lookup is a blocking IPC, so it must not run per
        // event — but "no id available" is a legitimate steady state, and
        // retrying to rediscover it would cost more than it could return.
        val delegate = Fixed(null)
        val caching = CachingAdvertisingIdProvider(delegate)
        assertThat(caching.fetch(context)).isNull()
        assertThat(caching.fetch(context)).isNull()
        assertThat(delegate.fetched).isEqualTo(1)
    }

    @Test
    fun `the caching provider returns the same answer every time`() {
        val delegate = Fixed(AdvertisingIdProvider.Info("gaid-1", false))
        val caching = CachingAdvertisingIdProvider(delegate)
        assertThat(caching.fetch(context)?.id).isEqualTo("gaid-1")
        assertThat(caching.fetch(context)?.id).isEqualTo("gaid-1")
        assertThat(delegate.fetched).isEqualTo(1)
    }

    @Test
    fun `a host value of null wins too, and suppresses the lookup`() {
        // An app that withdrew the id must not have Play Services quietly put it
        // back on the next payload.
        val delegate = Fixed(AdvertisingIdProvider.Info("from-play-services", false))
        val manual = ManualAdvertisingIdProvider()
        val provider = HostFirstAdvertisingIdProvider(manual, delegate)

        manual.set(null, limitAdTracking = true)
        assertThat(provider.fetch(context)?.id).isNull()
        assertThat(delegate.fetched).isEqualTo(0)
    }
}
