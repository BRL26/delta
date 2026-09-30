package com.blurr.voice.v2.actions

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the data-driven [Action.ActionSerializer], which is what turns
 * the LLM's JSON into type-safe actions.
 *
 * The tool-style actions (notifications, list_files, device_state) are called
 * with empty `{}` argument objects, which is a shape the serializer must accept
 * without declaring parameters. This test pins that behaviour down so a future
 * refactor cannot quietly break the tools-first agent path.
 */
class ActionParsingTest {

    @Test
    fun `tool actions parse from an empty object`() {
        assertEquals(Action.Notifications, parse("""{"notifications": {}}"""))
        assertEquals(Action.ListFiles, parse("""{"list_files": {}}"""))
        assertEquals(Action.DeviceState, parse("""{"device_state": {}}"""))
        assertEquals(Action.Reminders, parse("""{"reminders": {}}"""))
        assertEquals(Action.RequestUnlock, parse("""{"request_unlock": {}}"""))
    }

    @Test
    fun `parameterised actions still parse`() {
        assertEquals(Action.OpenApp("Settings"), parse("""{"open_app": {"app_name": "Settings"}}"""))
        assertEquals(
            Action.LaunchIntent("SetTimer", mapOf("seconds" to "300")),
            parse("""{"launch_intent": {"intent_name": "SetTimer", "parameters": {"seconds": "300"}}}""")
        )
        assertEquals(Action.ReadFile("results.md"), parse("""{"read_file": {"file_name": "results.md"}}"""))
    }

    @Test
    fun `spec registry exposes the tool actions to the prompt`() {
        val names = Action.getAllSpecs().map { it.name }
        assertTrue("notifications spec missing", names.contains("notifications"))
        assertTrue("list_files spec missing", names.contains("list_files"))
        assertTrue("device_state spec missing", names.contains("device_state"))
        assertTrue("reminders spec missing", names.contains("reminders"))
        assertTrue("request_unlock spec missing", names.contains("request_unlock"))
    }

    private fun parse(json: String): Action = Json.decodeFromString(Action.serializer(), json)
}