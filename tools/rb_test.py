#!/usr/bin/env python3
"""RoastBridge 全量测试辅助脚本：WS state 读取 + Modbus TCP 读写 + HTTP 状态口"""
import socket, struct, time, os, sys, json

HOST = sys.argv[1] if len(sys.argv) > 1 else '192.168.200.51'
TCP_PORT = 8899
WS_PORT = 8897
HTTP_PORT = 8898

# ============ WS 8897 ============
def ws_connect(host=HOST):
    s = socket.create_connection((host, WS_PORT), timeout=5)
    key = 'dGhlIHNhbXBsZSBub25jZQ=='
    s.sendall((f'GET / HTTP/1.1\r\nHost: {host}:{WS_PORT}\r\nUpgrade: websocket\r\n'
               f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
    resp = s.recv(4096).decode('utf-8', 'replace')
    if '101' not in resp:
        raise Exception(f'WS 握手失败: {resp.split(chr(10))[0]}')
    return s

def ws_send(s, payload: str):
    data = payload.encode()
    mask = os.urandom(4)
    n = len(data)
    head = bytes([0x81, 0x80 | n]) if n < 126 else bytes([0x81, 0x80 | 126]) + n.to_bytes(2, 'big')
    s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

def ws_recv(s, timeout=2.0):
    s.settimeout(timeout)
    try:
        data = s.recv(8192)
    except socket.timeout:
        return None
    if not data:
        return None
    out = b''
    i = 0
    while i < len(data):
        if data[i] & 0x80:
            ln = data[i+1] & 0x7F
            j = i + 2
            if ln == 126: ln = int.from_bytes(data[j:j+2], 'big'); j += 2
            elif ln == 127: ln = int.from_bytes(data[j:j+8], 'big'); j += 8
            out += data[j:j+ln]
            i = j + ln
        else:
            i += 1
    return out.decode('utf-8', 'replace') if out else None

def ws_get_state(host=HOST, timeout=3):
    """发 ping 等 state 广播，返回 dict"""
    s = ws_connect(host)
    ws_send(s, '{"type":"ping"}')
    end = time.time() + timeout
    while time.time() < end:
        f = ws_recv(s, 0.5)
        if f and '"type":"state"' in f:
            s.close()
            return json.loads(f)
    s.close()
    return None

# ============ Modbus TCP 8899 ============
def mbap_request(host, pdu, tid=1, timeout=6):
    s = socket.create_connection((host, TCP_PORT), timeout=timeout)
    frame = struct.pack('>HHHB', tid, 0, len(pdu) + 1, 1) + pdu
    s.sendall(frame)
    try:
        head = b''
        while len(head) < 7:
            c = s.recv(7 - len(head))
            if not c: break
            head += c
        if len(head) < 7:
            s.close(); return None
        length = struct.unpack('>H', head[4:6])[0]
        body = b''
        while len(body) < length - 1:
            c = s.recv(length - 1 - len(body))
            if not c: break
            body += c
        s.close()
        return head + body
    except socket.timeout:
        s.close()
        return None

def mb_read(host, reg, qty=1, tid=1):
    pdu = bytes([0x03, (reg >> 8) & 0xFF, reg & 0xFF, (qty >> 8) & 0xFF, qty & 0xFF])
    resp = mbap_request(host, pdu, tid)
    if not resp or len(resp) < 9:
        return None
    if resp[7] == 0x03:
        n = resp[8]
        vals = []
        for i in range(n // 2):
            vals.append(struct.unpack('>H', resp[9 + i*2:11 + i*2])[0])
        return vals
    return ('EXC', resp[8] if len(resp) > 8 else -1)

def mb_write(host, reg, val, tid=1):
    pdu = bytes([0x06, (reg >> 8) & 0xFF, reg & 0xFF, (val >> 8) & 0xFF, val & 0xFF])
    resp = mbap_request(host, pdu, tid)
    if not resp or len(resp) < 12:
        return None
    if resp[7] == 0x06:
        return struct.unpack('>H', resp[10:12])[0]
    return ('EXC', resp[8] if len(resp) > 8 else -1)

# ============ HTTP 8898 ============
import urllib.request
def http_get(path):
    # 绕过系统代理：环境 http_proxy 会把内网请求送进代理导致超时
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(f'http://{HOST}:{HTTP_PORT}{path}')
    with opener.open(req, timeout=5) as r:
        return r.read().decode('utf-8', 'replace')

if __name__ == '__main__':
    cmd = sys.argv[2] if len(sys.argv) > 2 else 'state'
    if cmd == 'state':
        st = ws_get_state()
        print(json.dumps(st, ensure_ascii=False) if st else '无法获取 state')
    elif cmd == 'read':
        reg = int(sys.argv[3], 0)
        vals = mb_read(HOST, reg)
        print(f'读 reg 0x{reg:04X}: {vals}')
    elif cmd == 'write':
        reg = int(sys.argv[3], 0)
        val = int(sys.argv[4])
        r = mb_write(HOST, reg, val)
        print(f'写 reg 0x{reg:04X} = {val}: 回显 {r}')
    elif cmd == 'http':
        print(http_get(sys.argv[3] if len(sys.argv) > 3 else '/status'))
