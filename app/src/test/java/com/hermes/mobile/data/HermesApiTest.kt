package com.hermes.mobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesApiTest {
    @Test fun normalizesRemoteUrl() {
        assertEquals("https://host/hermes", HermesApi.normalizeBaseUrl(" https://host/hermes/// "))
    }

    @Test fun parsesProviders() {
        val providers = HermesApi.parseProviders(JSONObject("""{
          "providers": [
            {"name":"basic","display_name":"Username / password","supports_password":true},
            {"name":"nous","display_name":"Nous Research","supports_password":false}
          ]
        }"""))
        assertEquals(2, providers.size)
        assertEquals(true, providers.first().supportsPassword)
        assertEquals("Nous Research", providers.last().displayName)
    }

    @Test fun parsesStoredAndRuntimeMessages() {
        val messages = HermesApi.parseMessages(JSONObject("""{
          "messages": [
            {"id":"1","role":"user","content":"hello"},
            {"role":"assistant","text":"hi there"},
            {"role":"assistant","content":[{"type":"text","text":"part one"},{"type":"text","text":"part two"}]},
            {"role":"assistant","content":"checking","tool_calls":[{"id":"call-1"}]}
          ]
        }"""))
        assertEquals(4, messages.size)
        assertEquals("hello", messages[0].text)
        assertEquals("hi there", messages[1].text)
        assertEquals("part one\npart two", messages[2].text)
        assertEquals(true, messages[3].hasToolCalls)
    }

    @Test fun normalizesLocalAndSecureHostsButRejectsPublicCleartext() {
        assertEquals("http://10.0.2.2:9119", HermesApi.normalizeBaseUrl(" http://10.0.2.2:9119/ "))
        assertEquals("http://192.168.1.12:9119", HermesApi.normalizeBaseUrl("http://192.168.1.12:9119"))
        assertEquals("https://agent.example.com", HermesApi.normalizeBaseUrl("https://agent.example.com/"))
        assertThrows(IllegalArgumentException::class.java) {
            HermesApi.normalizeBaseUrl("http://agent.example.com")
        }
        assertTrue(HermesApi.sameOrigin("https://agent.example.com", "https://agent.example.com/api/status"))
        assertFalse(HermesApi.sameOrigin("https://one.example.com", "https://two.example.com"))
    }

    @Test fun parsesCurrentDashboardSessionShape() {
        val payload = JSONObject("""{
          "sessions": [{
            "id": "s_1", "title": "Android client", "preview": "Build it",
            "model": "gpt-5", "profile": "default", "cwd": "C:/work/mobile",
            "input_tokens": 1200, "output_tokens": 300, "message_count": 4,
            "last_active": 42.0, "is_active": true
          }]
        }""")
        val session = HermesApi.parseSessions(payload).single()
        assertEquals("s_1", session.id)
        assertEquals("mobile", session.projectName)
        assertEquals(1500, session.totalTokens)
    }

    @Test fun treatsJsonNullWorkspaceAsNoProject() {
        val session = HermesApi.parseSessions(JSONObject("""{
          "sessions": [{"id":"s-null","cwd":null,"workspace":null}]
        }""")).single()
        assertEquals("", session.cwd)
        assertEquals("No project", session.projectName)
    }

    @Test fun parsesProfileMetadataAndLegacyProfileNames() {
        val profiles = HermesApi.parseProfiles(JSONObject("""{
          "profiles": [
            {"name":"default","description":"General agent"},
            "security"
          ]
        }"""))
        assertEquals(listOf("default", "security"), profiles.map { it.name })
        assertEquals("General agent", profiles.first().description)
    }

    @Test fun parsesConfiguredModelOptionsAndAvailability() {
        val models = HermesApi.parseModelOptions(JSONObject("""{
          "model":"gpt-5.5",
          "provider":"openai-codex",
          "providers":[
            {
              "slug":"openai-codex","name":"OpenAI Codex","authenticated":true,
              "models":["gpt-5.5","gpt-5.4"],"unavailable_models":["gpt-5.4"]
            },
            {"slug":"deepseek","name":"DeepSeek","authenticated":false,"models":["v4"]}
          ]
        }"""))
        assertEquals(2, models.size)
        assertEquals("openai-codex", models.first().provider)
        assertEquals(true, models.first().available)
        assertTrue(models.first().isProfileDefault)
        assertEquals(false, models.last().available)
    }
}
