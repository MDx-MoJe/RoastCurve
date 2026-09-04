#!/bin/bash
# publish-both.sh — RoastCurve / CoffeeBeanTracker 一键双平台发行
# 用法:
#   ./tools/publish-both.sh <版本号> ["发行标题"] ["发行说明"]
# 示例:
#   ./tools/publish-both.sh v1.3.10 "RoastCurve v1.3.10" "修复翻译键冲突；新增自动风速下限"
#
# 流程:
#   1. 校验版本号、本地 tag 与产物目录 release-<ver>/
#   2. 打 tag（若不存在）→ 推 GitHub(origin) + Gitee(gitee)
#   3. Gitee 发行版：建 release → 上传 release-<ver>/ 下全部附件
#   4. GitHub Release：提示手工（gh CLI / 网页，因 gh token 可能失效）
#
# 网络: GitHub 走全局代理(7890)，Gitee 直连（已配 http.https://gitee.com/.proxy 覆盖）
# 凭据: Gitee 私人令牌存 macOS 钥匙串（gitee.com / MDx-MoJe），GitHub 走 gh/钥匙串
set -u
cd "$(git rev-parse --show-toplevel)" || exit 1

# ---------- 参数 ----------
VER="${1:-}"
TITLE="${2:-}"
BODY="${3:-}"
if [ -z "$VER" ]; then
  echo "用法: $0 <版本号> [\"标题\"] [\"说明\"]"
  echo "示例: $0 v1.3.10 \"RoastCurve v1.3.10\" \"修复翻译键冲突\""
  exit 1
fi
# 去掉 v 前缀拿目录名
VER_NUM="${VER#v}"
REL_DIR="release-$VER_NUM"

# ---------- 仓库信息 ----------
REPO_NAME="$(basename "$(git remote get-url origin)" .git)"   # RoastCurve / CoffeeBeanTracker
GITEE_REPO="$(basename "$(git remote get-url gitee)" .git)"   # roast-curve / coffee-bean-tracker
GITEE_USER="MDx-MoJe"
echo "== 仓库: $REPO_NAME | Gitee: $GITEE_USER/$GITEE_REPO | 版本: $VER =="

# ---------- 1. 预检 ----------
echo "── 预检 ──"
if [ ! -d "$REL_DIR" ]; then
  echo "  ✗ 找不到产物目录 $REL_DIR/ —— 请先把 APK/固件放到该目录"
  echo "    或手动指定: $0 $VER 时确保 release-$VER_NUM/ 存在"
  exit 1
fi
# 产物清单
FILES=( "$REL_DIR"/* )
if [ ${#FILES[@]} -eq 1 ] && [ ! -f "${FILES[0]}" ]; then
  echo "  ✗ $REL_DIR/ 里没有文件"
  exit 1
fi
echo "  产物目录 $REL_DIR/ 含:"
for f in "${FILES[@]}"; do [ -f "$f" ] && echo "    - $(basename "$f") ($(du -h "$f" | cut -f1))"; done

# 工作区是否干净（未提交改动会提醒）
if [ -n "$(git status --porcelain)" ]; then
  echo "  ⚠ 工作区有未提交改动！发行 tag 将打在未提交状态之上，请先 commit。"
  echo "    继续请输入 y，取消输入 n："
  read -r ans
  [ "$ans" = "y" ] || { echo "  已取消"; exit 1; }
fi

# ---------- 2. tag → 推送 ----------
echo "── tag 与推送 ──"
if git rev-parse "$VER" >/dev/null 2>&1; then
  echo "  tag $VER 已存在，跳过打 tag"
else
  git tag "$VER" || { echo "  ✗ 打 tag 失败"; exit 1; }
  echo "  ✓ 已打 tag $VER"
fi
# Gitee 直连推
echo "  推 Gitee..."
if git push gitee "$VER" 2>&1 | tail -1 | grep -qE 'new tag|up-to-date|already'; then
  echo "  ✓ Gitee tag 就绪"
else
  echo "  ⚠ Gitee tag 推送失败（网络？），继续尝试发行版"
fi
# GitHub 走代理
echo "  推 GitHub (需要 FlClash 代理)..."
if git push origin "$VER" 2>&1 | tail -1 | grep -qE 'new tag|up-to-date|already'; then
  echo "  ✓ GitHub tag 就绪"
else
  echo "  ⚠ GitHub tag 推送失败：代理(7890)可能未运行。稍后手工补: git push origin $VER"
fi

# ---------- 3. Gitee 发行版 ----------
echo "── Gitee 发行版 ──"
GITEE_TOKEN="$(printf 'protocol=https\nhost=gitee.com\n' | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)"
if [ -z "$GITEE_TOKEN" ]; then
  echo "  ✗ 从钥匙串取不到 Gitee 令牌，请先: git credential-osxkeychain store"
  exit 1
fi
# 发行说明默认
if [ -z "$BODY" ]; then
  BODY="$REPO_NAME $VER"
fi
if [ -z "$TITLE" ]; then
  TITLE="$REPO_NAME $VER"
fi
# 建发行版（幂等：已存在则复用）
RELEASE_JSON="$(curl -s --noproxy '*' -m 20 -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases?access_token=$GITEE_TOKEN" \
  --data-urlencode "tag_name=$VER" \
  --data-urlencode "target_commitish=main" \
  --data-urlencode "name=$TITLE" \
  --data-urlencode "body=$BODY")"
RELEASE_ID="$(echo "$RELEASE_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('id',''))" 2>/dev/null)"
if [ -z "$RELEASE_ID" ]; then
  # 可能已存在 → 查列表拿 id
  RELEASE_ID="$(curl -s --noproxy '*' -m 15 "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases?access_token=$GITEE_TOKEN" | python3 -c "
import sys,json
for r in json.load(sys.stdin):
    if r.get('tag_name')=='$VER': print(r.get('id'))
" 2>/dev/null)"
fi
if [ -z "$RELEASE_ID" ]; then
  echo "  ✗ 建发行版失败: $(echo "$RELEASE_JSON" | head -c 200)"
  exit 1
fi
echo "  ✓ 发行版 id=$RELEASE_ID"
# 上传附件
for f in "${FILES[@]}"; do
  [ -f "$f" ] || continue
  NAME="$(basename "$f")"
  echo "  上传 $NAME ..."
  curl -s --noproxy '*' -m 300 -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases/$RELEASE_ID/attach_files?access_token=$GITEE_TOKEN" \
    -F "file=@$f" | python3 -c "
import sys,json
d=json.load(sys.stdin)
if d.get('name'): print('    ✓', d.get('name'), d.get('size'), 'bytes')
else: print('    ✗ 上传失败:', str(d)[:150])
"
done

# ---------- 4. GitHub Release（手工） ----------
echo "── GitHub Release（手工步骤）──"
echo "  tag $VER 已推送 GitHub。请二选一补 Release："
echo "    网页: https://github.com/MDx-MoJe/$REPO_NAME/releases/new?tag=$VER"
echo "    CLI: gh release create $VER --title \"$TITLE\" --notes \"$BODY\" $REL_DIR/*  (需 gh auth 有效)"

echo
echo "== 完成。Gitee 发行版: https://gitee.com/$GITEE_USER/$GITEE_REPO/releases =="
