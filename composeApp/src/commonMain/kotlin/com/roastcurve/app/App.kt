package com.roastcurve.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.roastcurve.app.consent.ConsentConfig
import com.roastcurve.app.consent.ConsentDialog
import com.roastcurve.app.history.HistoryScreen
import com.roastcurve.app.history.RoastDetailScreen
import com.roastcurve.app.monitor.MonitorScreen
import com.roastcurve.app.manual.ManualScreen
import com.roastcurve.app.profile.AnchorEditorScreen
import com.roastcurve.app.platform.exitApplication
import com.roastcurve.app.settings.SettingsScreen
import com.roastcurve.app.settings.BleConfigScreen
import com.roastcurve.app.settings.ModbusConfigScreen
import com.roastcurve.app.settings.GpioConfigScreen
import com.roastcurve.design.RoastCurveTheme
import com.roastcurve.shared.model.RoastRecord
import com.roastcurve.shared.model.Settings
import com.roastcurve.shared.BackPressHook
import com.roastcurve.shared.storage.SettingsStore
import kotlinx.coroutines.launch

/** 简单导航状态（V1 不引入导航库） */
sealed interface Screen {
    data object Monitor : Screen
    data object History : Screen
    data object Settings : Screen
    data object Manual : Screen
    data object BleConfig : Screen
    data object ModbusConfig : Screen
    data object GpioConfig : Screen
    data class Detail(val record: RoastRecord) : Screen
    data class Editor(val profile: com.roastcurve.shared.model.RoastProfile?) : Screen
}

/**
 * 烤豆 (RoastCurve) 根组件
 */
@Composable
fun App() {
    RoastCurveTheme {
        Surface(modifier = Modifier) {
            var screen by remember { mutableStateOf<Screen>(Screen.Monitor) }
            // 配网成功后递增：通知常驻的监控页重新读 lastBridgeHost 并自动填 IP
            var hostRefreshKey by remember { mutableStateOf(0) }

            // 全局设置：启动加载，设置页改动即同步
            var settings by remember { mutableStateOf(Settings()) }
            LaunchedEffect(Unit) {
                settings = SettingsStore().load()
            }

            // 隐私政策同意门闩：版本不匹配时遮蔽全部内容，同意后写回设置
            val consentAccepted = settings.consentVersion >= ConsentConfig.POLICY_VERSION
            val scope = rememberCoroutineScope()

            // 返回键拦截：浮层打开时返回键=关浮层（而非退出应用）
            DisposableEffect(screen) {
                BackPressHook.handler = when (screen) {
                    Screen.Monitor -> null
                    is Screen.Detail -> ({ screen = Screen.History })
                    Screen.Manual -> ({ screen = Screen.Settings })
                    Screen.BleConfig -> ({ screen = Screen.Settings })
                    Screen.ModbusConfig -> ({ screen = Screen.Settings })
                    Screen.GpioConfig -> ({ screen = Screen.Settings })
                    else -> ({ screen = Screen.Monitor })
                }
                onDispose { }
            }

            // 监控页常驻组合（不被销毁）：切去历史/详情时烘焙会话继续存活，
            // 曲线、计时、连接都不中断；历史/详情以全屏浮层盖在上面
            androidx.compose.foundation.layout.Box(modifier = Modifier) {
                MonitorScreen(
                    settings = settings,
                    hostRefreshKey = hostRefreshKey,
                    onOpenHistory = { screen = Screen.History },
                    onOpenSettings = { screen = Screen.Settings },
                    onCreateProfile = { screen = Screen.Editor(null) },
                    onEditProfile = { p -> screen = Screen.Editor(p) },
                )

                if (!consentAccepted) {
                    ConsentDialog(
                        onAccept = {
                            scope.launch {
                                val updated = settings.copy(consentVersion = ConsentConfig.POLICY_VERSION)
                                SettingsStore().save(updated)
                                settings = updated
                            }
                        },
                        onDeclineForever = { exitApplication() },
                    )
                }

                if (screen != Screen.Monitor) {
                    Surface(modifier = Modifier) {
                        when (val s = screen) {
                            is Screen.History -> HistoryScreen(
                                onBack = { screen = Screen.Monitor },
                                onOpenRecord = { record -> screen = Screen.Detail(record) },
                            )
                            is Screen.Detail -> RoastDetailScreen(
                                record = s.record,
                                onBack = { screen = Screen.History },
                            )
                            is Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onUpdate = { settings = it },
                                onOpenManual = { screen = Screen.Manual },
                                onOpenBleConfig = { screen = Screen.BleConfig },
                                onOpenModbusConfig = { screen = Screen.ModbusConfig },
                                onOpenGpioConfig = { screen = Screen.GpioConfig },
                                onBack = { screen = Screen.Monitor },
                            )
                            is Screen.Manual -> ManualScreen(
                                onBack = { screen = Screen.Settings },
                            )
                            is Screen.BleConfig -> BleConfigScreen(
                                onBack = {
                                    // 配网可能保存了新 IP，通知监控页重新读取
                                    hostRefreshKey++
                                    screen = Screen.Settings
                                },
                            )
                            is Screen.ModbusConfig -> ModbusConfigScreen(
                                settings = settings,
                                onUpdate = { settings = it },
                                onBack = { screen = Screen.Settings },
                            )
                            is Screen.GpioConfig -> GpioConfigScreen(
                                settings = settings,
                                onBack = { screen = Screen.Settings },
                            )
                            is Screen.Editor -> AnchorEditorScreen(
                                initial = s.profile,
                                onBack = { screen = Screen.Monitor },
                            )
                            Screen.Monitor -> {}
                        }
                    }
                }
            }
        }
    }
}
