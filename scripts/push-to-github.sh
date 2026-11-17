#!/usr/bin/env bash
# 把本地仓库推到 GitHub。需要 GH_TOKEN（repo 权限）或已 gh auth login。
# 用法：GH_TOKEN=ghp_xxx ./scripts/push-to-github.sh <用户名> [仓库名] [public|private]
set -euo pipefail

OWNER="${1:-}"
REPO="${2:-paiguard}"
VIS="${3:-public}"

if [ -z "$OWNER" ]; then
  echo "用法：GH_TOKEN=... $0 <用户名> [仓库名=paiguard] [public|private]" >&2
  exit 1
fi

cd "$(dirname "$0")/.."

if [ ! -d .git ]; then
  echo "当前目录不是 git 仓库" >&2
  exit 1
fi

# 认 git 身份（没配过的话 GitHub 会显示 unknown）
git config user.name  >/dev/null 2>&1 || git config user.name  "$OWNER"
git config user.email >/dev/null 2>&1 || git config user.email "$OWNER@users.noreply.github.com"

# 脏工作区先提交，别把改动丢在本地
if [ -n "$(git status --porcelain)" ]; then
  echo "== 有未提交改动，先提交 =="
  git add -A
  git commit -m "chore: 推送前同步本地改动"
fi

if git remote get-url origin >/dev/null 2>&1; then
  echo "== 已存在 origin：$(git remote get-url origin) =="
else
  echo "== 创建远程仓库 $OWNER/$REPO ($VIS) =="
  gh repo create "$OWNER/$REPO" "--$VIS" \
    --description "元宝派 LaTeX 公式刷屏与崩溃防护（LSPosed 模块）" \
    --source=. --remote=origin --push
fi

echo "== 推送 main 与 tags =="
git push -u origin main
git push origin --tags

echo
echo "完成：https://github.com/$OWNER/$REPO"
echo "下一步：在 Releases 里挂 paiguard-0.1.0-debug.apk，APK 产物也在 CI 的 artifacts 里。"
