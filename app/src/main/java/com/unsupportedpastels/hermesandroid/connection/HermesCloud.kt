package com.unsupportedpastels.hermesandroid.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Hermes Cloud discovery contract, mirrored from the desktop shell
 * (`apps/desktop/electron/main.ts` — `discoverCloudAgents`, `trimCloudAgents`,
 * `trimCloudOrg`, `parseOrgSelectionError`). These are the trimmed DTOs the
 * Nous Portal `GET /api/agents` endpoint returns.
 *
 * The desktop authenticates that call with a Privy browser cookie because it is
 * an Electron shell. A native client instead uses the Portal's device-code
 * OAuth bearer (same `hermes-cli` client the CLI uses); both authenticate the
 * identical endpoint. All parsing here is tolerant: unknown fields are ignored
 * and malformed rows dropped so a preview-era Portal response can never crash
 * the client.
 */

/** The canonical Nous Portal origin the CLI/desktop default to. */
const val DEFAULT_NOUS_PORTAL_ORIGIN: String = "https://portal.nousresearch.com"

/** A discovered Hermes Cloud agent — the trimmed row from `GET /api/agents`. */
data class CloudAgent(
    val id: String,
    val name: String,
    val status: String,
    /** null until the agent has a provisioned dashboard (show "provisioning…"). */
    val dashboardUrl: String?,
    /** "active" | "degraded" | "down" | "unknown". */
    val dashboardGatewayState: String,
) {
    /** True once the agent exposes a reachable dashboard origin to connect to. */
    val isConnectable: Boolean
        get() {
            val url = dashboardUrl ?: return false
            return url.isNotBlank() && runCatching { ServerOrigin.parse(url) }.isSuccess
        }
}

/**
 * An org the signed-in user belongs to. Surfaced for the picker shown when a
 * multi-org user's discovery call needs disambiguation (Portal NAS 409).
 */
data class CloudOrg(
    val id: String,
    val slug: String?,
    val name: String,
    val isPersonal: Boolean,
    /** "OWNER" | "MEMBER". */
    val role: String,
)

/**
 * Discovery result: either the agent list, OR a request to pick an org first
 * (multi-org user, no org chosen yet). On the [Agents] branch, [Agents.org]
 * echoes the authoritatively-resolved org the list was scoped to so the client
 * can persist it without relying on transient picker state.
 */
sealed interface CloudDiscoverResult {
    data class Agents(
        val agents: List<CloudAgent>,
        val org: CloudOrg?,
    ) : CloudDiscoverResult

    data class NeedsOrgSelection(
        val orgs: List<CloudOrg>,
    ) : CloudDiscoverResult
}

/**
 * Pure, tolerant parsers for the Portal discovery contract. Kept separate from
 * transport so protocol shape is unit-testable without a live server.
 */
object HermesCloudParsing {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parse a successful `GET /api/agents` body into agents + resolved org. */
    fun parseAgentsBody(body: String): CloudDiscoverResult.Agents {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        return CloudDiscoverResult.Agents(
            agents = trimAgents(root?.get("agents")),
            org = trimOrg(root?.get("org")),
        )
    }

    /**
     * Parse a 409 `org_selection_required` error body into the org list, or
     * null when it isn't that shape (caller then treats it as a hard error).
     */
    fun parseOrgSelection(body: String): List<CloudOrg>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val error = (root["error"] as? JsonPrimitive)?.contentOrNull
        if (error != "org_selection_required") return null
        val orgsElement = root["orgs"] ?: return null
        val orgs = trimOrgs(orgsElement)
        return orgs
    }

    private fun trimAgents(element: JsonElement?): List<CloudAgent> {
        val array = (element as? JsonArray) ?: return emptyList()
        return array.mapNotNull { entry ->
            // Wrap the whole row so a single preview-era schema mismatch (e.g. a
            // wrong-typed field like "name": {}) discards only that row instead
            // of failing the entire discovery — the tolerant-row contract.
            runCatching {
                val obj = (entry as? JsonObject) ?: return@runCatching null
                val id = obj.stringOrNull("id") ?: return@runCatching null
                if (id.isBlank()) return@runCatching null
                CloudAgent(
                    id = id,
                    name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: id,
                    status = obj.stringOrNull("status")?.takeIf { it.isNotBlank() } ?: "unknown",
                    dashboardUrl = obj.stringOrNull("dashboardUrl")?.takeIf { it.isNotBlank() },
                    dashboardGatewayState = obj.stringOrNull("dashboardGatewayState")
                        ?.takeIf { it.isNotBlank() } ?: "unknown",
                )
            }.getOrNull()
        }
    }

    private fun trimOrgs(element: JsonElement?): List<CloudOrg> {
        val array = (element as? JsonArray) ?: return emptyList()
        return array.mapNotNull { entry -> trimOrg(entry) }
    }

    private fun trimOrg(element: JsonElement?): CloudOrg? = runCatching {
        val obj = (element as? JsonObject) ?: return@runCatching null
        val id = obj.stringOrNull("id") ?: return@runCatching null
        if (id.isBlank()) return@runCatching null
        CloudOrg(
            id = id,
            slug = obj.stringOrNull("slug")?.takeIf { it.isNotBlank() },
            name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: id,
            isPersonal = obj.booleanOrNull("isPersonal") ?: false,
            role = obj.stringOrNull("role")?.takeIf { it.isNotBlank() } ?: "MEMBER",
        )
    }.getOrNull()

    /** Read a string field, returning null for absent OR wrong-typed values. */
    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Read a boolean field, returning null for absent OR wrong-typed values. */
    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
