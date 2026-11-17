#!/usr/bin/env bash
# 在【你自己的机器】上运行：把 PaiGuard 推到 GitHub。
# 用法：
#   ./push-from-local.sh                          # 默认私钥 ~/.ssh/id_ed25519_paiguard
#   ./push-from-local.sh /path/to/key             # 指定私钥
#   KEY=~/.ssh/id_ed25519_paiguard REMOTE=git@github.com:ybnmb/78.git ./push-from-local.sh
set -euo pipefail

KEY="${1:-${KEY:-$HOME/.ssh/id_ed25519_paiguard}}"
REMOTE="${REMOTE:-git@github.com:ybnmb/78.git}"
BRANCH="${BRANCH:-main}"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m错误：%s\033[0m\n' "$*" >&2; exit 1; }

# 1. 必须在 git 仓库里（含 .git 的源码包解压后就是）
[ -d .git ] || die "当前目录不是 git 仓库。请先解压 paiguard-git.tar.gz 并 cd 进 paiguard/。"

# 2. 私钥检查
[ -f "$KEY" ] || die "找不到私钥：$KEY\n把它放到该路径，或用第一个参数指定：./push-from-local.sh /path/to/key"
chmod 600 "$KEY"
say "私钥：$KEY（已设为 600）"

# 3. 用这把钥匙测 GitHub 身份；失败就停，不硬推
say "测试 SSH 连接 GitHub …"
if ! INFO="$(ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -o BatchMode=yes -T git@github.com 2>&1)"; then
  printf '%s\n' "$INFO"
  die "SSH 连不上。确认：① 公钥已加到 GitHub → Settings → SSH and GPG keys；② 网络允许 22 端口（公司网/公共 WiFi 常拦截，可改用 HTTPS 或 443 端口）。
若 22 被拦，把 REMOTE 换成 ssh://git@ssh.github.com:443/ybnmb/78.git 再跑：
  REMOTE=ssh://git@ssh.github.com:443/ybnmb/78.git $0"
fi
printf '%s\n' "$INFO"
case "$INFO" in
  *"Hi ybnmb"*) : ;;
  *"successfully authenticated"*) : ;;
  *) die "连上了但身份不是 ybnmb：$INFO" ;;
esac
say "身份确认 OK"

# 4. 配置 remote（幂等）
if git remote get-url origin >/dev/null 2>&1; then
  OLD="$(git remote get-url origin)"
  [ "$OLD" = "$REMOTE" ] || say "更新 origin：$OLD → $REMOTE"
  git remote set-url origin "$REMOTE"
else
  git remote add origin "$REMOTE"
fi
say "origin = $(git remote get-url origin)"

# 5. 推送（导出 GIT_SSH_COMMAND，让 git 用指定私钥）
export GIT_SSH_COMMAND="ssh -i $KEY -o StrictHostKeyChecking=accept-new"
say "推送 $BRANCH 分支 …"
git push -u origin "$BRANCH"

say "推送 v0.1.0 标签 …"
git push origin v0.1.0 || printf '（标签推送失败，可稍后单独执行：git push origin v0.1.0）\n'

say "完成。打开 https://github.com/ybnmb/78 检查。"
printf '若想连 22 端口都省了，改用 HTTPS（会要 token 而非密钥）：\n  REMOTE=https://github.com/ybnmb/78.git %s\n' "$0"
