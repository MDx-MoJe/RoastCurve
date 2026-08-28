package com.roastcurve.app.monitor

import com.roastcurve.shared.l10n.L10n
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roastcurve.design.DarkRoast
import com.roastcurve.design.WarmBeige
import com.roastcurve.shared.model.EventMarker
import com.roastcurve.shared.model.RoastEvent

/** 秒 → M:SS 格式 */
internal fun fmtMMSS(sec: Float): String {
    if (sec < 0f) return "--:--"
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

/** 阶段统计：一段的时长、占全程百分比 */
private data class PhaseStat(
    val label: String,
    val startSec: Float?,
    val endSec: Float?,
    val color: Color,
)

/**
 * 烘焙阶段统计卡（Artisan 式三段分析）
 *
 * 脱水段 = 入豆→黄点；美拉德段 = 黄点→一爆；发展段 = 一爆→出豆(或当前时刻)
 * 百分比 = 该段时长 / 全程（未出豆时以当前时刻为终点，实时变化）
 *
 * @param events 已标记的事件列表
 * @param currentElapsedSec 当前计时秒数（用于实时计算；出豆后应传冻结值）
 */
@Composable
internal fun RoastPhaseStats(
    events: List<EventMarker>,
    currentElapsedSec: Float,
    modifier: Modifier = Modifier,
) {
    val chargeT = events.filter { it.event == RoastEvent.CHARGE }.firstOrNull()?.timeSeconds ?: 0f
    val dryT = events.filter { it.event == RoastEvent.DRY }.firstOrNull()?.timeSeconds
    val fcsT = events.filter { it.event == RoastEvent.FCs }.firstOrNull()?.timeSeconds
    val dropT = events.filter { it.event == RoastEvent.DROP }.firstOrNull()?.timeSeconds

    // 至少标记了黄点或一爆才显示本卡
    if (dryT == null && fcsT == null) return

    // 全程终点：出豆后冻结在出豆时刻，否则跟随当前计时
    val totalEnd = dropT ?: currentElapsedSec.coerceAtLeast(0f)
    if (totalEnd <= 0f) return

    fun pct(from: Float?, to: Float?): String {
        if (from == null || to == null || to <= from) return "--"
        val p = (to - from) / (totalEnd - chargeT) * 100f
        return "${p.toInt()}%"
    }

    fun dur(from: Float?, to: Float?): String =
        if (from == null || to == null || to < from) "--:--" else fmtMMSS(to - from)

    val palette = if (isSystemInDarkTheme()) DarkRoast else WarmBeige
    val phases = listOf(
        PhaseStat(L10n.get("phase.drying"), chargeT, dryT, palette.PhaseDryingAccent),
        PhaseStat(L10n.get("phase.maillard"), dryT, fcsT, palette.PhaseMaillardAccent),
        PhaseStat(L10n.get("phase.development"), fcsT, dropT, palette.PhaseDevelopmentAccent),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(L10n.get("stats.s1"), style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 一爆后右侧实时显示发展时间与率；平时显示全程
                val devLive = fcsT != null && dropT == null && currentElapsedSec >= fcsT
                if (devLive) {
                    Text(L10n.get("stats.s2"),
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(L10n.get("stats.s3"),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                phases.forEach { ph ->
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            // 色点与图表背景带同源，建立视觉关联
                            Box(
                                Modifier.padding(end = 4.dp)
                                    .background(ph.color, RoundedCornerShape(50))
                                    .size(8.dp)
                            ) {}
                            Text(ph.label, fontSize = 12.sp,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(dur(ph.startSec, ph.endSec), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(1.dp))
                        Text(pct(ph.startSec, ph.endSec), fontSize = 12.sp, color = ph.color)
                    }
                }
            }
        }
    }
}
