package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encrypted at-rest storage for the Nous Portal OAuth token that authenticates
 * Hermes Cloud discovery. Scoped by portal origin (there is only one canonical
 * portal today, but staging overrides exist), mirroring [EncryptedNativeTokenStore]
 * so the refresh token never touches plaintext prefs.
 */
interface PortalTokenStore {
    suspend fun load(portalOrigin: ServerOrigin): PortalTokenSet?

    suspend fun save(portalOrigin: ServerOrigin, tokens: PortalTokenSet)

    suspend fun clear(portalOrigin: ServerOrigin)
}

class EncryptedPortalTokenStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = {
        createAead(context.applicationContext, preferencesName)
    },
) : PortalTokenStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)

    override suspend fun load(portalOrigin: ServerOrigin): PortalTokenSet? = withContext(ioDispatcher) {
        val encoded = preferences.getString(preferenceKey(portalOrigin), null)
            ?: return@withContext null
        val ciphertext = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return@withContext null
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) return@withContext null

        runCatching {
            val plaintext = aead.decrypt(ciphertext, associatedData(portalOrigin))
            if (plaintext.size > MAX_SERIALIZED_TOKEN_BYTES) return@runCatching null
            val tokens = json.decodeFromString<PortalTokenSet>(
                plaintext.toString(StandardCharsets.UTF_8),
            )
            validate(tokens)
            tokens
        }.getOrNull()
    }

    @Suppress("UseKtx") // Preserve commit() result so credential persistence fails closed.
    override suspend fun save(portalOrigin: ServerOrigin, tokens: PortalTokenSet) = withContext(ioDispatcher) {
        validate(tokens)
        val plaintext = json.encodeToString(tokens).toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_SERIALIZED_TOKEN_BYTES) {
            "Portal token record is too large"
        }
        val ciphertext = aead.encrypt(plaintext, associatedData(portalOrigin))
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Portal token record is too large"
        }
        check(
            preferences.edit()
                .putString(
                    preferenceKey(portalOrigin),
                    Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                )
                .commit(),
        ) { "Could not persist Portal tokens" }
    }

    @Suppress("UseKtx") // Preserve commit() result so credential removal fails closed.
    override suspend fun clear(portalOrigin: ServerOrigin) = withContext(ioDispatcher) {
        check(
            preferences.edit()
                .remove(preferenceKey(portalOrigin))
                .commit(),
        ) { "Could not clear Portal tokens" }
    }

    private fun associatedData(portalOrigin: ServerOrigin): ByteArray =
        portalOrigin.value.toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(portalOrigin: ServerOrigin): String =
        "portal_" + sha256(portalOrigin.value).toHex()

    private fun validate(tokens: PortalTokenSet) {
        require(tokens.accessToken.isNotBlank()) { "Portal token response was incomplete" }
        requireTokenSize(tokens.accessToken)
        requireTokenSize(tokens.refreshToken)
        requireTokenSize(tokens.tokenType)
        requireTokenSize(tokens.scope)
    }

    private fun requireTokenSize(value: String) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_TOKEN_FIELD_BYTES) {
            "Portal token field is too large"
        }
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "portal_token_store"
        const val KEYSET_PREFERENCES_SUFFIX = ".keyset"
        const val KEYSET_NAME = "portal_token_store_keyset"
        const val MASTER_KEY_URI = "android-keystore://portal_token_store_master"
        const val MAX_TOKEN_FIELD_BYTES = 16 * 1024
        const val MAX_SERIALIZED_TOKEN_BYTES = 64 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_SERIALIZED_TOKEN_BYTES + 1024

        fun createAead(context: Context, preferencesName: String): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(
                    context,
                    "$KEYSET_NAME:$preferencesName",
                    "$preferencesName$KEYSET_PREFERENCES_SUFFIX",
                )
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
