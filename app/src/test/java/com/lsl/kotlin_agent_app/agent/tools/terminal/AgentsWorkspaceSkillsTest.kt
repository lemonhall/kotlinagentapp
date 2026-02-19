package com.lsl.kotlin_agent_app.agent.tools.terminal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentsWorkspaceSkillsTest {

    @Test
    fun agentsWorkspace_installsStockCliSkill_andSeedsEnv() =
        runTerminalExecToolTest { tool ->
            val skill = File(tool.filesDir, ".agents/skills/stock-cli/SKILL.md")
            assertTrue("stock-cli skill should exist: $skill", skill.exists())
            val env = File(tool.filesDir, ".agents/skills/stock-cli/secrets/.env")
            assertTrue("stock-cli .env should exist: $env", env.exists())
        }

    @Test
    fun agentsWorkspace_installsExchangeRateCliSkill() =
        runTerminalExecToolTest { tool ->
            val skill = File(tool.filesDir, ".agents/skills/exchange-rate-cli/SKILL.md")
            assertTrue("exchange-rate-cli skill should exist: $skill", skill.exists())
        }
}

