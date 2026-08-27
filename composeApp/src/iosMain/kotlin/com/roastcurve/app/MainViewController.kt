package com.roastcurve.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS 入口桥接：Swift 侧调用 MainViewControllerKt.MainViewController()
 * （@Composable 函数经编译器变换后无法直接从 Swift 调用，需此桥接）
 */
fun MainViewController(): UIViewController =
    ComposeUIViewController {
        App()
    }