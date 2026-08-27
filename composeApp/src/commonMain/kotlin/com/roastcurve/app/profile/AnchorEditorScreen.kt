package com.roastcurve.app.profile

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.design.DarkRoast
import com.roastcurve.design.WarmBeige
import com.roastcurve.shared.math.RoastMath
import com.roastcurve.shared.model.AnchorPoint
import com.roastcurve.shared.model.RoastProfile
import com.roastcurve.shared.storage.ProfileStore
import com.roastcurve.shared.storage.RoastStore
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

/**
 * 自定义锚点曲线编辑器
 *
 * 上半屏实时预览（锚点→单调三次插值曲线），下半屏锚点列表编辑。
 * 保存为标准 RoastProfile：points 由 anchors 插值生成，
 * 跟随控制器 / 图表 / 选择器零改动直接复用。
 *
 * @param initial 传 null 为新建；传自定义模板则回炉编辑（历史提取模板不进这里）
 */
@Composable
fun AnchorEditorScreen(
    initial: RoastProfile?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isEdit = initial != null

    var name by remember {
        mutableStateOf(initial?.name ?: defaultName())
    }
    val anchors = remember {
        mutableStateListOf<AnchorPoint>().apply {
            (initial?.anchors ?: emptyList()).let { base ->
                if (base.isNotEmpty()) addAll(base)
                else {
                    // 新建默认给三个起步锚点，用户改数值即可
                    add(AnchorPoint(timeSeconds = 0f, bt = 25f, label = "入豆"))
                    add(AnchorPoint(timeSeconds = 300f, bt = 150f, label = guessStage(150f)))
                    add(AnchorPoint(timeSeconds = 600f, bt = 200f, label = guessStage(200f)))
                }
            }
        }
    }
    var saveMsg by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (isEdit) "编辑模板" else "新建模板", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 模板名 =====
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("模板名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // ===== 实时预览 =====
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
            Column(Modifier.padding(10.dp)) {
                // 拖拽会话：跟踪当前工作副本，避免 copy 替换后引用失联
                var dragSession by remember { mutableStateOf<AnchorPoint?>(null) }
                ProfilePreviewCanvas(
                    anchors = anchors.toList(),
                    onAdd = { t, b -> anchors.add(AnchorPoint(timeSeconds = t, bt = b, label = guessStage(b))) },
                    onMove = { target, t, b ->
                        val cur = dragSession ?: target.also { dragSession = it }
                        val i = anchors.indexOfFirst { it === cur }
                        if (i >= 0) {
                            val np = cur.copy(timeSeconds = t, bt = b)
                            anchors[i] = np
                            dragSession = np
                        } else {
                            dragSession = null   // 会话失效，下次事件重新建立
                        }
                    },
                    onDragEndReset = { dragSession = null },
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Color(0xFFC05A2E), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("目标曲线", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                    Spacer(Modifier.width(4.dp))
                    Text("锚点", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 锚点列表 =====
        Text("锚点 · 预览图上点空白加锚点、长按拖动调位置", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        val sortedIdx = anchors.withIndex().sortedBy { it.value.timeSeconds }.toList()
        sortedIdx.forEach { (origIdx, p) ->
            AnchorRow(
                point = p,
                onUpdate = { np -> anchors[origIdx] = np },
                onDelete = { if (anchors.size > 2) anchors.removeAt(origIdx) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("时间单位为秒：9分30秒 填 570", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val last = anchors.maxByOrNull { it.timeSeconds }
                val t = (last?.timeSeconds ?: 0f) + 30f
                val bt = ((last?.bt ?: 25f) + 8f).coerceAtMost(260f)
                anchors.add(AnchorPoint(timeSeconds = t, bt = bt, label = guessStage(bt)))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("+ 添加锚点") }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val valid = anchors.filter { it.timeSeconds >= 0f && it.bt > 0f }
                if (valid.size < 2) return@Button
                scope.launch {
                    ProfileStore().save(
                        RoastProfile(
                            id = initial?.id ?: RoastStore.newId(kotlinx.datetime.Clock.System.now().toEpochMilliseconds()),
                            name = name.ifBlank { defaultName() },
                            sourceRecordId = initial?.sourceRecordId ?: "",
                            points = RoastMath.anchorsToPoints(valid),
                            anchors = valid.sortedBy { it.timeSeconds },
                        )
                    )
                    saveMsg = true
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (isEdit) "保存修改" else "保存模板", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }

        if (saveMsg) {
            Spacer(Modifier.height(6.dp))
            Text("已保存 ✓", color = MaterialTheme.colorScheme.primary,
                 style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(120.dp))   // 给底部手势条留白
    }
}

/** 单个锚点编辑行：名称 + 时间(秒) + 温度(°C) + 删除 */
@Composable
private fun AnchorRow(
    point: AnchorPoint,
    onUpdate: (AnchorPoint) -> Unit,
    onDelete: () -> Unit,
) {
    // 文本框内容跟随数据，但允许中间态自由输入：
    // 用本地状态托管字符串，外部数据变化时同步
    var lText by remember(point.label) { mutableStateOf(point.label) }
    var tText by remember(point.timeSeconds.toInt()) { mutableStateOf(formatSec(point.timeSeconds)) }
    var cText by remember(point.bt.toInt()) { mutableStateOf(point.bt.toInt().toString()) }

    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = lText,
                onValueChange = { s ->
                    lText = s
                    onUpdate(point.copy(label = s.take(6)))
                },
                label = { Text("名称") },
                placeholder = { Text(guessStage(point.bt), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = tText,
                onValueChange = { s ->
                    tText = s
                    parseNum(s)?.let { v -> onUpdate(point.copy(timeSeconds = v)) }
                },
                label = { Text("秒") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            )
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = cText,
                onValueChange = { s ->
                    cText = s
                    parseNum(s)?.let { v -> onUpdate(point.copy(bt = v.coerceIn(0f, 500f))) }
                },
                label = { Text("°C") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            )
            TextButton(onClick = onDelete, enabled = true) {
                Text("删", color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 按温度猜阶段名，作新锚点的默认名 */
private fun guessStage(bt: Float): String = when {
    bt < 60f -> "入豆"
    bt < 160f -> "脱水"
    bt < 190f -> "美拉德"
    bt < 205f -> "一爆"
    else -> "发展"
}

/**
 * 预览画布 v2：可交互
 * - 点空白处：在该位置新增锚点
 * - 长按锚点后拖动：实时移动位置
 * - 视口在拖动期间冻结，避免坐标漂移抖动
 */
@Composable
private fun ProfilePreviewCanvas(
    anchors: List<AnchorPoint>,
    onAdd: (Float, Float) -> Unit,
    onMove: (AnchorPoint, Float, Float) -> Unit,
    onDragEndReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val curveColor = Color(0xFFC05A2E)
    val anchorColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragTarget by remember { mutableStateOf<AnchorPoint?>(null) }
    var frozenPV by remember { mutableStateOf<PV?>(null) }

    // 手势闭包里读到的永远是最新值（检测器 key 固定，不随数据重启）
    val curAnchors by rememberUpdatedState(anchors)
    val curOnAdd by rememberUpdatedState(onAdd)
    val curOnMove by rememberUpdatedState(onMove)
    val curOnEnd by rememberUpdatedState(onDragEndReset)

    fun buildPV(a: List<AnchorPoint>): PV {
        val xMax = ((a.maxOfOrNull { it.timeSeconds } ?: 300f) * 1.06f).coerceAtLeast(300f)
        val yMax = ((a.maxOfOrNull { it.bt } ?: 100f) * 1.15f).coerceAtLeast(100f)
        return PV(canvasSize.width.toFloat(), canvasSize.height.toFloat(), xMax, yMax)
    }
    fun currentPV(): PV? =
        frozenPV ?: if (canvasSize == IntSize.Zero) null else buildPV(curAnchors)

    fun nearest(off: Offset): Pair<AnchorPoint, Float>? {
        val pv = currentPV() ?: return null
        var best: AnchorPoint? = null; var bestD = 48f * 48f
        for (p in curAnchors) {
            val dx = pv.px(p.timeSeconds) - off.x
            val dy = pv.py(p.bt) - off.y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p }
        }
        return best?.let { it to bestD }
    }

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val pv = currentPV() ?: return@detectTapGestures
                    if (nearest(off) != null) return@detectTapGestures   // 点在已有锚点上不加
                    if (off.x < PV.PADL || off.x > pv.w - PV.PADR) return@detectTapGestures
                    val t = ((pv.tx(off.x) / 5f).toInt().coerceAtLeast(0)) * 5f
                    val b = pv.vy(off.y).coerceIn(20f, 400f)
                    curOnAdd(t, kotlin.math.round(b))
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { off ->
                        nearest(off)?.let { (p, _) ->
                            dragTarget = p
                            frozenPV = currentPV()
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val tgt = dragTarget ?: return@detectDragGesturesAfterLongPress
                        val pv = currentPV() ?: return@detectDragGesturesAfterLongPress
                        val t = pv.tx(change.position.x).coerceAtLeast(0f)
                        val b = pv.vy(change.position.y).coerceIn(20f, 400f)
                        curOnMove(tgt, kotlin.math.round(t), kotlin.math.round(b))
                    },
                    onDragEnd = { dragTarget = null; frozenPV = null; curOnEnd() },
                    onDragCancel = { dragTarget = null; frozenPV = null; curOnEnd() },
                )
            },
    ) {
        val a = curAnchors.sortedBy { it.timeSeconds }
        val pv = currentPV() ?: return@Canvas
        val w = size.width; val h = size.height

        // 网格
        var gy = 0f
        while (gy <= pv.yMax) {
            drawLine(gridColor, Offset(PV.PADL, pv.py(gy)), Offset(w - PV.PADR, pv.py(gy)), 1f)
            gy += 50f
        }
        var gx = 0f
        while (gx <= pv.xMax) {
            drawLine(gridColor, Offset(pv.px(gx), PV.PADT), Offset(pv.px(gx), h - PV.PADB), 1f)
            gx += 60f
        }

        // 插值曲线
        val pts = RoastMath.anchorsToPoints(a)
        if (pts.size >= 2) {
            val path = Path()
            pts.forEachIndexed { i, p ->
                val o = Offset(pv.px(p.timeSeconds), pv.py(p.bt))
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, curveColor, style = Stroke(width = 3f))
        }

        // 锚点 + 名称（拖拽中的放大高亮）
        a.forEachIndexed { i, p ->
            val c = Offset(pv.px(p.timeSeconds), pv.py(p.bt))
            val dragging = p === dragTarget
            drawCircle(anchorColor, radius = if (dragging) 10f else 7f, center = c)
            drawCircle(Color.White, radius = if (dragging) 4f else 3f, center = c)
            val tm = (p.timeSeconds / 60).toInt()
            val ts = (p.timeSeconds % 60).toInt()
            val text = buildString {
                if (p.label.isNotBlank()) { append(p.label); append(" ") }
                append("$tm:${ts.toString().padStart(2, '0')} ${p.bt.toInt()}°")
            }
            val label = measurer.measure(text, TextStyle(fontSize = 10.sp, color = textColor))
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, (c.y - 26f).coerceAtLeast(0f)))
        }
    }
}

/** 预览视口参数与像素↔数据换算 */
private data class PV(val w: Float, val h: Float, val xMax: Float, val yMax: Float) {
    companion object {
        const val PADL = 34f; const val PADR = 12f; const val PADT = 18f; const val PADB = 22f
    }
    private val plotW get() = w - PADL - PADR
    private val plotH get() = h - PADT - PADB
    fun px(t: Float) = PADL + plotW * (t / xMax).coerceIn(0f, 1f)
    fun py(bt: Float) = h - PADB - plotH * (bt / yMax).coerceIn(0f, 1f)
    fun tx(x: Float) = ((x - PADL) / plotW).coerceIn(0f, 1f) * xMax
    fun vy(y: Float) = ((h - PADB - y) / plotH).coerceIn(0f, 1f) * yMax
}


private fun formatSec(sec: Float): String = sec.toInt().toString()

/** 解析正数；非法输入返回 null（保留中间态不强制写回） */
private fun parseNum(s: String): Float? =
    s.trim().toFloatOrNull()?.takeIf { it >= 0f }

private fun defaultName(): String {
    val d = kotlinx.datetime.Clock.System.now()
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "自定义模板 ${p2(d.monthNumber)}${p2(d.dayOfMonth)}-${p2(d.hour)}${p2(d.minute)}"
}
