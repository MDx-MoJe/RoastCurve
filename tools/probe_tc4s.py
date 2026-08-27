#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
烤豆 · Wi-Fi 转 RS485 设备 + 温控器 连通性探测工具
用法:
  python3 probe_tc4s.py <桥接器的IP>            # 单次读温度
  python3 probe_tc4s.py <桥接器的IP> --loop     # 持续轮询(每秒1次), Ctrl+C 停止
  python3 probe_tc4s.py <桥接器的IP> --scan     # 先扫常见端口再读
示例:
  python3 probe_tc4s.py 192.168.1.100
"""
import socket, sys, time

PORTS = [8899, 502, 8080, 8234]   # 默认8899; 其他桥接器常用端口备用
SLAVE = 1                          # 默认从站地址1

def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF

def frame(func: int, addr: int, qty: int) -> bytes:
    body = bytes([SLAVE, func, addr >> 8, addr & 0xFF, qty >> 8, qty & 0xFF])
    c = crc16(body)
    return body + bytes([c & 0xFF, (c >> 8) & 0xFF])

def read_pv(sock) -> "float | None":
    """读实际温度 PV: FC04 @ 0x03E8, 响应7字节"""
    sock.sendall(frame(0x04, 0x03E8, 1))
    resp = b""
    while len(resp) < 7:
        chunk = sock.recv(7 - len(resp))
        if not chunk:
            raise ConnectionError("连接被对端关闭")
        resp += chunk
    # 校验CRC
    calc = crc16(resp[:5])
    recv = resp[5] | (resp[6] << 8)
    if calc != recv:
        print(f"  ! CRC不符: 收{recv:04X} 算{calc:04X} 原始={resp.hex(' ')}")
        return None
    raw = (resp[3] << 8) | resp[4]
    return float(raw)

def read_sv(sock) -> "int | None":
    """读设定温度 SV: FC03 @ 0x0000"""
    sock.sendall(frame(0x03, 0x0000, 1))
    resp = b""
    while len(resp) < 7:
        chunk = sock.recv(7 - len(resp))
        if not chunk:
            raise ConnectionError("连接被对端关闭")
        resp += chunk
    calc = crc16(resp[:5])
    recv = resp[5] | (resp[6] << 8)
    if calc != recv:
        return None
    return (resp[3] << 8) | resp[4]

def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    ip = sys.argv[1]
    loop = "--loop" in sys.argv
    scan = "--scan" in sys.argv or True   # 总是先探测可用端口

    # 第一步：找开放端口
    working_port = None
    for p in PORTS:
        try:
            s = socket.create_connection((ip, p), timeout=2)
            working_port = p
            s.close()
            break
        except OSError:
            pass
    if not working_port:
        print(f"✗ {ip} 的 {PORTS} 端口都不通。检查: ①手机/电脑与桥接器在同一WiFi ②IP是否正确")
        sys.exit(2)
    print(f"✓ TCP连通 {ip}:{working_port}")

    # 第二步：MODBUS 握手
    sock = socket.create_connection((ip, working_port), timeout=3)
    try:
        pv = read_pv(sock)
        if pv is None:
            print("✗ 端口通但 MODBUS 无有效响应。可能原因: 波特率不匹配(桥接器串口侧需设为温控器的115200)、从站号非1")
            sys.exit(3)
        print(f"✓ MODBUS RTU 握手成功!")
        sv = read_sv(sock)
        print(f"┌────────────────────────────┐")
        print(f"│ 实际温度 PV : {pv:7.1f} ℃      │")
        print(f"│ 设定温度 SV : {(sv or 0):7d} ℃      │")
        print(f"└────────────────────────────┘")
        if loop:
            print("持续轮询中，Ctrl+C 退出…")
            t0 = time.time()
            while True:
                time.sleep(1)
                try:
                    v = read_pv(sock)
                    print(f"  [{time.time()-t0:6.0f}s] BT={v:.0f}℃", flush=True)
                except KeyboardInterrupt:
                    break
                except Exception as e:
                    print(f"  ! {e}，重连…"); sock.close()
                    sock = socket.create_connection((ip, working_port), timeout=3)
    finally:
        sock.close()

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已停止")
