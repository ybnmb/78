# 发到 GitHub

**当前状态**：本地仓库已就绪 —— `main` 分支有一次提交，`v0.1.0` 标签已打。
只差一个远程地址。**这两步不要重做。**

```bash
# 已完成，别重复执行
# git init -b main
# git add -A && git commit
# git tag -a v0.1.0
```

## 一条命令推上去

```bash
GH_TOKEN=ghp_xxxxxxxx ./scripts/push-to-github.sh 你的用户名 paiguard public
```

脚本做的事：认 git 身份 → 有脏改动先提交 → `gh repo create` 建仓并推送 →
推 `main` 和 `v0.1.0` 标签。已有 `origin` 就跳过建仓，只推送。

不想给 token，就自己在本机跑：

```bash
gh auth login                                   # 浏览器授权
gh repo create 你的用户名/paiguard --public --source=. --remote=origin --push
git push origin --tags
```

没有 `gh` 的话先在网页上建空仓库，然后：

```bash
git remote add origin git@github.com:你的用户名/paiguard.git
git push -u origin main --tags
```

## 推完紧接着做两件事

1. **挂 APK**：Releases → Draft a new release → 选 `v0.1.0` → 上传
   `paiguard-0.1.0-debug.apk`。CI 每次构建也会产出 artifact，但 Release 才有固定下载链接。
2. **提 Xposed 模块仓库**：给 [Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo)
   提一个仓库，LSPosed 的在线模块列表里才能搜到。它要求一个特定结构的子目录，
   按它 README 的说明来 —— 这步可以在有第一批用户之后再做，不影响使用。

## 仓库里该有什么

```
README.md           别人第一眼看的东西（原理 / 装法 / 配置 / 限制）
docs/               THREAT-MODEL 威胁模型 · DESIGN 架构取舍 · ECOSYSTEM 生态调研
                    ROADMAP 未做的功能 · PUBLISH 本文
app/                源码（core 判定 / hooks 挂载 / data 存储 / ui 设置页）
app/src/test/       16 个纯逻辑单测
scripts/            push-to-github.sh
.github/workflows/  CI：跑单测 + 出 APK
LICENSE             MIT
CONTRIBUTING.md     收什么、不收什么
```

## 别提交的东西

`local.properties`（含本机 SDK 路径）、`app/build/`、`.gradle/`、`*.hprof`。
`.gitignore` 已覆盖。自查：`git ls-files | grep -E "local.properties|build/"` 应当无输出。

## 开源前的自查

- [x] `assembleDebug` 通过，APK 里 `assets/xposed_init` 与 xposed meta-data 齐全
- [x] 16 个单测全绿
- [ ] **真机验一遍**：LSPosed 里勾选「元宝」作用域，日志出现 `[PaiGuard] 已挂载`
- [ ] README 里的描述和你实际看到的一致（尤其包名，见 `core/Targets.kt`）
- [ ] `versionName` 与 tag 对得上（当前 `0.1.0`）
