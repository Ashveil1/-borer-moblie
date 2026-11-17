package com.example.mcp

import com.example.data.McpServerConfig
import kotlinx.serialization.json.JsonObject

enum class McpConnectionStatus(val label: String) {
    DISCONNECTED("OFF"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED"),
    ERROR("ERROR")
}

data class McpToolInfo(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

data class McpResourceInfo(
    val serverId: String,
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String?
)

data class McpResourceTemplateInfo(
    val serverId: String,
    val uriTemplate: String,
    val name: String,
    val description: String,
    val mimeType: String?
)

data class McpPromptArgument(
    val name: String,
    val description: String,
    val required: Boolean
)

data class McpPromptInfo(
    val serverId: String,
    val name: String,
    val description: String,
    val arguments: List<McpPromptArgument>
)

data class McpServerState(
    val config: McpServerConfig,
    val status: McpConnectionStatus = McpConnectionStatus.DISCONNECTED,
    val protocolVersion: String? = null,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val instructions: String? = null,
    val errorMessage: String? = null,
    val tools: List<McpToolInfo> = emptyList(),
    val resources: List<McpResourceInfo> = emptyList(),
    val resourceTemplates: List<McpResourceTemplateInfo> = emptyList(),
    val prompts: List<McpPromptInfo> = emptyList(),
    val stderrTail: List<String> = emptyList(),
    val lastConnectedAt: String? = null
)

class McpRpcException(
    val code: Int,
    override val message: String,
    val data: String? = null
) : RuntimeException(if (code != 0) "MCP error ($code): $message" else message)

object McpProtocol {
    const val LATEST_VERSION = "2025-03-26"
    const val LEGACY_VERSION = "2024-11-05"
}
