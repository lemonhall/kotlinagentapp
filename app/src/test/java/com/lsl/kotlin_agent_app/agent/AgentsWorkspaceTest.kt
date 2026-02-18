package com.lsl.kotlin_agent_app.agent

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentsWorkspaceTest {

    @Test
    fun ensureInitialized_seedsBuiltinSkills() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)

        ws.ensureInitialized()

        val skills =
            ws.listDir(".agents/skills")
                .filter { it.type == AgentsDirEntryType.Dir }
                .map { it.name }
                .toSet()

        assertTrue(skills.contains("hello-world"))
        assertTrue(skills.contains("skill-creator"))
        assertTrue(skills.contains("brainstorming"))
        assertTrue(skills.contains("find-skills"))
        assertTrue(skills.contains("deep-research"))
        assertTrue(skills.contains("qqmail-cli"))

        val text = ws.readTextFile(".agents/skills/skill-creator/SKILL.md")
        assertTrue(text.contains("name: skill-creator"))

        val qqmailEnv = ws.readTextFile(".agents/skills/qqmail-cli/secrets/.env")
        assertTrue(qqmailEnv.contains("EMAIL_ADDRESS="))
        assertTrue(qqmailEnv.contains("EMAIL_PASSWORD="))
    }

    @Test
    fun pathTraversal_isRejected() {
        val context = RuntimeEnvironment.getApplication()
        val ws = AgentsWorkspace(context)

        assertThrows(IllegalStateException::class.java) {
            ws.listDir("../")
        }
        assertThrows(IllegalStateException::class.java) {
            ws.readTextFile(".agents/skills/../../outside.txt")
        }
    }
}
