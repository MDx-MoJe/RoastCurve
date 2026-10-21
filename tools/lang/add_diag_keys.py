#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
向 zh-CN.csv 追加「连接诊断」词条（一次性工具，2026-10-21）
用法：python3 tools/lang/add_diag_keys.py
已存在的 key 跳过，不覆盖人工译文。
"""
import csv
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CSV_PATH = os.path.join(ROOT, 'tools', 'lang', 'zh-CN.csv')

ROWS = [
    # 页面框架
    ('diag.title', '连接诊断', 'Connection Diagnostics'),
    ('diag.run', '开始诊断', 'Run Diagnostics'),
    ('diag.running', '诊断中…', 'Diagnosing…'),
    ('diag.rerun', '重新诊断', 'Run Again'),
    ('diag.verdict', '结论', 'Verdict'),
    ('diag.advice', '建议', 'What to do'),
    ('diag.detail_toggle', '原始数据', 'Raw data'),
    ('diag.export', '导出诊断包', 'Export Report'),
    ('diag.export_ok', '已保存：{path}', 'Saved: {path}'),
    ('diag.export_fail', '导出失败', 'Export failed'),
    ('diag.log', '连接日志', 'Connection Log'),
    ('diag.log_empty', '暂无日志', 'No entries yet'),
    ('diag.clear', '清空日志', 'Clear Log'),
    ('diag.clear_done', '已清空', 'Cleared'),
    ('diag.warn_active', '诊断会短暂断开当前连接。烘焙中请先停下。', 'Diagnostics briefly interrupt the current connection. Stop roasting first.'),
    ('diag.raw_none', '无', 'none'),
    ('diag.host', '桥接器 IP', 'Bridge IP'),
    ('settings.diag1', '连接诊断', 'Connection Diagnostics'),
    ('settings.diag2', '连不上时先跑一次，直接指出断在哪一环', 'When it will not connect, run this first: it points at the broken link'),
    # 连接失败的细分文案（取代“连接失败: ConnectException”）
    ('app.s13', '连不上桥接器 {host}：8899 端口无响应。确认 IP 正确、桥接器已通电', 'Cannot reach bridge {host}: port 8899 does not respond. Check the IP and that the bridge is powered'),
    ('app.s14', '{host} 拒绡了 8899 连接：固件可能不是烤豆桥接固件，或端口被别的程序占用', '{host} refused port 8899: the firmware may not be RoastCurve bridge firmware, or the port is taken'),
    ('app.s15', '连接 {host} 超时：手机与桥接器可能不在同一网络，或 WiFi 信号太弱', 'Timeout connecting to {host}: phone and bridge may be on different networks, or WiFi is too weak'),
    ('app.s16', '到 {host} 没有路由：请确认手机连的是同一个 WiFi（别用移动数据）', 'No route to {host}: make sure the phone is on the same WiFi (not mobile data)'),
    ('app.s17', '解析不了地址 {host}：请填 IP（如 192.168.1.50），不要填主机名', 'Cannot resolve {host}: enter an IP address (e.g. 192.168.1.50), not a hostname'),
    ('monitor.diag', '诊断', 'Diagnose'),
    # 步骤标题
    ('diag.s1', '① 桥接器地址', '1. Bridge address'),
    ('diag.s2', '② 桥接器状态口（8898）', '2. Bridge status port (8898)'),
    ('diag.s2b', '自动发现对照', 'Auto-discovery check'),
    ('diag.s3', '③ 桥接器自检（总线侧）', '3. Bridge self-test (bus side)'),
    ('diag.s4', '④ 主控端口（8899）', '4. Control port (8899)'),
    ('diag.s5', '⑤ 端到端读取温度', '5. End-to-end temperature read'),
    ('diag.s6', '⑥ 读数合理性', '6. Reading sanity'),
    # 步骤详情
    ('diag.d1_empty', '没有填写桥接器地址', 'Bridge address is empty'),
    ('diag.d1_bad', '地址格式不对：{host}', 'Invalid address format: {host}'),
    ('diag.d1_ok', '地址 {host}', 'Address {host}'),
    ('diag.d2_ok', '固件 {ver}，信号 {rssi} dBm，已运行 {uptime} 秒', 'Firmware {ver}, signal {rssi} dBm, uptime {uptime}s'),
    ('diag.d2_fail', '状态口打不开：手机和桥接器不在同一网络，或 IP 已变', 'Status port unreachable: phone and bridge are not on the same network, or the IP changed'),
    ('diag.d2b_found', '自动发现找到桥接器在 {newHost}，和填写的 {oldHost} 不一致', 'Discovery found the bridge at {newHost}, but the app uses {oldHost}'),
    ('diag.d2b_same', '自动发现与填写的地址一致：{host}', 'Discovery matches the configured address: {host}'),
    ('diag.d3_ok', '桥接器现场读到温控器：{pv}°C，耗时 {rtt} ms', 'Bridge read the controller directly: {pv}°C in {rtt} ms'),
    ('diag.d3_fail', '桥接器自己也读不到温控器：{reason}', 'Bridge cannot reach the controller either: {reason}'),
    ('diag.d3_old', '固件 {ver} 不支持自检，建议升级到 1.9.5 以上再诊断', 'Firmware {ver} has no self-test; update to 1.9.5+ for a precise diagnosis'),
    ('diag.d3_mismatch', '固件实际生效参数与应用设置不一致：{diff}', 'Firmware settings differ from the app: {diff}'),
    ('diag.d3_sim', '桥接器处于模拟模式（SIM），未接真实温控器', 'Bridge is in simulation mode (SIM), no real controller attached'),
    ('diag.d4_ok', '8899 端口可以建立连接', 'Port 8899 accepts connections'),
    ('diag.d4_fail', '8899 端口连不上', 'Port 8899 refuses connections'),
    ('diag.d5_ok', '端到端读取成功：{pv}°C', 'End-to-end read succeeded: {pv}°C'),
    ('diag.d5_fail', '请求已发出，但没读到温度：{err}', 'Request sent but no temperature came back: {err}'),
    ('diag.d6_ok', '读数正常：{pv}°C', 'Reading looks sane: {pv}°C'),
    ('diag.d6_bad', '读数异常：{pv}，寄存器地址可能不对', 'Reading is out of range: {pv}, register address may be wrong'),
    # 失败原因
    ('diag.f_timeout', '完全没有任何回话', 'no reply at all'),
    ('diag.f_crc', '回话了但校验对不上', 'reply received but the checksum does not match'),
    ('diag.f_short', '回包太短', 'reply too short'),
    ('diag.f_slaveid', '应答的从站地址不符', 'reply came from a different slave address'),
    ('diag.f_exception', '温控器回了异常码', 'controller returned an exception code'),
    ('diag.f_sim', '模拟模式未测总线', 'simulation mode, bus not tested'),
    ('diag.f_unknown', '未知原因', 'unknown reason'),
    # 结论
    ('diag.v0', '整条链路是通的，可以开始烘焙', 'The whole chain works. Ready to roast.'),
    ('diag.a0', '如果 App 仍然连不上，把诊断包发出来，里面有逐层证据。', 'If the app still fails, send the exported report: it holds per-layer evidence.'),
    ('diag.v1', '桥接器地址没填或格式不对', 'Bridge address is missing or malformed'),
    ('diag.a1', '在连接设置里填入桥接器 IP。路由器后台找名为 roastbridge 的设备，或用蓝牙配网。', 'Enter the bridge IP in connection settings. Look for "roastbridge" in your router, or use Bluetooth setup.'),
    ('diag.v2', '手机连不上桥接器的状态口', 'The phone cannot reach the bridge at all'),
    ('diag.a2', '确认手机连的是 2.4G 频段的 WiFi、和桥接器同一网段、没有挂 VPN；桥接器状态灯是否绿色常亮。', 'Check that the phone is on the 2.4 GHz network, on the same subnet, with no VPN; is the bridge LED solid green?'),
    ('diag.v3', '总线这一侧不通：桥接器与温控器之间说不上话', 'The bus side is down: the bridge cannot talk to the controller'),
    ('diag.a3', '看原因分别排查：无回话查接线视角（TXD 接模块输入、RXD 接模块输出）、从站地址、温控器电源；校验错查波特率和校验位，改完必须断电重启温控器；异常码查 PV/SV 寄存器地址。', 'By cause: no reply → check wiring orientation (TXD to module input, RXD to module output), slave address, controller power. Checksum error → check baud rate and parity, then power-cycle the controller. Exception → check PV/SV register addresses.'),
    ('diag.v4', '8899 端口不通', 'Port 8899 is not reachable'),
    ('diag.a4', '桥接器可能没刷烤豆固件，或 8899 被其他程序占用。重启桥接器后再试。', 'The bridge may not run RoastCurve firmware, or 8899 is occupied. Reboot the bridge and retry.'),
    ('diag.v5', '桥接器本身正常，问题出在 8899 通道或会话上', 'The bridge is healthy; the problem is the 8899 channel or session'),
    ('diag.a5', '桥接器读得到温控器，App 读不到：关掉其他正在连接的程序（含其他手机、网页控制台），重启桥接器后再连。', 'The bridge can read the controller but the app cannot: close other clients (other phones, the web console), reboot the bridge, then reconnect.'),
    ('diag.v6', '能读到温度，但数值不合理', 'Temperature reads, but the value is out of range'),
    ('diag.a6', '温控器型号可能不兼容，检查 PV 寄存器地址是否正确。', 'The controller model may be incompatible. Check the PV register address.'),
    ('diag.v7', 'App 读不到，但桥接器固件太旧，无法对照判断', 'The app cannot read, and the firmware is too old to compare against'),
    ('diag.a7', '把桥接器固件升级到 1.9.5 以上再诊断一次，就能直接指出是哪一环断的。', 'Update the bridge firmware to 1.9.5+ and run diagnostics again: it will point at the exact broken link.'),
]


def main():
    existing = []
    if os.path.exists(CSV_PATH):
        with open(CSV_PATH, encoding='utf-8-sig', newline='') as f:
            existing = list(csv.DictReader(f))
    have = {r['key'] for r in existing}
    added = 0
    for key, zh, en in ROWS:
        if key in have:
            continue
        existing.append({'key': key, 'zh': zh, 'en': en, 'file': 'DiagScreen.kt', 'line': '0'})
        added += 1
    with open(CSV_PATH, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['key', 'zh', 'en', 'file', 'line'])
        w.writeheader()
        w.writerows(existing)
    print(f'已追加 {added} 条诊断词条，累计 {len(existing)} 条')


if __name__ == '__main__':
    main()
