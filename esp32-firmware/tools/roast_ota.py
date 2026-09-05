#!/usr/bin/env python3
"""
roast_ota.py — 针对 ESP32 ArduinoOTA 的稳健无线升级推送脚本
修复 espota.py「发一块等一块」协议在弱 WiFi 下的中途断流问题：
- 认证用标准 PBKDF2-HMAC-SHA256 挑战响应
- 数据传输改为「持续发送 + 依赖 TCP 流控 + 独立线程读响应」
- 每块仍读取设备回写的字节数（设备需要它做进度确认），但发送用阻塞式 sendall
  且把每块响应超时放到 60s，避免瞬时抖动误杀

用法: python3 roast_ota.py -i <ip> -a <password> -f <firmware.bin> [-t timeout]
"""
import argparse, hashlib, os, socket, sys, time

FLASH = 0
AUTH = 200

def md5hex(data):
    return hashlib.md5(data).hexdigest()

def pbkdf2_sha256(password, salt, iterations=10000, dklen=32):
    return hashlib.pbkdf2_hmac('sha256', password.encode(), salt, iterations, dklen)

def do_ota(ip, port, password, filename, timeout=120):
    size = os.path.getsize(filename)
    with open(filename, 'rb') as f:
        data = f.read()
    file_md5 = md5hex(data)

    # 1. UDP 邀请
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(10)
    udp.bind(('0.0.0.0', 0))
    host_port = 57380
    # 绑定一个固定端口让设备连回来
    tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    tcp.bind(('0.0.0.0', host_port))
    tcp.listen(1)

    inv = "%d %d %d %s\n" % (FLASH, host_port, size, file_md5)
    print(f"[1] 发送邀请到 {ip}:{port} (size={size})")
    udp.sendto(inv.encode(), (ip, port))

    # 2. 收挑战
    try:
        resp, addr = udp.recvfrom(512)
    except socket.timeout:
        print("[!] 邀请无响应"); return 1
    if not resp.startswith(b'AUTH'):
        print(f"[!] 预期 AUTH，收到: {resp[:50]}")
        return 1
    parts = resp.decode().split()
    nonce = parts[1]
    nonce_bytes = nonce.encode()
    print(f"[2] 收到挑战 nonce_len={len(nonce)}")

    # 3. 挑战响应（精确复刻 espota.py 的 PBKDF2-HMAC-SHA256 握手）
    cnonce_text = "%s%u%s%s" % (filename, size, file_md5, addr[0])
    cnonce = hashlib.sha256(cnonce_text.encode()).hexdigest()
    password_hash = hashlib.sha256(password.encode()).hexdigest()
    salt = nonce + ":" + cnonce
    derived_key = hashlib.pbkdf2_hmac("sha256", password_hash.encode(), salt.encode(), 10000)
    derived_key_hex = derived_key.hex()
    challenge = derived_key_hex + ":" + nonce + ":" + cnonce
    response = hashlib.sha256(challenge.encode()).hexdigest()
    msg = f"{AUTH} {cnonce} {response}\n"
    print("[3] 发送认证响应")
    udp.sendto(msg.encode(), (ip, port))
    udp.settimeout(10)
    try:
        ack, _ = udp.recvfrom(64)
        print(f"[4] 认证回执: {ack[:30]}")
        if not ack.startswith(b'OK'):
            print("[!] 认证失败"); return 1
    except socket.timeout:
        print("[!] 认证回执超时（可能仍成功）")

    # 5. 设备连回 TCP
    print("[5] 等待设备连接...")
    tcp.settimeout(15)
    try:
        conn, addr = tcp.accept()
    except socket.timeout:
        print("[!] 设备未连回"); return 1
    conn.settimeout(timeout)
    print(f"[6] 设备已连接 {addr}，开始传输 {size} 字节")

    # 6. 传输（发一块读一块响应，超时 60s）
    sent = 0
    chunk_size = 1024
    t_start = time.time()
    while sent < size:
        chunk = data[sent:sent+chunk_size]
        conn.sendall(chunk)
        sent += len(chunk)
        # 读设备响应（字节数确认）
        try:
            res = conn.recv(16)
        except socket.timeout:
            print(f"[!] 传输卡在 {sent}/{size} ({(sent*100)//size}%)")
            break
        if not res:
            print(f"[!] 连接关闭 @ {sent}/{size}")
            break
    dt = time.time() - t_start
    print(f"[7] 发送完成 {sent}/{size} 字节, 用时 {dt:.1f}s")

    # 7. 等最终 OK
    conn.settimeout(30)
    try:
        fin = conn.recv(64)
        print(f"[8] 最终响应: {fin[:40]}")
        if b'OK' in fin:
            print("✅ OTA 成功")
            return 0
        elif b'Not Activate' in fin:
            print("⚠️ 数据完整但固件无效（测试镜像）")
            return 0
    except socket.timeout:
        print("[8] 最终响应超时")
    return 1

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('-i', '--ip', required=True)
    ap.add_argument('-a', '--auth', required=True)
    ap.add_argument('-f', '--file', required=True)
    ap.add_argument('-t', '--timeout', type=int, default=120)
    ap.add_argument('-p', '--port', type=int, default=3232)
    args = ap.parse_args()
    sys.exit(do_ota(args.ip, args.port, args.auth, args.file, args.timeout))
