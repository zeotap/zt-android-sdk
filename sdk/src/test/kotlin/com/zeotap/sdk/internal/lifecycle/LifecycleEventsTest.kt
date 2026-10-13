package com.zeotap.sdk.internal.lifecycle

import com.google.common.truth.Truth.assertThat
import com.zeotap.sdk.internal.storage.Keys
import com.zeotap.sdk.internal.testing.InMemoryStorage
import org.junit.Test

class LifecycleEventsTest {
    private val keys = Keys("zeotap")
    private val storage = InMemoryStorage()

    private fun events(version: String = "1.2.3", build: String = "42") = LifecycleEvents(keys, storage, version, build)

    @Test
    fun `first launch emits Installed then Opened and persists version and build`() {
        val launch = events().onLaunch()

        assertThat(launch.map { it.name }).containsExactly("Application Installed", "Application Opened").inOrder()
        assertThat(launch[0].properties).containsExactly("version", "1.2.3", "build", "42").inOrder()
        assertThat(launch[1].properties)
            .containsExactly("from_background", false, "version", "1.2.3", "build", "42").inOrder()
        assertThat(storage.get("zeotap_app_version")).isEqualTo("1.2.3")
        assertThat(storage.get("zeotap_app_build")).isEqualTo("42")
    }

    @Test
    fun `relaunch on the same version emits only Opened`() {
        events().onLaunch()
        val launch = events().onLaunch()
        assertThat(launch.map { it.name }).containsExactly("Application Opened")
        assertThat(launch.single().properties["from_background"]).isEqualTo(false)
    }

    @Test
    fun `version change emits Updated with previous values then Opened`() {
        events("1.0.0", "1").onLaunch()
        val launch = events("1.1.0", "2").onLaunch()

        assertThat(launch.map { it.name }).containsExactly("Application Updated", "Application Opened").inOrder()
        assertThat(launch[0].properties).containsExactly(
            "version", "1.1.0",
            "build", "2",
            "previous_version", "1.0.0",
            "previous_build", "1",
        ).inOrder()
        assertThat(storage.get(keys.appVersion)).isEqualTo("1.1.0")
        assertThat(storage.get(keys.appBuild)).isEqualTo("2")
    }

    @Test
    fun `build-only change is an update too`() {
        events("1.0.0", "1").onLaunch()
        val launch = events("1.0.0", "2").onLaunch()
        assertThat(launch.first().name).isEqualTo("Application Updated")
        assertThat(launch.first().properties["previous_build"]).isEqualTo("1")
    }

    @Test
    fun `foreground emits Opened from background`() {
        val event = events().onForeground()
        assertThat(event.name).isEqualTo("Application Opened")
        assertThat(event.properties).containsExactly("from_background", true, "version", "1.2.3", "build", "42").inOrder()
    }

    @Test
    fun `background emits Backgrounded with no properties`() {
        val event = events().onBackground()
        assertThat(event.name).isEqualTo("Application Backgrounded")
        assertThat(event.properties).isEmpty()
    }

    @Test
    fun `keys are namespaced`() {
        val other = Keys("acme")
        LifecycleEvents(other, storage, "9", "9").onLaunch()
        assertThat(storage.get("acme_app_version")).isEqualTo("9")
        assertThat(storage.get("acme_app_build")).isEqualTo("9")
        assertThat(storage.get("zeotap_app_version")).isNull()
    }
}
