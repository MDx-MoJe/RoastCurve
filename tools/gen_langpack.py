#!/usr/bin/env python3
"""
烤豆 RoastCurve 语言包工具链
- scan:  从 composeApp 源码抽取硬编码中文字符串 → 生成键名初稿 + ZhSourceGenerated.kt + en 初稿
- pack:  把 zh/en 合并打包为语言包 zip（beanbag 风格：lang.zip 内含 lang.json）

用法:
  python3 gen_langpack.py scan            # 抽取 → lang/zh-CN.csv (键名待人工复核)
  python3 gen_langpack.py build           # 复核后的 csv → ZhSourceGenerated.kt + EnBuilt.kt + roastcurve_lang_en.zip
"""
import os, re, sys, glob, json, csv, zipfile

ROOT = os.path.expanduser('~/Desktop/OH-WorkSpace/RoastCurve')
SCAN_DIRS = [
    'composeApp/src/commonMain/kotlin',
    'composeApp/src/androidMain/kotlin',
    'shared/src/commonMain/kotlin',
]
# 排除 l10n 自身（避免自举）与构建生成物
EXCLUDE = ['/l10n/', '/build/']
OUT_DIR = os.path.join(ROOT, 'tools', 'lang')
ZH_CSV = os.path.join(OUT_DIR, 'zh-CN.csv')
EN_CSV = os.path.join(OUT_DIR, 'en.csv')

# 键名映射规则：文件名 → 模块前缀
MODULE_MAP = {
    'MonitorScreen.kt': 'monitor',
    'ManualScreen.kt': 'manual',
    'SettingsScreen.kt': 'settings',
    'RoastDetailScreen.kt': 'detail',
    'AnchorEditorScreen.kt': 'anchor',
    'BleConfigScreen.kt': 'ble',
    'HistoryScreen.kt': 'history',
    'ConsentDialog.kt': 'consent',
    'RoastPhaseStats.kt': 'stats',
    'RoastChart.kt': 'chart',
    'RoastDetailScreen.kt': 'detail',
}
_file_counter = {}

def make_key(fname, text):
    mod = MODULE_MAP.get(fname, 'app')
    _file_counter[mod] = _file_counter.get(mod, 0) + 1
    return f'{mod}.s{_file_counter[mod]}'  # 全模块内唯一；语义化改名在 CSV 人工复核时进行

def scan():
    os.makedirs(OUT_DIR, exist_ok=True)
    seen = {}   # text -> key
    rows = []
    for d in SCAN_DIRS:
        for f in glob.glob(os.path.join(ROOT, d, '**', '*.kt'), recursive=True):
            if any(x in f for x in EXCLUDE):
                continue
            fname = os.path.basename(f)
            src = open(f, encoding='utf-8', errors='replace').read()
            lines = src.split('\n')
            for i, line in enumerate(lines, 1):
                st = line.strip()
                if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
                    continue
                code = line.split('//')[0]
                # 抓所有含中文的字符串字面量
                for m in re.finditer(r'"([^"]*[\u4e00-\u9fff][^"]*)"', code):
                    text = m.group(1)
                    if not text.strip():
                        continue
                    # 含嵌套引号的模板（如 ${x ?: "--"}）无法可靠转换 → 跳过留给人工
                    if '${' in text and '"' in code[max(0,code.find('${')):code.find('}')+1] if '${' in text else False:
                        continue
                    # Kotlin 字符串模板 ${任意expr} → 语言包占位符 {var}（变量名取 expr 内最后一个标识符）
                    def _ph(mm):
                        ids = re.findall(r'[A-Za-z_][A-Za-z0-9_]*', mm.group(1))
                        return '{' + (ids[-1] if ids else 'arg') + '}'
                    text = re.sub(r'\$\{([^}]*)\}', _ph, text)
                    # Kotlin 简模板 $identifier → {identifier}
                    text = re.sub(r'\$([A-Za-z_][A-Za-z0-9_]*)', r'{\1}', text)
                    if text not in seen:
                        key = make_key(fname, text)
                        seen[text] = key
                        rows.append({'key': key, 'zh': text, 'file': fname, 'line': i})
    rows.sort(key=lambda r: (r['file'], r['line']))
    with open(ZH_CSV, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['key', 'zh', 'en', 'file', 'line'])
        w.writeheader()
        for r in rows:
            w.writerow({'key': r['key'], 'zh': r['zh'], 'en': '', 'file': r['file'], 'line': r['line']})
    print(f'抽取 {len(rows)} 条 → {ZH_CSV}')
    print('下一步: 人工复核键名/en 列 → python3 gen_langpack.py build')

def build():
    if not os.path.exists(ZH_CSV):
        print('先运行 scan'); return
    rows = list(csv.DictReader(open(ZH_CSV, encoding='utf-8-sig')))
    zh_map = {r['key']: r['zh'] for r in rows}
    en_map = {r['key']: r['en'] for r in rows if r.get('en', '').strip()}

    # ZhSourceGenerated.kt
    zh_body = ',\n'.join(f'        "{k}" to {json.dumps(v, ensure_ascii=False)}' for k, v in sorted(zh_map.items()))
    gen = f'''package com.roastcurve.shared.l10n

/** 自动生成：python3 tools/gen_langpack.py build —— 请勿手工编辑此文件 */
object ZhSourceGenerated {{
    val strings: Map<String, String> = mapOf(
{zh_body}
    )
}}
'''
    open(os.path.join(ROOT, 'shared/src/commonMain/kotlin/com/roastcurve/shared/l10n/ZhSourceGenerated.kt'),
         'w', encoding='utf-8').write(gen)

    # EnBuilt.kt（只含已翻译的）
    en_body = ',\n'.join(f'        "{k}" to {json.dumps(v, ensure_ascii=False)}' for k, v in sorted(en_map.items()))
    gen2 = f'''package com.roastcurve.shared.l10n

/** 自动生成：python3 tools/gen_langpack.py build —— 请勿手工编辑此文件 */
object EnBuilt {{
    val strings: Map<String, String> = mapOf(
{en_body}
    )
}}
'''
    open(os.path.join(ROOT, 'shared/src/commonMain/kotlin/com/roastcurve/shared/l10n/EnBuilt.kt'),
         'w', encoding='utf-8').write(gen2)

    # 英文语言包 zip（可分发给社区做其他语言的底稿）
    pack = {
        'meta': {'name': 'English', 'version': 1, 'authors': ['MDx'], 'base': 'zh-CN'},
        'strings': en_map,
    }
    zpath = os.path.join(OUT_DIR, 'roastcurve_lang_en.zip')
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('lang.json', json.dumps(pack, ensure_ascii=False, indent=1))
    print(f'生成 ZhSourceGenerated.kt ({len(zh_map)} 键) / EnBuilt.kt ({len(en_map)} 键) / {zpath}')

if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'scan'
    {'scan': scan, 'build': build}[cmd]()
