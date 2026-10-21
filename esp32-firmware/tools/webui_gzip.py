#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 webui/index.html 压成 roast_bridge/webui_gzip.h（PROGMEM 字节数组）。

固件用 gzip 直出网页：ESP32 端省 flash、浏览器端自动解压。
改完 webui/index.html 必须重跑本脚本，否则固件里还是旧页面。

用法：python3 tools/webui_gzip.py
"""
import gzip
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'webui', 'index.html')
DST = os.path.join(ROOT, 'roast_bridge', 'webui_gzip.h')

HEADER = """// 自动生成：esp32-firmware/webui/index.html（勿手改）
#pragma once
#include <pgmspace.h>
constexpr unsigned int WEBUI_GZIP_LEN = {size};
constexpr const uint8_t WEBUI_GZIP[] PROGMEM = {{
{body}
}};
"""


def main():
    raw = open(SRC, 'rb').read()
    gz = gzip.compress(raw, 9, mtime=0)   # OS 字节固定 0xFF，与历史产物一致
    lines = []
    for i in range(0, len(gz), 16):
        chunk = ','.join(f'0x{b:02X}' for b in gz[i:i + 16])
        lines.append('  ' + chunk + ',')
    open(DST, 'w', encoding='utf-8').write(HEADER.format(size=len(gz), body='\n'.join(lines)))
    print(f'index.html {len(raw)} B → webui_gzip.h {len(gz)} B（{len(raw)/max(1,len(gz)):.1f}x）')


if __name__ == '__main__':
    main()
