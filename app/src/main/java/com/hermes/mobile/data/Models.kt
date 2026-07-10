package com.hermes.mobile.data

data class AuthProvider(
    val name: String,
    val displayName: String,
    val supportsPassword: Boolean,
)

data class HermesStatus(
    val version: String,
    val authRequired: Boolean,
    val profiles: List<String>,
    val activeSessions: Int,
)

data class ConnectionProbe(
    val status: HermesStatus,
    val providers: List<AuthProvider>,
)

data class HermesSession(
    val id: String,
    val title: String,
    val preview: String,
    val model: String,
    val profile: String,
    val cwd: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val messageCount: Int,
    val lastActive: Double,
    val isActive: Boolean,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val projectName: String
        get() {
            val normalized = cwd.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
            return normalized.trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { "No project" }
        }
}

data class HermesSnapshot(
    val status: HermesStatus,
    val sessions: List<HermesSession>,
)

data class HermesProfile(
    val name: String,
    val description: String = "",
)

data class ModelOption(
    val provider: String,
    val providerName: String,
    val model: String,
    val available: Boolean = true,
    /** The provider/model configured as this profile's main default. */
    val isProfileDefault: Boolean = false,
)

data class ModelDefaultResult(
    val confirmationMessage: String? = null,
)

data class HermesProjectFolder(
    val path: String,
    val label: String = "",
    val isPrimary: Boolean = false,
)

data class HermesProject(
    val id: String,
    val name: String,
    val primaryPath: String = "",
    val folders: List<HermesProjectFolder> = emptyList(),
) {
    val selectableFolders: List<HermesProjectFolder>
        get() = folders.ifEmpty {
            primaryPath.takeIf(String::isNotBlank)?.let {
                listOf(HermesProjectFolder(it, isPrimary = true))
            }.orEmpty()
        }
}

data class PendingAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val hasToolCalls: Boolean = false,
)
