package com.example.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TransformEngine {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val supportedOps = listOf(
        "url", "urldecode", "base64", "unbase64", "hex", "unhex",
        "hash", "hmac", "jwt", "gzip", "gunzip", "html", "htmlunescape",
        "unicode", "case", "json", "charset"
    )

    fun run(
        op: String,
        rawInput: String,
        inputHex: String? = null,
        algorithm: String = "sha256",
        key: String = "",
        offset: Int = 0
    ): Result<String> = runCatching {
        val input = if (!inputHex.isNullOrBlank()) {
            hexToBytes(inputHex).toString(Charsets.UTF_8)
        } else {
            rawInput
        }
        val algoName = when (algorithm.lowercase()) {
            "md5" -> "MD5"
            "sha1" -> "SHA-1"
            "sha512" -> "SHA-512"
            else -> "SHA-256"
        }
        val hmacName = when (algorithm.lowercase()) {
            "md5" -> "HmacMD5"
            "sha1" -> "HmacSHA1"
            "sha512" -> "HmacSHA512"
            else -> "HmacSHA256"
        }

        when (op.lowercase()) {
            "url" -> URLEncoder.encode(input, "UTF-8").replace("+", "%20")
            "urldecode" -> URLDecoder.decode(input, "UTF-8")
            "base64" -> Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
            "unbase64" -> Base64.getDecoder().decode(input.trim()).toString(Charsets.UTF_8)
            "hex" -> input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            "unhex" -> hexToBytes(input).toString(Charsets.UTF_8)
            "hash" -> MessageDigest.getInstance(algoName)
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "hmac" -> {
                val mac = Mac.getInstance(hmacName)
                mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), hmacName))
                mac.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }
            "jwt" -> {
                val parts = input.trim().split('.')
                require(parts.size >= 2) { "not a jwt" }
                fun decodePart(p: String): String {
                    val padded = p.replace('-', '+').replace('_', '/')
                        .let { it + "=".repeat((4 - it.length % 4) % 4) }
                    return Base64.getDecoder().decode(padded).toString(Charsets.UTF_8)
                }
                "header: ${decodePart(parts[0])}\npayload: ${decodePart(parts[1])}\nsignature(b64url): ${parts.getOrElse(2) { "" }}"
            }
            "gzip" -> {
                val out = ByteArrayOutputStream()
                GZIPOutputStream(out).use { it.write(input.toByteArray(Charsets.UTF_8)) }
                Base64.getEncoder().encodeToString(out.toByteArray()) + " (base64)"
            }
            "gunzip" -> {
                val raw = Base64.getDecoder().decode(input.trim())
                GZIPInputStream(ByteArrayInputStream(raw)).readBytes().toString(Charsets.UTF_8)
            }
            "html" -> buildString {
                for (c in input) {
                    when (c) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&#39;")
                        else -> append(c)
                    }
                }
            }
            "htmlunescape" -> input
                .replace(Regex("&#(\\d+);")) { m ->
                    runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault(m.value)
                }
                .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                    runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault(m.value)
                }
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            "unicode" -> buildString {
                var i = 0
                while (i < input.length) {
                    val cp = input.codePointAt(i)
                    if (cp > 127) append("\\u%04x".format(cp)) else appendCodePoint(cp)
                    i += Character.charCount(cp)
                }
            }
            "case" -> buildString {
                for (c in input) {
                    when (c) {
                        in 'a'..'z' -> append(('a'.code + (((c.code - 'a'.code + offset) % 26 + 26) % 26)).toChar())
                        in 'A'..'Z' -> append(('A'.code + (((c.code - 'A'.code + offset) % 26 + 26) % 26)).toChar())
                        else -> append(c)
                    }
                }
            }
            "json" -> {
                val parsed = json.parseToJsonElement(input)
                json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsed)
            }
            "charset" -> {
                val latinBytes = input.toByteArray(Charsets.ISO_8859_1)
                val utf8AsLatin = input.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
                "latin1->utf8: ${latinBytes.toString(Charsets.UTF_8)}\nutf8->latin1: $utf8AsLatin"
            }
            else -> error("unknown op: $op")
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.replace(Regex("[^0-9a-fA-F]"), "")
            .chunked(2)
            .filter { it.length == 2 }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}

object DiffEngine {
    fun compare(
        aRaw: String,
        bRaw: String,
        ignoreWhitespace: Boolean = false,
        maxLines: Int = 200
    ): String {
        fun norm(t: String) = if (ignoreWhitespace) t.replace(Regex("\\s+"), " ").trim() else t
        val aLines = norm(aRaw).split('\n')
        val bLines = norm(bRaw).split('\n')

        fun headerInfo(lines: List<String>): Pair<String, List<String>> {
            val status = lines.firstOrNull { Regex("^HTTP/\\d\\.\\d \\d{3}").containsMatchIn(it) }.orEmpty()
            val sep = lines.indexOfFirst { it.trim().isEmpty() }
            val headers = if (sep > 0) lines.subList(0, sep).filter { it.contains(':') } else emptyList()
            return status to headers
        }

        val (sa, ha) = headerInfo(aLines)
        val (sb, hb) = headerInfo(bLines)
        val headerDiff = if (ha.isNotEmpty() && hb.isNotEmpty()) {
            buildList {
                if (sa != sb) add("status: ${sa.ifBlank { "?" }} -> ${sb.ifBlank { "?" }}")
                ha.filter { it !in hb }.forEach { add("- header $it") }
                hb.filter { it !in ha }.forEach { add("+ header $it") }
            }.joinToString("\n")
        } else ""

        val setA = aLines.map { it.trim() }.toSet()
        val setB = bLines.map { it.trim() }.toSet()
        val onlyA = setA.filter { it.isNotEmpty() && it !in setB }
        val onlyB = setB.filter { it.isNotEmpty() && it !in setA }
        val similarity = if (setA.size + setB.size == 0) 1.0
        else (2.0 * (setA.size - onlyA.size)) / (setA.size + setB.size)

        val safeMaxLines = maxLines.coerceAtLeast(1)
        return buildList {
            add("similarity ${"%.1f".format(similarity * 100)}% · a ${aLines.size} lines · b ${bLines.size} lines")
            if (headerDiff.isNotBlank()) add(headerDiff)
            if (onlyA.isEmpty() && onlyB.isEmpty()) add("bodies are line-identical")
            add("--- only in a ---")
            onlyA.take(safeMaxLines).forEach { add("- ${it.take(400)}") }
            add("--- only in b ---")
            onlyB.take(safeMaxLines).forEach { add("+ ${it.take(400)}") }
            if (onlyA.size + onlyB.size > safeMaxLines) add("(truncated at $safeMaxLines changed lines)")
        }.joinToString("\n")
    }
}

object MutateEngine {
    val allRules = listOf("case", "url", "url2", "base64", "hex", "null", "ws", "terminator", "traversal", "truncate", "unicode")
    val allContexts = listOf("quote", "doublequote", "paren", "array", "json", "html", "sql", "xml", "header")

    data class Variant(val index: Int, val how: String, val value: String)

    fun generate(
        seed: String,
        rules: List<String> = listOf("case", "url", "null"),
        contexts: List<String> = emptyList(),
        traversalDepth: Int = 3,
        limit: Int = 40,
        seedValue: Int = 0x5eed
    ): Pair<Int, List<Variant>> {
        val safeDepth = traversalDepth.coerceIn(1, 20)
        val safeLimit = limit.coerceAtLeast(1)
        val rng = mulberry32(seedValue)
        val variants = linkedMapOf<String, String>()

        fun applyRules(input: String): List<String> = buildList {
            for (rule in rules) {
                when (rule) {
                    "case" -> {
                        add(input.uppercase())
                        add(input.lowercase())
                        add(input.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString(""))
                    }
                    "url" -> add(URLEncoder.encode(input, "UTF-8").replace("+", "%20"))
                    "url2" -> {
                        val once = URLEncoder.encode(input, "UTF-8").replace("+", "%20")
                        add(URLEncoder.encode(once, "UTF-8").replace("+", "%20"))
                    }
                    "base64" -> add(Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8)))
                    "hex" -> add(input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })
                    "null" -> add(input.replace(" ", "\u0000"))
                    "ws" -> {
                        add(input.replace(" ", "\t"))
                        add(input.replace(" ", "/**/"))
                        add(input.replace(Regex(" +"), "%20"))
                    }
                    "terminator" -> listOf("'", "\"", "--", "#", ";", "-- -", "/*", "\u0000").forEach { add(input + it) }
                    "traversal" -> {
                        for (i in 1..safeDepth) add("../".repeat(i) + input)
                        for (i in 1..safeDepth) add("..%2f".repeat(i) + input)
                    }
                    "truncate" -> {
                        add(input.take((input.length / 2).coerceAtLeast(1)))
                        add(input.take(1))
                    }
                    "unicode" -> add(buildString {
                        var i = 0
                        while (i < input.length) {
                            val cp = input.codePointAt(i)
                            if (cp > 127) append("%u${cp.toString(16)}") else appendCodePoint(cp)
                            i += Character.charCount(cp)
                        }
                    })
                    else -> add(input)
                }
            }
        }

        for (v in applyRules(seed)) variants.putIfAbsent(v, "rule")
        for (ctxName in contexts) {
            val wrapped = wrap(seed, ctxName)
            variants.putIfAbsent(wrapped, "context:$ctxName")
            for (v in applyRules(wrapped)) variants.putIfAbsent(v, "$ctxName+rule")
        }

        val rngNum = (rng() * 1_000_000).toInt()
        val list = variants.entries.take(safeLimit).mapIndexed { idx, (value, how) ->
            Variant(idx + 1, how, value)
        }
        return rngNum to list
    }

    private fun wrap(seed: String, context: String): String = when (context) {
        "quote" -> "'$seed'"
        "doublequote" -> "\"$seed\""
        "paren" -> "($seed)"
        "array" -> "[$seed]"
        "json" -> "{\"k\":\"$seed\"}"
        "html" -> "<div>$seed</div>"
        "sql" -> "' OR $seed --"
        "xml" -> "<?xml version=\"1.0\"?><x>$seed</x>"
        "header" -> "X-Test: $seed"
        else -> seed
    }

    private fun mulberry32(initial: Int): () -> Double {
        var a = initial
        return {
            a += 0x6d2b79f5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            ((t xor (t ushr 14)).toUInt().toDouble()) / 4294967296.0
        }
    }
}

val transformTool = object : Tool {
    override val name = "transform"
    override val description =
        "Local encode/decode/hash/transform utilities. op = url|urldecode|base64|unbase64|hex|unhex|hash|hmac|jwt|gzip|gunzip|html|htmlunescape|unicode|case|json|charset. Nothing leaves the machine."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "op" to schemaProp("string"),
            "input" to schemaProp("string"),
            "input_hex" to schemaProp("string"),
            "algorithm" to schemaProp("string", "hash/hmac: md5|sha1|sha256|sha512"),
            "key" to schemaProp("string", "hmac key"),
            "offset" to schemaProp("number", "caesar/case helper offset")
        ),
        required = listOf("op", "input")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val op = (args["op"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val input = (args["input"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val inputHex = (args["input_hex"] as? JsonPrimitive)?.contentOrNull
        val algo = (args["algorithm"] as? JsonPrimitive)?.contentOrNull ?: "sha256"
        val key = (args["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val offset = (args["offset"] as? JsonPrimitive)?.intOrNull ?: 0

        return TransformEngine.run(op, input, inputHex, algo, key, offset).fold(
            onSuccess = { ToolResult(it) },
            onFailure = { ToolResult("error: ${it.message}", isError = true) }
        )
    }
}

val diffTool = object : Tool {
    override val name = "diff"
    override val description =
        "Structural comparison of two responses, files or artifacts: status line, header set differences, body similarity and the changed lines."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "a" to schemaProp("string", "text, or artifact id when a_id is true"),
            "b" to schemaProp("string"),
            "a_id" to schemaProp("boolean"),
            "b_id" to schemaProp("boolean"),
            "ignore_whitespace" to schemaProp("boolean"),
            "max_lines" to schemaProp("number", "default 200")
        ),
        required = listOf("a", "b")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        fun load(value: String, isId: Boolean): String {
            if (!isId) return value
            return ctx.artifacts.read(value) ?: error("unknown artifact $value")
        }
        val a = runCatching { load((args["a"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (args["a_id"] as? JsonPrimitive)?.booleanOrNull == true) }
            .getOrElse { return ToolResult("error: ${it.message}", isError = true) }
        val b = runCatching { load((args["b"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (args["b_id"] as? JsonPrimitive)?.booleanOrNull == true) }
            .getOrElse { return ToolResult("error: ${it.message}", isError = true) }
        val ignoreWs = (args["ignore_whitespace"] as? JsonPrimitive)?.booleanOrNull == true
        val maxLines = (args["max_lines"] as? JsonPrimitive)?.intOrNull ?: 200
        return ToolResult(DiffEngine.compare(a, b, ignoreWs, maxLines))
    }
}

val mutateTool = object : Tool {
    override val name = "mutate"
    override val description =
        "Deterministic payload variant generator. Give a seed payload and rules/contexts to get numbered variants."
    override val parameters: JsonObject = schemaObject(
        properties = mapOf(
            "seed" to schemaProp("string"),
            "rules" to schemaProp("array", "case|url|url2|base64|hex|null|ws|terminator|traversal|truncate|unicode", "string"),
            "contexts" to schemaProp("array", "quote|doublequote|paren|array|json|html|sql|xml|header", "string"),
            "traversal_depth" to schemaProp("number"),
            "limit" to schemaProp("number", "max variants (default 40)"),
            "seed_value" to schemaProp("number", "rng seed")
        ),
        required = listOf("seed")
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val seed = (args["seed"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val rules = (args["rules"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: listOf("case", "url", "null")
        val contexts = (args["contexts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val depth = (args["traversal_depth"] as? JsonPrimitive)?.intOrNull ?: 3
        val limit = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 40
        val seedVal = (args["seed_value"] as? JsonPrimitive)?.intOrNull ?: 0x5eed

        val (rngNum, variants) = MutateEngine.generate(seed, rules, contexts, depth, limit, seedVal)
        val header = "seed: \"$seed\" · rules: ${rules.joinToString(",")} · contexts: ${contexts.joinToString(",").ifBlank { "-" }} · ${variants.size} variants (rng $rngNum)"
        val lines = variants.map { "${it.index}. (${it.how}) \"${it.value.replace("\"", "\\\"")}\"" }
        return ToolResult((listOf(header) + lines).joinToString("\n"))
    }
}
