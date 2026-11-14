package com.example.tools

import com.example.data.LearningMemory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

fun learningTool(memory: LearningMemory): Tool = object : Tool {
    override val name = "learn"
    override val description =
        "Write to your persistent memory, which survives across sessions. Record what worked (action=experience), save a reusable procedure (action=technique), or note host history (action=target)."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "action" to schemaProp("string", "experience | technique | target"),
            "fingerprint" to schemaProp("string", "e.g. php+mysql|sqli"),
            "approach" to schemaProp("string"),
            "outcome" to schemaProp("string"),
            "worked" to schemaProp("boolean"),
            "situation" to schemaProp("string"),
            "steps" to schemaProp("string"),
            "host" to schemaProp("string"),
            "profile" to schemaProp("string"),
            "finding" to schemaProp("string"),
            "deadEnd" to schemaProp("string")
        ),
        required = listOf("action")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        return when ((args["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()) {
            "experience" -> {
                val fp = (args["fingerprint"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val approach = (args["approach"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (fp.isEmpty() || approach.isEmpty()) {
                    return ToolResult("experience needs fingerprint and approach", isError = true)
                }
                val worked = (args["worked"] as? JsonPrimitive)?.booleanOrNull == true
                val outcome = (args["outcome"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                memory.recordExperience(fp, approach, outcome, worked)
                ctx.log.append("memory.write", mapOf("action" to "experience", "fingerprint" to fp, "approach" to approach, "worked" to worked))
                val ranked = memory.experienceFor(fp, 4)
                ToolResult(
                    (listOf("recorded for $fp") + ranked.map { e ->
                        "  ${(e.score * 100).toInt()}% · ${e.approach} (${e.success}/${e.attempts})"
                    }).joinToString("\n")
                )
            }
            "technique" -> {
                val situation = (args["situation"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val steps = (args["steps"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (situation.isEmpty() || steps.isEmpty()) {
                    return ToolResult("technique needs situation and steps", isError = true)
                }
                val worked = (args["worked"] as? JsonPrimitive)?.booleanOrNull != false
                memory.recordTechnique(situation, steps, worked)
                ctx.log.append("memory.write", mapOf("action" to "technique", "situation" to situation))
                ToolResult("technique saved: when $situation -> ${steps.take(120)}")
            }
            "target" -> {
                val host = (args["host"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (host.isEmpty()) return ToolResult("target needs host", isError = true)
                memory.noteTarget(
                    host = host,
                    profile = (args["profile"] as? JsonPrimitive)?.contentOrNull,
                    finding = (args["finding"] as? JsonPrimitive)?.contentOrNull,
                    deadEnd = (args["deadEnd"] as? JsonPrimitive)?.contentOrNull
                )
                ctx.log.append("memory.write", mapOf("action" to "target", "host" to host))
                val h = memory.targetHistory(host)
                if (h == null) ToolResult("recorded $host")
                else ToolResult(
                    listOfNotNull(
                        "$host · visit ${h.visits}",
                        h.profile.takeIf { it.isNotBlank() }?.let { "stack: $it" },
                        h.findings.takeIf { it.isNotBlank() }?.let { "found: $it" },
                        h.deadEnds.takeIf { it.isNotBlank() }?.let { "dead ends: $it" }
                    ).joinToString("\n")
                )
            }
            else -> ToolResult("action must be experience | technique | target", isError = true)
        }
    }
}

data class BorerSkill(
    val name: String,
    val description: String,
    val dir: File,
    val body: String
)

object SkillStore {
    fun loadSkills(workdir: File): List<BorerSkill> {
        val skillsRoot = File(workdir, ".borer/skills")
        if (!skillsRoot.exists()) return emptyList()
        return skillsRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
            ?.map { dir ->
                val raw = File(dir, "SKILL.md").readText()
                val (meta, body) = parseFrontMatter(raw)
                BorerSkill(
                    name = meta["name"] ?: dir.name,
                    description = meta["description"].orEmpty(),
                    dir = dir,
                    body = body
                )
            }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun skillIndex(skills: List<BorerSkill>): String {
        if (skills.isEmpty()) return ""
        return (listOf(
            "Skills are playbooks you can load by name with the `skill` tool. Load one before",
            "working on a matching problem; it is cheaper than rediscovering it."
        ) + skills.map { "- ${it.name}: ${it.description}" }).joinToString("\n")
    }

    private fun parseFrontMatter(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 3)
        if (end == -1) return emptyMap<String, String>() to text
        val raw = text.substring(3, end).trim()
        val meta = linkedMapOf<String, String>()
        for (line in raw.lines()) {
            val m = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").matchEntire(line.trim()) ?: continue
            meta[m.groupValues[1]] = m.groupValues[2].trim().trim('"', '\'')
        }
        return meta to text.substring(end + 4).trim()
    }
}

fun skillTool(skills: List<BorerSkill>): Tool = object : Tool {
    override val name = "skill"
    override val description =
        "Read a skill playbook by name (returns its full instructions). Pass `file` to read a bundled reference file inside the skill directory."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "name" to schemaProp("string", "skill name"),
            "file" to schemaProp("string", "optional relative file path")
        ),
        required = listOf("name")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val wanted = (args["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val skill = skills.firstOrNull { it.name == wanted }
            ?: return ToolResult(
                "no skill named $wanted. available: ${skills.joinToString(", ") { it.name }.ifBlank { "(none)" }}",
                isError = true
            )
        val relFile = (args["file"] as? JsonPrimitive)?.contentOrNull
        if (!relFile.isNullOrBlank()) {
            val skillRoot = skill.dir.canonicalFile
            val target = File(skillRoot, relFile).canonicalFile
            if (!target.toPath().startsWith(skillRoot.toPath())) {
                return ToolResult("path escapes the skill directory", isError = true)
            }
            if (!target.exists() || !target.isFile) {
                return ToolResult("no such file in ${skill.name}: $relFile", isError = true)
            }
            return ToolResult(target.readText())
        }
        return ToolResult("# ${skill.name}\n\n${skill.body}")
    }
}
