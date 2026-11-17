package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object ToolSummaryFormatter {
    private val json = Json { ignoreUnknownKeys = true }

    fun summarize(name: String, rawArguments: String): String {
        val obj = runCatching { json.parseToJsonElement(rawArguments) as? JsonObject }.getOrNull()
        if (obj == null) {
            return rawArguments.replace('\n', ' ').trim().take(48)
        }
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }

        val summary = when (name) {
            "http" -> {
                val method = str("method")?.uppercase() ?: "GET"
                val url = str("url", "target")
                if (url != null) "$method $url" else method
            }
            "web_fetch" -> str("url").orEmpty()
            "web_search" -> str("query").orEmpty()
            "net" -> {
                val host = str("host", "target")
                val port = str("port")
                when {
                    host != null && port != null -> "$host:$port"
                    host != null -> host
                    else -> str("action", "mode").orEmpty()
                }
            }
            "bash" -> str("command", "cmd").orEmpty()
            "read", "write", "edit", "ls" -> str("path", "file", "dir").orEmpty()
            "glob", "grep" -> {
                val pat = str("pattern", "query")
                val path = str("path")
                listOfNotNull(pat, path).joinToString(" in ")
            }
            "notes" -> listOfNotNull(str("action"), str("kind"), str("key")).joinToString(" · ")
            "report" -> listOfNotNull(str("action"), str("severity"), str("title")).joinToString(" · ")
            "verify" -> str("command", "target", "url").orEmpty()
            "learn" -> listOfNotNull(str("action"), str("host", "situation", "fingerprint")).joinToString(" · ")
            "recall" -> listOfNotNull(str("action"), str("id"), str("pattern")).joinToString(" · ")
            "transform" -> listOfNotNull(str("op", "operation"), str("input")?.take(24)).joinToString(" · ")
            "mutate" -> {
                val rules = (obj["rules"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                    ?.joinToString(",")
                    ?.takeIf { it.isNotEmpty() }
                listOfNotNull(str("seed", "payload")?.take(24), rules ?: str("kind", "context")).joinToString(" · ")
            }
            "diff" -> listOfNotNull(str("a")?.take(20), str("b")?.take(20)).joinToString(" vs ").ifBlank { str("mode").orEmpty() }
            "scope" -> listOfNotNull(str("action"), str("host", "target", "url")).joinToString(" · ")
            "job" -> listOfNotNull(str("action"), str("id"), str("command")).joinToString(" · ")
            "session" -> listOfNotNull(str("action"), str("label")).joinToString(" · ")
            "skill" -> str("name", "action").orEmpty()
            "todo" -> listOfNotNull(str("action"), str("text", "id", "item", "task")).joinToString(" · ")
            "apply_patch" -> {
                val patch = str("patch").orEmpty()
                val fileLine = patch.lineSequence()
                    .map { it.trim() }
                    .firstOrNull {
                        it.startsWith("*** Update File:") ||
                            it.startsWith("*** Add File:") ||
                            it.startsWith("*** Delete File:") ||
                            it.startsWith("+++ ")
                    }
                    ?.removePrefix("*** Update File:")
                    ?.removePrefix("*** Add File:")
                    ?.removePrefix("*** Delete File:")
                    ?.removePrefix("+++ b/")
                    ?.removePrefix("+++ ")
                    ?.trim()
                fileLine ?: "workspace patch"
            }
            "task" -> listOfNotNull(str("role"), str("prompt")?.take(36)).joinToString(" · ")
            "propose_plan" -> str("title", "summary").orEmpty()
            "finish" -> str("outcome", "summary").orEmpty()
            "mcp_resource" -> listOfNotNull(str("action"), str("server"), str("uri")).joinToString(" · ")
            "mcp_prompt" -> listOfNotNull(str("action"), str("server"), str("name")).joinToString(" · ")
            else -> {
                val firstArg = obj.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
                if (name.startsWith("mcp__")) {
                    val parts = name.removePrefix("mcp__").split("__", limit = 2)
                    val label = if (parts.size == 2) "${parts[0]}/${parts[1]}" else name.removePrefix("mcp__")
                    listOfNotNull(label, firstArg.takeIf { it.isNotBlank() }).joinToString(" · ")
                } else {
                    firstArg
                }
            }
        }

        return summary.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(64)
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class NumberedItem(val index: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MdBlock>()
    val paraBuffer = mutableListOf<String>()

    fun flushPara() {
        if (paraBuffer.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraBuffer.joinToString("\n")))
            paraBuffer.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushPara()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushPara()
            i++
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushPara()
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        val headingMatch = Regex("^(#{1,3})\\s+(.+)$").matchEntire(trimmed)
        if (headingMatch != null) {
            flushPara()
            blocks.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushPara()
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                i++
            }
            blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
            continue
        }

        val bulletMatch = Regex("^[-*+]\\s+(.+)$").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushPara()
            blocks.add(MdBlock.BulletItem(bulletMatch.groupValues[1]))
            i++
            continue
        }

        val numMatch = Regex("^(\\d+)\\.\\s+(.+)$").matchEntire(trimmed)
        if (numMatch != null) {
            flushPara()
            blocks.add(MdBlock.NumberedItem(numMatch.groupValues[1], numMatch.groupValues[2]))
            i++
            continue
        }

        paraBuffer.add(line)
        i++
    }

    flushPara()
    return blocks
}

@Composable
fun MarkdownProse(
    text: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = true
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeFg = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.secondary

    val body: @Composable () -> Unit = {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = (if (selectable) Modifier else modifier).fillMaxWidth()
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge.copy(
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            2 -> MaterialTheme.typography.titleMedium.copy(
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            else -> MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = formatInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = style,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    is MdBlock.Paragraph -> {
                        Text(
                            text = formatInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    is MdBlock.BulletItem -> {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(start = 4.dp, end = 10.dp)
                            )
                            Text(
                                text = formatInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.NumberedItem -> {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${block.index}.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
                            )
                            Text(
                                text = formatInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = formatInlineMarkdown(block.text, codeBg, codeFg, linkColor),
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.Divider -> {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    is MdBlock.CodeBlock -> {
                        MarkdownCodeBlockCard(
                            language = block.language,
                            code = block.code
                        )
                    }
                }
            }
        }
    }

    if (selectable) {
        SelectionContainer(modifier = modifier) {
            body()
        }
    } else {
        body()
    }
}

@Composable
private fun MarkdownCodeBlockCard(
    language: String,
    code: String
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = language.ifBlank { "text" },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (copied) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code snippet", code))
                        copied = true
                    }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    lineHeight = 19.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private val inlineTokenRegex = Regex(
    "`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|\\*([^*\\n]+?)\\*|_([^_\\n]+?)_|\\[([^\\]]+)]\\(([^)]+)\\)"
)

private fun formatInlineMarkdown(
    text: String,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlineTokenRegex.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val inlineCode = match.groups[1]?.value
        val bold1 = match.groups[2]?.value
        val bold2 = match.groups[3]?.value
        val italic1 = match.groups[4]?.value
        val italic2 = match.groups[5]?.value
        val linkText = match.groups[6]?.value

        when {
            inlineCode != null -> {
                withStyle(
                    SpanStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.5.sp,
                        background = codeBg,
                        color = codeFg
                    )
                ) {
                    append(" $inlineCode ")
                }
            }
            bold1 != null || bold2 != null -> {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(bold1 ?: bold2.orEmpty())
                }
            }
            italic1 != null || italic2 != null -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italic1 ?: italic2.orEmpty())
                }
            }
            linkText != null -> {
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(linkText)
                }
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
