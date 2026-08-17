package com.unsupportedpastels.hermesandroid.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DictationProviderPolicyTest {
    @Test
    fun automaticPrefersServerForProviderFidelity() {
        val order = DictationProviderPolicy.orderedProviders(
            DictationProviderPreference.Automatic,
            serverAvailable = true,
            onDeviceSupported = true,
        )
        assertEquals(listOf(DictationProvider.Server, DictationProvider.OnDevice), order)
    }

    @Test
    fun onDevicePreferenceLeadsWithOnDeviceButKeepsServerFallback() {
        val order = DictationProviderPolicy.orderedProviders(
            DictationProviderPreference.OnDevice,
            serverAvailable = true,
            onDeviceSupported = true,
        )
        assertEquals(listOf(DictationProvider.OnDevice, DictationProvider.Server), order)
    }

    @Test
    fun serverUnavailableFallsBackToOnDevice() {
        assertEquals(
            DictationProvider.OnDevice,
            DictationProviderPolicy.preferred(
                DictationProviderPreference.Automatic,
                serverAvailable = false,
                onDeviceSupported = true,
            ),
        )
    }

    @Test
    fun onDeviceUnsupportedFallsBackToServer() {
        assertEquals(
            DictationProvider.Server,
            DictationProviderPolicy.preferred(
                DictationProviderPreference.OnDevice,
                serverAvailable = true,
                onDeviceSupported = false,
            ),
        )
    }

    @Test
    fun neitherAvailableMeansDictationHidden() {
        assertEquals(
            emptyList<DictationProvider>(),
            DictationProviderPolicy.orderedProviders(
                DictationProviderPreference.Automatic,
                serverAvailable = false,
                onDeviceSupported = false,
            ),
        )
        assertNull(
            DictationProviderPolicy.preferred(
                DictationProviderPreference.Automatic,
                serverAvailable = false,
                onDeviceSupported = false,
            ),
        )
    }
}
