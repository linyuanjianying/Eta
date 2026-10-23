package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serial child execution deliberately keeps foreground and shell ownership with the parent. */
internal class SubAgentOrchestrator(
    private val main: AgentModelClient.ModelConfig,
    private val parent: AgentRunController,
    private val executor: AgentModelClient.ToolExecutor,
    private val capabilities: () -> AgentToolCapabilities,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        parent.throwIfCancelled()
        if (call.name == "subagent_models") {
            val items = JSONArray()
            runBlocking {
                ProviderRepository.allProviders().filter { it.isEnabled }.forEach { p ->
                    p.models.filter { it.isEnabled }.forEach { m ->
                        items.put(JSONObject().put("provider_id", p.id).put("provider", p.name)
                            .put("model_id", m.id).put("model", m.modelId))
                    }
                }
            }
            return AgentModelClient.ToolResult(JSONObject().put("models", items).toString())
        }
        val a = JSONObject(call.argumentsJson)
        val objective = a.getString("objective")
        require(objective.isNotBlank() && objective.length <= 16000)
        val requested = a.optJSONArray("allowed_tools") ?: JSONArray()
        val allowed = (0 until requested.length()).map { requested.getString(it) }.toSet()
        require(allowed.none { it.startsWith("subagent_") || it in setOf("memory_write", "conversation_history") })
        val selected = if (a.optString("model_id").isBlank()) main else runBlocking {
            val p = ProviderRepository.providerById(a.getString("provider_id"))
                ?.takeIf { it.isEnabled } ?: error("Provider unavailable")
            val m = p.models.firstOrNull { it.id == a.getString("model_id") && it.isEnabled }
                ?: error("Model unavailable")
            RuntimeConfigRepository.buildRuntimeConfig(p, m)
        }
        val config = selected.copy(
            terminalTools = main.terminalTools, browserTools = main.browserTools,
            deviceDirectTools = main.deviceDirectTools,
            deviceSensitiveReadTools = main.deviceSensitiveReadTools,
            deviceSensitiveActionTools = main.deviceSensitiveActionTools,
            hostedWebSearchEnabled = false,
            systemPrompt = selected.systemPrompt + "\n你是执行子代理，只完成给定任务，不直接与用户对话。不得扩展任务或创建子代理。返回实际结果、验证证据及未完成部分；不要把模型响应完成当作任务验收成功。",
        )
        val child = AgentRunController()
        val binding = parent.register(child::cancel)
        val timer = Executors.newSingleThreadScheduledExecutor()
        val deadline = a.optLong("timeout_seconds", 300)
        require(deadline in 10..1800)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        timer.schedule({ timedOut.set(true); child.cancel() }, deadline, TimeUnit.SECONDS)
        try {
            val response = AgentModelClient.complete(
                config = config, prompt = objective + "\n上下文与验收标准：\n" + a.optString("context"),
                toolExecutor = AgentModelClient.ToolExecutor { tool ->
                    parent.throwIfCancelled()
                    child.throwIfCancelled()
                    require(tool.name in allowed) { "Tool not assigned" }
                    executor.execute(tool)
                },
                runController = child, capabilitiesProvider = capabilities,
                sessionId = "subagent-${UUID.randomUUID()}", allowedToolNames = allowed,
            )
            parent.throwIfCancelled()
            return AgentModelClient.ToolResult(JSONObject().put("status", "response_completed")
                .put("result", response.content.take(32000)).put("verification_required", true).toString(), sensitive = true)
        } catch (e: Exception) {
            parent.throwIfCancelled()
            return AgentModelClient.ToolResult(JSONObject().put("status", if(timedOut.get()) "timeout" else "failed")
                .put("error_type", e.javaClass.simpleName).put("do_not_replay_side_effects", true).toString(), sensitive = true)
        } finally {
            binding.close(); timer.shutdownNow(); child.cancel()
        }
    }

    companion object {
        val names = setOf("subagent_models", "subagent_run")
        fun schemas(): List<JSONObject> {
            fun text() = JSONObject().put("type", "string")
            return listOf(
                AgentToolSchema.function("subagent_models", "列出可供子代理使用的已启用模型，不返回密钥。", JSONObject().put("type", "object").put("properties", JSONObject())),
                AgentToolSchema.function("subagent_run", "派遣独立上下文子代理执行明确任务。省略模型继承主代理，指定时先查询 subagent_models。主代理负责与用户沟通、规划及验收，子代理负责执行。明确目标、约束、输入及验收标准；只分配必要工具。当前串行执行，返回后主代理检查结果。不自动重试有副作用任务。", JSONObject()
                    .put("type", "object").put("required", JSONArray(listOf("objective", "allowed_tools")))
                    .put("properties", JSONObject().put("objective", text()).put("context", text())
                        .put("provider_id", text()).put("model_id", text())
                        .put("allowed_tools", JSONObject().put("type", "array").put("items", text()))
                        .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 10).put("maximum", 1800))))
            )
        }
    }
}
