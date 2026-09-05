package com.roastcurve.app.manual

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import roastcurve.composeapp.generated.resources.Res
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 完整用户指导手册（内置版）
 * 读取打包在 resources/files/manual/ 的 manual.md（Markdown 子集），轻量渲染。
 * 支持：# 标题 / | 表格 | / - 列表 / 引用 / **粗体** / 图片 / 代码块 / 分隔线
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun FullManualScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var markdown by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val images = remember { mutableMapOf<String, ImageBitmap>() }

    // 加载手册文本
    LaunchedEffect(Unit) {
        try {
            val bytes = Res.readBytes("files/manual/manual.md")
            markdown = bytes.decodeToString()
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    fun loadImage(name: String) {
        if (images.containsKey(name)) return
        scope.launch {
            try {
                val b = Res.readBytes("files/manual/$name")
                images[name] = b.decodeToImageBitmap()
            } catch (_: Exception) {
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "完整用户指导手册",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
            )
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        HorizontalDivider()

        when {
            loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("手册加载失败", color = MaterialTheme.colorScheme.error)
            }
            markdown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                val blocks = remember(markdown) { MarkdownParser.parse(markdown!!) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(blocks.size) { i ->
                        val b = blocks[i]
                        when (b) {
                            is MdBlock.Heading -> Text(
                                b.text,
                                style = when (b.level) {
                                    1 -> MaterialTheme.typography.headlineSmall
                                    2 -> MaterialTheme.typography.titleLarge
                                    else -> MaterialTheme.typography.titleMedium
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = if (b.level <= 2) 10.dp else 4.dp),
                            )
                            is MdBlock.Paragraph -> Text(
                                MdRender.inline(b.text),
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 22.sp,
                            )
                            is MdBlock.ListItem -> Row(Modifier.padding(start = 6.dp)) {
                                Text("•  ", color = MaterialTheme.colorScheme.primary)
                                Text(
                                    MdRender.inline(b.text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 21.sp,
                                )
                            }
                            is MdBlock.Quote -> Text(
                                MdRender.inline(b.text),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(10.dp),
                            )
                            is MdBlock.Table -> MdTable(b.rows)
                            is MdBlock.ImageRef -> {
                                loadImage(b.file)
                                val img = images[b.file]
                                if (img != null) {
                                    Image(
                                        bitmap = img,
                                        contentDescription = b.alt,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            is MdBlock.Code -> Text(
                                b.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(10.dp),
                            )
                            is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Markdown 行内格式化：**粗体** / `代码` / 链接 */
object MdRender {
    fun inline(raw: String): String {
        var s = raw
        // 链接 [text](url) → text
        s = Regex("\\[([^\\]]+)]\\([^)]+\\)").replace(s) { it.groupValues[1] }
        // 粗体 **x** → x
        s = s.replace("**", "")
        // 行内代码 `x` → x
        s = s.replace("`", "")
        return s
    }
}

/** Markdown 块解析（子集） */
object MarkdownParser {
    fun parse(md: String): List<MdBlock> {
        val lines = md.split("\n")
        val out = mutableListOf<MdBlock>()
        var i = 0
        val n = lines.size
        while (i < n) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val t = line.trim()

            when {
                // 代码块
                t.startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < n && !lines[i].trimStart().startsWith("```")) {
                        buf.append(lines[i]).append("\n")
                        i++
                    }
                    out.add(MdBlock.Code(buf.toString().trimEnd()))
                    i++
                }
                // 标题
                t.startsWith("#") && (t.length < 2 || t[1] == ' ' || t[1] == '#') -> {
                    val m = Regex("^(#{1,4})\\s+(.*)").find(t)
                    if (m != null) out.add(MdBlock.Heading(m.groupValues[2], m.groupValues[1].length))
                    i++
                }
                // 分隔线
                t == "---" || t == "***" -> { out.add(MdBlock.Rule); i++ }
                // 图片 ![](file)
                t.startsWith("![") -> {
                    val m = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)").find(t)
                    if (m != null) out.add(MdBlock.ImageRef(m.groupValues[1], m.groupValues[2]))
                    i++
                }
                // 表格行（含表头+分隔+数据行聚合）
                t.startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < n && lines[i].trim().startsWith("|")) {
                        val cells = parseRow(lines[i])
                        // 跳过 |---| 分隔行
                        if (!cells.all { it.trim().matches(Regex(":?-{2,}:?")) }) rows.add(cells)
                        i++
                    }
                    if (rows.size > 1) out.add(MdBlock.Table(rows))
                }
                // 引用
                t.startsWith(">") -> {
                    val buf = StringBuilder()
                    while (i < n && lines[i].trimStart().startsWith(">")) {
                        if (buf.isNotEmpty()) buf.append("\n")
                        buf.append(lines[i].trim().removePrefix(">").trim())
                        i++
                    }
                    out.add(MdBlock.Quote(buf.toString()))
                }
                // 列表
                Regex("^[-*]\\s+").containsMatchIn(t) || Regex("^\\d+\\.\\s+").containsMatchIn(t) -> {
                    out.add(MdBlock.ListItem(Regex("^[-*]\\s+|^\\d+\\.\\s+").replace(t, "")))
                    i++
                }
                // 空行
                t.isEmpty() -> { i++ }
                // 普通段落（聚合连续行）
                else -> {
                    val buf = StringBuilder(t)
                    i++
                    while (i < n) {
                        val nx = lines[i].trim()
                        if (nx.isEmpty() || nx.startsWith("#") || nx.startsWith("|") ||
                            nx.startsWith("```") || nx.startsWith("![") || nx.startsWith(">") ||
                            Regex("^[-*]\\s+|^\\d+\\.\\s+").containsMatchIn(nx) || nx == "---"
                        ) break
                        buf.append(" ").append(nx)
                        i++
                    }
                    out.add(MdBlock.Paragraph(buf.toString()))
                }
            }
        }
        return out
    }

    private fun parseRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }
}

sealed class MdBlock {
    data class Heading(val text: String, val level: Int) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    data class ImageRef(val alt: String, val file: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    object Rule : MdBlock()
}

/** 简单表格渲染（横向可滚动） */
@Composable
private fun MdTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(6.dp)
    ) {
        rows.forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until cols) {
                    val cell = row.getOrElse(c) { "" }
                    Text(
                        MdRender.inline(cell),
                        fontSize = 11.sp,
                        fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (ri == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
