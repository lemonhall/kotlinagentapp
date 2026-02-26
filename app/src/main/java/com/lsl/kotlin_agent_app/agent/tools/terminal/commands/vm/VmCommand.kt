package com.lsl.kotlin_agent_app.agent.tools.terminal.commands.vm

import android.content.Context
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommand
import com.lsl.kotlin_agent_app.agent.tools.terminal.TerminalCommandOutput
import com.lsl.kotlin_agent_app.vm.VmCommandBridge
import com.lsl.kotlin_agent_app.vm.VmMode
import com.lsl.kotlin_agent_app.vm.VmService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class VmCommand(
    appContext: Context,
) : TerminalCommand {
    private val ctx = appContext.applicationContext

    override val name: String = "vm"
    override val description: String = buildString {
        append("Local RISC-V VM (TinyEMU). Subcommands:\n")
        append("  vm status                    — VM state\n")
        append("  vm boot                      — start VM\n")
        append("  vm shutdown                  — stop VM\n")
        append("  vm python -c \"code\"          — run Python one-liner\n")
        append("  vm python script.py          — run script from .agents/\n")
        append("  vm pip install <pkg>         — install pip package")
    }

    override suspend fun run(
        argv: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        if (argv.size < 2) return invalidArgs("missing subcommand. Usage: vm <status|boot|shutdown|python|pip>")
        return when (argv[1].lowercase()) {
            "status" -> handleStatus()
            "boot" -> handleBoot()
            "shutdown" -> handleShutdown()
            "python" -> handlePython(argv.drop(2), stdin)
            "pip" -> handlePip(argv.drop(2))
            else -> invalidArgs("unknown subcommand: ${argv[1]}")
        }
    }

    private fun handleStatus(): TerminalCommandOutput {
        val running = VmService.isRunning.value
        val mode = VmService.mode.value
        val state = if (running) "running" else "stopped"
        val result = buildJsonObject {
            put("state", JsonPrimitive(state))
            put("mode", JsonPrimitive(mode.name.lowercase()))
        }
        return TerminalCommandOutput(
            exitCode = 0,
            stdout = "vm: $state (mode=${mode.name.lowercase()})",
            result = result,
        )
    }

    private fun handleBoot(): TerminalCommandOutput {
        if (VmService.isRunning.value) {
            return TerminalCommandOutput(exitCode = 0, stdout = "vm: already running")
        }
        VmService.boot(ctx, DEFAULT_PROFILE)
        return TerminalCommandOutput(exitCode = 0, stdout = "vm: boot requested")
    }

    private fun handleShutdown(): TerminalCommandOutput {
        if (!VmService.isRunning.value) {
            return TerminalCommandOutput(exitCode = 0, stdout = "vm: already stopped")
        }
        VmService.shutdown(ctx)
        return TerminalCommandOutput(exitCode = 0, stdout = "vm: shutdown requested")
    }

    private suspend fun handlePython(
        args: List<String>,
        stdin: String?,
    ): TerminalCommandOutput {
        val conn = VmService.connector.value
            ?: return vmNotRunning()

        val cmd = when {
            // vm python -c "print(1+1)"
            args.size >= 2 && args[0] == "-c" -> {
                val code = args.drop(1).joinToString(" ")
                "python3 -c ${shellQuote(code)}"
            }
            // vm python script.py  (stdin contains the script body)
            args.size == 1 && args[0].endsWith(".py") -> {
                val scriptBody = stdin?.trimEnd()
                    ?: return invalidArgs("vm python script.py requires script content in stdin")
                // Write script to /tmp then execute
                val guestPath = "/tmp/${args[0].replace("/", "_")}"
                val writeCmd = "cat > $guestPath << 'PYSCRIPT_EOF'\n$scriptBody\nPYSCRIPT_EOF"
                val bridge = withAgentMode(conn)
                bridge.exec(writeCmd, timeoutMs = 10_000)
                "python3 $guestPath"
            }
            else -> return invalidArgs("usage: vm python -c \"code\" | vm python script.py")
        }

        return execInVm(conn, cmd)
    }

    private suspend fun handlePip(args: List<String>): TerminalCommandOutput {
        if (args.isEmpty() || args[0].lowercase() != "install") {
            return invalidArgs("usage: vm pip install <package>")
        }
        val packages = args.drop(1)
        if (packages.isEmpty()) return invalidArgs("missing package name")

        val conn = VmService.connector.value
            ?: return vmNotRunning()

        val cmd = "pip3 install ${packages.joinToString(" ") { shellQuote(it) }}"
        return execInVm(conn, cmd, timeoutMs = 120_000)
    }

    private suspend fun execInVm(
        conn: com.lemonhall.jediterm.android.tinyemu.TinyEmuTtyConnector,
        cmd: String,
        timeoutMs: Long = 30_000,
    ): TerminalCommandOutput {
        val bridge = withAgentMode(conn)
        return try {
            val result = bridge.exec(cmd, timeoutMs)
            val ok = result.exitCode == 0
            TerminalCommandOutput(
                exitCode = result.exitCode,
                stdout = result.output,
                stderr = if (ok) "" else "exit code: ${result.exitCode}",
                result = buildJsonObject {
                    put("ok", JsonPrimitive(ok))
                    put("exit_code", JsonPrimitive(result.exitCode))
                    put("command", JsonPrimitive(cmd))
                },
            )
        } finally {
            VmService.switchMode(VmMode.IDLE)
        }
    }

    private fun withAgentMode(
        conn: com.lemonhall.jediterm.android.tinyemu.TinyEmuTtyConnector,
    ): VmCommandBridge {
        VmService.switchMode(VmMode.AGENT)
        return VmCommandBridge(conn)
    }

    private fun vmNotRunning(): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 1,
            stdout = "",
            stderr = "VM 未运行，请先执行 vm boot",
            errorCode = "VmNotRunning",
            errorMessage = "VM 未运行，请先执行 vm boot",
        )
    }

    private fun invalidArgs(message: String): TerminalCommandOutput {
        return TerminalCommandOutput(
            exitCode = 2,
            stdout = "",
            stderr = message,
            errorCode = "InvalidArgs",
            errorMessage = message,
        )
    }

    private fun shellQuote(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    companion object {
        private const val DEFAULT_PROFILE = "python312"
    }
}