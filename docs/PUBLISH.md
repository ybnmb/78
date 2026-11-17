# 发到 GitHub

三条命令，把工作目录变成仓库：

```bash
cd paiguard
git init -b main
git add .
git commit -m "feat: 元宝派 LaTeX 渲染防护 v0.1"
gh repo create paiguard --public --source=. --push
```

没有 `gh` 就在网页上建一个空仓库，然后：

```bash
git remote add origin git@github.com:<你的用户名>/paiguard.git
git push -u origin main
```

## 发版建议

1. 打 tag：`git tag v0.1.0 && git push --tags`
2. 在 Releases 里贴 `app-debug.apk`（或 CI 产物）
3. 提一个 PR 到 [Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo)，
   这样 LSPosed 的模块仓库里能搜到（需要把仓库结构改成一个子目录形式，按它的说明来）

## 仓库里该有什么

```
README.md           别人第一眼看的东西
docs/               威胁模型、设计、路线图、生态调研
app/                源码
.github/workflows/  CI：自动跑单测 + 出 APK
LICENSE             MIT
CONTRIBUTING.md     收什么、不收什么
```

## 别提交的东西

`local.properties`（里面有本机 SDK 路径）、`app/build/`、`.idea/`。
`.gitignore` 已经写好了，`git status` 里不该出现它们。

## 第一次发布时的检查

- [ ] README 的截图/说明和你实际看到的一致
- [ ] `./gradlew assembleDebug` 在一台干净机器上能过
- [ ] 单测全绿
- [ ] 在真机上装一次，确认 LSPosed 作用域里能看到「元宝」
- [ ] 版本号写对（`app/build.gradle.kts` 里的 `versionName`）
