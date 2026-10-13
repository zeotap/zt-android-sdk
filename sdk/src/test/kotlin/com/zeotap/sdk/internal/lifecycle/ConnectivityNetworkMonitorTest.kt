package com.zeotap.sdk.internal.lifecycle

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class ConnectivityNetworkMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Test
    fun `start registers one callback and onAvailable is forwarded`() {
        val monitor = ConnectivityNetworkMonitor(context)
        var available = 0
        monitor.start { available++ }

        val callbacks = shadowOf(cm).networkCallbacks
        assertThat(callbacks).hasSize(1)
        callbacks.single().onAvailable(ShadowNetwork.newInstance(7))
        callbacks.single().onAvailable(ShadowNetwork.newInstance(8))
        assertThat(available).isEqualTo(2)

        monitor.stop()
        assertThat(shadowOf(cm).networkCallbacks).isEmpty()
    }

    @Test
    fun `start twice registers once and stop twice is safe`() {
        val monitor = ConnectivityNetworkMonitor(context)
        monitor.start {}
        monitor.start {}
        assertThat(shadowOf(cm).networkCallbacks).hasSize(1)
        monitor.stop()
        monitor.stop()
        assertThat(shadowOf(cm).networkCallbacks).isEmpty()
    }

    @Test
    fun `stop without start is a no-op`() {
        ConnectivityNetworkMonitor(context).stop()
        assertThat(shadowOf(cm).networkCallbacks).isEmpty()
    }
}
