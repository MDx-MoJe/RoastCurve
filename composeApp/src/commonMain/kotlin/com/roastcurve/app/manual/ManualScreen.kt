package com.roastcurve.app.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 内置使用手册：结构化展示烘焙流程、事件术语、设置说明与注意事项。
 * 内容与 docs/使用手册.md 对齐，App 内随时可查。
 */
@Composable
fun ManualScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("使用手册", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("一、两种烘焙模式") {
                Bullet("手动：你控制 SV（设定温度），±5 按钮随时手调火力，传统用法。")
                Bullet("跟随曲线：自动模式，每 2 秒按模板覆盖 SV，温控器自己跟着目标曲线跑。")
                Bullet("跟随时 SV 是「只读」的，控制器持续覆盖它。")
                Bullet("±5 按钮在跟随时只有一个作用：一键接管——按下即退出跟随、切回手动。")
            } }

            item { Section("二、连接与记录状态") {
                Bullet("未连接：只能看设置和历史，没有开始/停止按钮。")
                Bullet("已连接 · 待命：实时豆温照常显示，但不画曲线、不走计时。")
                Bullet("已连接 · 记录中：曲线绘制、计时走动。")
                Para("「开始」= 连上后手动起表；「停止」= 断开并把曲线定稿保存。开启「入豆自动开始跟随」则投豆瞬间点「入豆」隐式起表。")
                Para("信号强度：连接后顶部显示彩色圆点 + dBm 值（绿强/黄中/橙弱/红极弱）。烘前若为橙/红，先调整桥接器位置或天线再开烘，避免中途掉线。")
            } }

            item { Section("三、完整烘焙流程") {
                Step("准备", "给温控器和 Wi-Fi 转 RS485 设备通电，打开 App 连接（可在设置开启自动连接）。需预热时用 ±5 把 SV 设到预热温度。")
                Step("入豆（计时起点）", "投豆瞬间点「入豆」：清空预热段、计时归零、记录入豆时刻豆温。若在待命态，入豆会自动开始正式记录。")
                Step("选模板跟随（可选）", "点「跟随曲线」→ 选一炉（仅选中不立刻跟随）。入豆时若开着「入豆自动开始跟随」则自动进入跟随。图表上米灰虚线=目标曲线，橙色实线=实际豆温。")
                Step("烘焙过程", "点事件标记即可，全程不用碰 SV：黄点（转黄）、一爆、一爆止。阶段卡自动算脱水/美拉德/发展三段时长与占比。")
                Step("出豆与收尾", "点「出豆」：SV 自动降到 25°C 进入冷却并立即存快照。冷却完成后点「停止」最终定稿。")
            } }

            item { Section("四、记录保存机制") {
                Bullet("连接期间每 30 秒自动存草稿。")
                Bullet("点「出豆」立即存一版快照。")
                Bullet("停止/断开时最终定稿。")
                Para("同一炉所有保存都是同一条记录的覆盖更新。App 崩溃或意外断连最多丢最近 30 秒数据；进程被杀后重开会自动恢复未完成的会话。")
            } }

            item { Section("五、创建烘焙模板") {
                Sub("方式一：从历史记录提取")
                Bullet("记录 → 选一炉满意的记录 → 右上角「存为模板」。该炉曲线（出豆处截断）即成为可回放模板。")
                Sub("方式二：导入 Artisan 曲线")
                Bullet("监控页 →「跟随曲线」→「导入」→ 选择 Artisan 导出的 .alog 文件。自动解析豆温曲线，若文件自带 computed 事件（入豆/脱水/一爆/出豆的时间与温度）会直接读取为锚点。")
                Sub("方式三：自定义锚点绘制")
                Bullet("「+ 新建自定义模板」，点空白加锚点、长按拖动调位置，列表里可精确改时间/温度。")
            } }

            item { Section("六、设置项说明") {
                SettingRow("入豆自动开始跟随", "入豆瞬间若已选模板，自动进入跟随模式")
                SettingRow("启动时自动连接", "打开 App 自动连上次的桥接器 IP")
                SettingRow("跟随前瞻", "SV 提前参考 N 秒后的目标值，补偿炉子热惯性（0=关闭）")
                SettingRow("深烘模式（显示二爆）", "事件条增加二爆/二爆止两键，意式深烘用")
            } }

            item { Section("七、事件术语速查") {
                EventRow("入豆 CHARGE", "生豆下锅，计时起点")
                EventRow("黄点 DRY", "豆色转黄，脱水阶段结束")
                EventRow("一爆 FCs", "第一声爆裂，发展期开始")
                EventRow("一爆止 FCe", "一爆爆裂声结束")
                EventRow("二爆 SCs", "第二爆开始（深烘区间）")
                EventRow("二爆止 SCe", "第二爆结束（深度烘焙极限区）")
                EventRow("出豆 DROP", "下豆，记录终点")
            } }

            item { Section("八、注意事项") {
                Bullet("空载测试：空锅跟随带豆模板，BT 会升得比模板快、偏高，属正常物理差异。")
                Bullet("总线上单主站：接自制桥接器时原 Wi-Fi 转 RS485 设备需断电，两个主站不能同时说话。")
                Bullet("SV 上限 260°C：适配特殊豆种，超出会被硬限幅。")
                Bullet("返回键：浮层页面（记录/详情/设置/手册）按返回=关闭浮层；烘焙会话进行中返回键被屏蔽防误触退出。")
                Bullet("失重率：典型 12%~18%，超出 8%~25% 区间会提示复查克重。")
            } }

            item { Section("九、开源协议") {
                Bullet("本项目（App + ESP32 固件）以 Apache License 2.0 开源，源码见 GitHub 仓库。")
                Bullet("Copyright © 2026 MDx")
            } }
        }
    }
}

// ===== 内部渲染组件 =====

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Sub(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)) {
        Text("· ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun Step(name: String, desc: String) {
    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingRow(name: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.6f))
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun EventRow(name: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f))
    }
    Spacer(Modifier.height(2.dp))
}
