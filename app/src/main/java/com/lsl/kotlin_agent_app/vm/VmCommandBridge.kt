package com.lsl.kotlin_agent_app.vm

import com.lemonhall.jediterm.android.tinyemu.TinyEmuTtyConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class VmExecResult(
    val output: String,
    val exitCode: Int,
)

class VmCommandBridge(private val connector: TinyEmuTtyConnector) {

    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): VmExecResult {
        val marker = "---END-${UUID.randomUUID().toString().take(8)}---"
        // Write: cmd ; echo '<marker>:'$?
        val wrapped = "$cmd; echo '$marker:'\$?\n"

        return withContext(Dispatchers.IO) {
            connector.write(wrapped)

            val buf = CharArray(4096)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val result = withTimeoutOrNull(
                    (deadline - System.currentTimeMillis()).coerceAtLeast(100)
                ) {
                    val n = connector.read(buf, 0, buf.size)
                    if (n > 0) String(buf, 0, n) else null
                }
                if (result != null) sb.append(result)

                val text = sb.toString()
                val markerIdx = text.indexOf(marker)
                if (markerIdx >= 0) {
                    return@withContext parseResult(text, marker, markerIdx)
                }
            }
            VmExecResult(output = sb.toString(), exitCode = -1)
        }
    }

    private fun parseResult(text: String, marker: String, markerIdx: Int): VmExecResult {
        // Output is everything before the marker line
        val output = text.substring(0, markerIdx).trimEnd('\n', '\r')
        // After marker: ":<exitCode>" possibly followed by newlines
        val afterMarker = text.substring(markerIdx + marker.length)
        val exitCodeStr = afterMarker.trimStart(':').trim().takeWhile { it.isDigit() }
        val exitCode = exitCodeStr.toIntOrNull() ?: -1
        // Strip the echoed command from the beginning of output
        // The first line is typically the echoed command itself
        val lines = output.lines()
        val cleanOutput = if (lines.size > 1) {
            lines.drop(1).joinToString("\n")
        } else {
            ""
        }
        return VmExecResult(output = cleanOutput, exitCode = exitCode)
    }
}