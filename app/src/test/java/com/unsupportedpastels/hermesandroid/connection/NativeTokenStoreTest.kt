package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeTokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "native_token_store_tests"
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val otherOrigin = ServerOrigin.parse("https://other.example")

    @Before
    fun clearTokenPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savedTokensCanBeLoadedForTheSameOrigin() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token", "refresh-token")

        store.save(origin, tokens)

        assertEquals(tokens, store.load(origin))
    }

    @Test
    fun tokensAreOriginScopedAndClearOnlyRemovesThatOrigin() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val first = tokenSet("first-access", "first-refresh")
        val second = tokenSet("second-access", "second-refresh")
        store.save(origin, first)
        store.save(otherOrigin, second)

        store.clear(origin)

        assertNull(store.load(origin))
        assertEquals(second, store.load(otherOrigin))
    }

    @Test
    fun preferencesDoNotContainPlaintextTokenValues() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token-that-must-stay-encrypted", "refresh-token-that-must-stay-encrypted")

        store.save(origin, tokens)

        val persistedText = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all
            .values
            .joinToString("|")
        assertFalse(persistedText.contains(tokens.accessToken))
        assertFalse(persistedText.contains(tokens.refreshToken))
        assertTrue(persistedText.isNotBlank())
    }

    @Test
    fun ciphertextCopiedToAnotherOriginCannotBeDecrypted() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token", "refresh-token")
        store.save(origin, tokens)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val ciphertext = preferences.getString(tokenPreferenceKey(origin), null)
        requireNotNull(ciphertext)
        preferences.edit().putString(tokenPreferenceKey(otherOrigin), ciphertext).commit()

        assertNull(store.load(otherOrigin))
    }

    @Test
    fun corruptedCiphertextFailsClosed() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(tokenPreferenceKey(origin), "not-ciphertext").commit()

        assertNull(store.load(origin))
    }

    @Test
    fun oversizedTokenFieldsAreRejectedBeforePersistence() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val oversized = tokenSet("a".repeat(16 * 1024 + 1), "refresh-token")

        val failure = runCatching { store.save(origin, oversized) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(store.load(origin))
    }

    private fun tokenSet(accessToken: String, refreshToken: String) = NativeTokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user-1",
    )

    private fun tokenPreferenceKey(serverOrigin: ServerOrigin): String =
        "token_" + MessageDigest.getInstance("SHA-256")
            .digest(serverOrigin.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
