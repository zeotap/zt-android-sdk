package com.zeotap.sdk.internal.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class AppLifecycleTrackerTest {
    /** An activity mid-rotation: stopped only to be recreated. */
    class RotatingActivity : Activity() {
        override fun isChangingConfigurations(): Boolean = true
    }

    private class RecordingListener : AppLifecycle.Listener {
        var foregrounds = 0
        var backgrounds = 0

        override fun onForeground() {
            foregrounds++
        }

        override fun onBackground() {
            backgrounds++
        }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val listener = RecordingListener()
    private val tracker = AppLifecycleTracker(app)
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).get()
        tracker.start(listener)
    }

    @After
    fun tearDown() {
        tracker.stop()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun started() = tracker.callbacks.onActivityStarted(activity)
    private fun stopped(a: Activity = activity) = tracker.callbacks.onActivityStopped(a)

    @Test
    fun `first activity start does not report foreground`() {
        started()
        advance(1_000)
        assertThat(listener.foregrounds).isEqualTo(0)
        assertThat(listener.backgrounds).isEqualTo(0)
    }

    @Test
    fun `stop reports background once after the debounce`() {
        started()
        stopped()
        advance(699)
        assertThat(listener.backgrounds).isEqualTo(0)
        advance(1)
        assertThat(listener.backgrounds).isEqualTo(1)
        advance(5_000)
        assertThat(listener.backgrounds).isEqualTo(1)
    }

    @Test
    fun `start after background reports foreground once`() {
        started()
        stopped()
        advance(700)
        assertThat(listener.backgrounds).isEqualTo(1)

        started()
        assertThat(listener.foregrounds).isEqualTo(1)
        advance(1_000)
        assertThat(listener.foregrounds).isEqualTo(1)
        assertThat(listener.backgrounds).isEqualTo(1)
    }

    @Test
    fun `quick stop then start is not a background (activity swap)`() {
        started()
        stopped()
        advance(300)
        started()
        advance(2_000)
        assertThat(listener.backgrounds).isEqualTo(0)
        assertThat(listener.foregrounds).isEqualTo(0)
    }

    @Test
    fun `stop during a configuration change never arms the timer`() {
        val rotating = Robolectric.buildActivity(RotatingActivity::class.java).get()
        tracker.callbacks.onActivityStarted(rotating)
        stopped(rotating)
        advance(5_000)
        assertThat(listener.backgrounds).isEqualTo(0)
        // the recreated activity starts; still foreground, so no foreground signal either
        started()
        assertThat(listener.foregrounds).isEqualTo(0)
    }

    @Test
    fun `overlapping activities keep the process in the foreground`() {
        val second = Robolectric.buildActivity(Activity::class.java).get()
        started()
        tracker.callbacks.onActivityStarted(second)
        stopped()
        advance(2_000)
        assertThat(listener.backgrounds).isEqualTo(0)
        stopped(second)
        advance(700)
        assertThat(listener.backgrounds).isEqualTo(1)
    }

    @Test
    fun `full cycle background foreground background`() {
        started()
        stopped()
        advance(700)
        started()
        stopped()
        advance(700)
        assertThat(listener.backgrounds).isEqualTo(2)
        assertThat(listener.foregrounds).isEqualTo(1)
    }

    @Test
    fun `stop cancels a pending background`() {
        started()
        stopped()
        tracker.stop()
        advance(2_000)
        assertThat(listener.backgrounds).isEqualTo(0)
    }

    @Test
    fun `stop unregisters the callbacks`() {
        tracker.stop()
        val controller = Robolectric.buildActivity(Activity::class.java).create().start()
        controller.stop()
        advance(2_000)
        assertThat(listener.backgrounds).isEqualTo(0)
        controller.destroy()
    }
}
