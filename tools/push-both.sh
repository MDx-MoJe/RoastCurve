#!/bin/bash
# push-both.sh — 同时推 GitHub(origin) + Gitee(gitee)，默认 main 分支
# 用法:
#   ./tools/push-both.sh          # 推 main 到两地
#   ./tools/push-both.sh dev      # 推 dev 到两地（若有）
#
# 网络策略:
#   GitHub 走全局代理(7890，FlClash)，代理不在则跳过 GitHub 段并提示
#   Gitee  走直连（http.https://gitee.com/.proxy 已设空覆盖）
set -u
cd "$(git rev-parse --show-toplevel)" || exit 1
BRANCH="${1:-$(git branch --show-current)}"

# 本地该分支是否已提交但未推的变更（不含未 commit 的工作区改动）
if [ -n "$(git status --porcelain)" ]; then
  echo "⚠ 工作区有未提交改动，先 commit 再双推（当前只推已提交内容）"
fi

echo "== 分支: $BRANCH =="

# --- GitHub 段（走代理）---
echo "── GitHub (origin) ──"
if git push origin "$BRANCH" 2>&1 | tee /tmp/pushall-origin.log | grep -q "Failed to connect to 127.0.0.1"; then
  echo "  ⚠ GitHub 推送失败：代理(7890)未运行。已跳过 GitHub，仅推了 Gitee。"
  echo "  提示：先启动 FlClash 再单独补推: git push origin $BRANCH"
else
  echo "  ✓ GitHub 推送完成"
fi

# --- Gitee 段（直连，gitee.com 已设代理覆盖为空）---
echo "── Gitee (gitee) ──"
git push gitee "$BRANCH" 2>&1 | tail -2 || echo "  ⚠ Gitee 推送失败"

echo "== 完成 =="
