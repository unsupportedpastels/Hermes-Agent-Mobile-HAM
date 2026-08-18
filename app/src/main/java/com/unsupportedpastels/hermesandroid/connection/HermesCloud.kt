package com.unsupportedpastels.hermesandroid.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val error = root["error"]?.jsonPrimitive?.contentOrNull
        if (error != "org_selection_required") return null
        val orgsElement = root["orgs"] ?: return null
        val orgs = trimOrgs(orgsElement)
        return orgs
    }

    private fun trimAgents(element: JsonElement?): List<CloudAgent> {
        val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { entry ->
            val obj = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            CloudAgent(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
                status = obj["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "unknown",
                dashboardUrl = obj["dashboardUrl"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() },
                dashboardGatewayState = obj["dashboardGatewayState"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: "unknown",
            )
        }
    }

    private fun trimOrgs(element: JsonElement?): List<CloudOrg> {
        val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { entry -> trimOrg(entry) }
    }

    private fun trimOrg(element: JsonElement?): CloudOrg? {
        val obj = runCatching { element?.jsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (id.isBlank()) return null
        return CloudOrg(
            id = id,
            slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id,
            isPersonal = obj["isPersonal"]?.jsonPrimitive?.booleanOrNull ?: false,
            role = obj["role"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "MEMBER",
        )
    }
}
