/*
 * RoastCurve（烤豆）—— 咖啡烘焙曲线记录与控制
 * Copyright 2026 MDx
 * https://github.com/MDx-MoJe/RoastCurve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once
#include <stdint.h>

// 现场 RS485 探针的结果载体（v1.9.5）
// 放在独立头文件里：Arduino 自动生成函数原型会插到 .ino 顶部，
// 自定义结构体若定义在 .ino 中段，原型会先于定义出现 → "does not name a type"。
struct DiagProbeResult {
  int8_t   ok = -1;      // -1 未执行（SIM 模式）/ 0 失败 / 1 成功
  uint16_t pv = 0;
  uint32_t rttMs = 0;
  uint8_t  txLen = 0, rxLen = 0;
  uint8_t  tx[16], rx[16];
  const char* fail = "";  // 成功时为空串，避免报告里出现“fail=sim”这种脏字段
};

extern DiagProbeResult diagProbe;
