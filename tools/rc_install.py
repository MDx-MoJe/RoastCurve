#!/usr/bin/env python3
"""
烤豆 App 安装器 v3

用法:
  tools/rc_install.sh [apk路径]

特性:
  - 多设备路由: 默认推所有在线设备，也可指定品牌偏好；目标离线显式报错
  - 唤醒屏幕并解锁
  - 边安装边轮询厂商安全确认弹窗并自动代点（勾选复选框+确认按钮，
    不依赖 clickable 属性——input tap 坐标注入天然冒泡）
  - lastUpdateTime 核验防假成功
"""
import subprocess
import re
import sys
import os
import time
import threading
import shutil
import xml.etree.ElementTree as ET


def _find_adb():
    """智能定位 adb：不硬编码个人路径，开源友好"""
    from pathlib import Path
    # 1. 环境变量 ANDROID_HOME / ANDROID_SDK_ROOT
    for var in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        v = os.environ.get(var)
        if v:
            p = Path(v) / 'platform-tools' / 'adb'
            if p.exists():
                return str(p)
    # 2. 仓库根目录 local.properties 的 sdk.dir
    local = Path(__file__).resolve().parent.parent / 'local.properties'
    if local.exists():
        for line in local.read_text().splitlines():
            if line.startswith('sdk.dir='):
                p = Path(line.split('=', 1)[1].strip()) / 'platform-tools' / 'adb'
                if p.exists():
                    return str(p)
    # 3. PATH 里的 adb
    which = shutil.which('adb')
    if which:
        return which
    return 'adb'


ADB = _find_adb()
PKG = 'com.roastcurve.android'
BTN_WORDS = ['继续安装', '仍要安装', '本次允许', '安装', '允许', '确定', '好']
SERIAL = None


def sh(*args):
    cmd = [ADB] + (['-s', SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True)


def pick_serial(prefer_brand):
    r = subprocess.run([ADB, 'devices'], capture_output=True, text=True)
    serials = []
    for line in r.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == 'device':
            serials.append(parts[0])
    if len(serials) <= 1:
        s = serials[0] if serials else None
        if s:
            b = subprocess.run([ADB, '-s', s, 'shell', 'getprop', 'ro.product.brand'],
                               capture_output=True, text=True).stdout.strip().lower()
            if prefer_brand not in b:
                print(f'⚠ 目标设备({prefer_brand})不在线，当前只有 {b} —— 已中止，未安装')
                return None
        return s
    for s in serials:
        b = subprocess.run([ADB, '-s', s, 'shell', 'getprop', 'ro.product.brand'],
                           capture_output=True, text=True).stdout.strip().lower()
        if prefer_brand in b:
            return s
    print(f'⚠ 目标设备({prefer_brand})不在线 —— 已中止，未安装')
    return None


def dump_xml():
    sh('shell', 'uiautomator', 'dump', '/sdcard/rc_i.xml')
    return sh('shell', 'cat', '/sdcard/rc_i.xml').stdout


def last_update_time():
    r = sh('shell', 'dumpsys', 'package', PKG)
    m = re.search(r'lastUpdateTime=(.+)', r.stdout)
    return m.group(1).strip() if m else '?'


def bounds_center(attrib):
    bd = attrib.get('bounds', '')
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bd)
    if not m:
        return None
    l, t, r_, b = map(int, m.groups())
    return (l + r_) // 2, (t + b) // 2


def find_button(xml):
    """找确认按钮：优先可点击祖先，否则直接点文本中心"""
    try:
        root = ET.fromstring(xml)
    except Exception:
        return None
    parent = {c: p for p in root.iter() for c in p}

    def walk(e):
        for c in list(e):
            yield from walk(c)
            yield c
    for e in root.iter():
        if e.get('text') in BTN_WORDS:
            cur, depth = e, 0
            while cur is not None and depth < 8:
                if cur.get('clickable') == 'true':
                    c = bounds_center(cur.attrib)
                    if c:
                        return c[0], c[1], e.get('text')
                cur = parent.get(cur)
                depth += 1
            c = bounds_center(e.attrib)
            if c:
                return c[0], c[1], e.get('text')
    return None


def find_unchecked_box(xml):
    """找未勾选的复选框（弹窗内的小控件）"""
    try:
        root = ET.fromstring(xml)
    except Exception:
        return None
    for e in root.iter():
        if (e.get('checkable') == 'true' and e.get('checked') == 'false'):
            bd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', e.get('bounds', ''))
            if not bd:
                continue
            l, t, r_, b = map(int, bd.groups())
            return (l + r_) // 2, (t + b) // 2
    return None


def find_confirm_text(xml):
    """找「已了解/了解/同意」等确认文字（vivo 安全检测弹窗里是 clickable 而非 checkable）"""
    try:
        root = ET.fromstring(xml)
    except Exception:
        return None
    for e in root.iter():
        t = e.get('text') or ''
        if e.get('clickable') == 'true' and any(w in t for w in ['已了解', '了解', '同意', '确认']):
            bd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', e.get('bounds', ''))
            if not bd:
                continue
            l, t, r_, b = map(int, bd.groups())
            return (l + r_) // 2, (t + b) // 2
    return None


def handle_dialog_once():
    """处理一轮弹窗：先勾框（如"已了解风险"）再点确认按钮。返回是否做了操作"""
    xml = dump_xml()
    box = find_unchecked_box(xml)
    if box:
        print(f'      勾选确认框 @({box[0]},{box[1]})')
        sh('shell', 'input', 'tap', str(box[0]), str(box[1]))
        time.sleep(1.0)
        xml = dump_xml()
    else:
        # vivo 安全检测弹窗：确认项是 clickable 而非 checkable
        conf = find_confirm_text(xml)
        if conf:
            print(f'      勾选确认项 @({conf[0]},{conf[1]})')
            sh('shell', 'input', 'tap', str(conf[0]), str(conf[1]))
            time.sleep(1.0)
            xml = dump_xml()
    btn = find_button(xml)
    if btn:
        x, y, w = btn
        print(f'      点「{w}」@({x},{y})')
        sh('shell', 'input', 'tap', str(x), str(y))
        time.sleep(1.5)
        return True
    return False


def do_install(apk):
    r = sh('install', '-r', '-t', apk)
    out = (r.stdout + r.stderr).strip()
    return out.splitlines()[-1] if out else '(无输出)'


def install_with_watch(apk, watch_sec=45):
    result = {}

    def worker():
        result['tail'] = do_install(apk)

    th = threading.Thread(target=worker)
    th.start()
    deadline = time.time() + watch_sec
    while th.is_alive() and time.time() < deadline:
        if handle_dialog_once():
            handled = True
    th.join()
    return result.get('tail', '(无输出)')


def pick_all_online():
    r = subprocess.run([ADB, 'devices'], capture_output=True, text=True)
    serials = []
    for line in r.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == 'device':
            serials.append(parts[0])
    return serials


def install_to(serial, apk):
    global SERIAL
    SERIAL = serial
    before = last_update_time()
    print(f'\n[设备 {serial}]')
    print('[0/3] 唤醒屏幕并解锁')
    sh('shell', 'input', 'keyevent', '224')
    sh('shell', 'wm', 'dismiss-keyguard')
    sh('shell', 'input', 'keyevent', '82')
    time.sleep(1)
    print(f'[1/3] 安装 {apk.split("/")[-1]}')
    print(f'      当前版本安装于: {before}')
    tail = install_with_watch(apk)
    print(f'      adb: {tail}')
    if 'Success' not in tail:
        print('[2/3] 未成功，继续盯弹窗重试（最多 40 秒）...')
        deadline = time.time() + 40
        while time.time() < deadline:
            if handle_dialog_once():
                tail = do_install(apk)
                print(f'      重试 adb: {tail}')
                break
            time.sleep(0.8)
    after = last_update_time()
    ok = before != after
    print(f'[3/3] {"✅ 生效 " if ok else "❌ 未生效 "}({after})')
    return ok


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else \
        'androidApp/build/outputs/apk/release/androidApp-release.apk'
    serials = pick_all_online()
    if not serials:
        print('❌ 未发现在线设备')
        return 1
    fails = 0
    for s in serials:
        if not install_to(s, apk):
            fails += 1
    print(f'\n完成：{len(serials)-fails}/{len(serials)} 台成功')
    return 0 if fails == 0 else 1

    before = last_update_time()
    print('[0/3] 唤醒屏幕并解锁')
    sh('shell', 'input', 'keyevent', '224')
    sh('shell', 'wm', 'dismiss-keyguard')
    sh('shell', 'input', 'keyevent', '82')
    time.sleep(1)

    print(f'[1/3] 安装 {apk.split("/")[-1]}')
    print(f'      当前版本安装于: {before}')
    tail = install_with_watch(apk)
    print(f'      adb: {tail}')

    if 'Success' not in tail:
        print('[2/3] 未成功，继续盯弹窗重试（最多 40 秒）...')
        deadline = time.time() + 40
        retried = False
        while time.time() < deadline:
            if handle_dialog_once():
                tail = do_install(apk)
                print(f'      重试 adb: {tail}')
                retried = True
                break
            time.sleep(0.8)
        if not retried and 'Success' not in tail:
            print('      未见弹窗。若手机锁屏请先解锁再跑一次')

    after = last_update_time()
    if before != after:
        print(f'[3/3] ✅ 安装生效（lastUpdateTime {after}）')
        return 0
    print('[3/3] ❌ 包未更新。请留意手机是否有其他确认框')
    return 1


if __name__ == '__main__':
    sys.exit(main())
