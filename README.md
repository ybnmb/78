# PaiGuard · 元宝派公式防护

> 一个 LSPosed 模块：在元宝派（`com.tencent.hunyuan.app.chat`）里，
> 把 LaTeX 公式刷屏和递归宏炸弹挡在渲染之前，让群聊不会因为一条消息卡死或闪退。

原因很简单：元宝派用 Markwon + `io.noties.markwon.ext.latex` 渲染 AI 回复里的公式。
这个链路把公式丢进 `jlatexmath` 现场解析、现算排版、现场画 Bitmap。一条消息里塞几十个
`$$...$$`，或者一个 `\def\x{\x}\x`，就是几百毫秒到几秒的 CPU，外加一张可能申请失败的
大图 —— 表现就是：消息列表卡住、输入框没反应、运气不好直接闪退。

PaiGuard 不修改元宝派的安装包，不改协议，不做任何联网。它在运行时挂几个 hook，
在公式到达渲染器之前做判断。

---

## 它怎么工作

三道路障，逐层收紧。任何一层拦下，后面的都不会执行。

| 层 | 挂在哪 | 做什么 | 拦不住的时候 |
|---|---|---|---|
| 入口 | `Markwon.setMarkdown / toMarkdown / render` | 数这一条消息里有多少个 `$$`、最长多长、嵌套多深、有没有危险宏 | 已经被别的路径渲染过的消息 |
| 解析 | `org.scilab.forge.jlatexmath.TeXFormula` 构造 | 净化源码（删宏定义、裁尺寸、截断），并发限到 2 路 | 完全不经过 TeXFormula 的渲染器 |
| 兜底 | `ThreadGroup.uncaughtException` | 接住 LaTeX 栈溢出，别让线程池的 `Error` 把整个进程带走 | OOM 只记录不吞 |

两个开关决定"拦多狠"：

- **默认模式**：小公式照常渲染，只有超阈值才断电。适合正常用群聊、偶尔看公式的人。
- **踹飞模式**：`JLatexMathPlugin.configure` 直接变成空方法，公式永远只显示源码。
  适合"我根本不看公式，别让它浪费我一颗 CPU 周期"的人。

中间那个"断电"是什么意思：把 `$$` 中间插一个零宽空格（`$\u200B$`）。
肉眼几乎看不出来，但解析器认不出这是公式，于是乖乖当普通文字渲染 —— 内容还在，
只是不排版了。比 `throw` 好：抛异常会让 Markwon 显示一片空白，用户以为消息丢了。

---

## 装上

前提：Android 8.1 以上，已 root，已装 Magisk + LSPosed。

```bash
git clone <你的仓库地址> paiguard
cd paiguard
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者在 GitHub Actions 里直接下 artifact（`.github/workflows/build.yml` 已经配好）。

装完在 LSPosed 里启用模块，**作用域勾上「元宝」**（元宝派是元宝 App 里的一个功能，
包名 `com.tencent.hunyuan.app.chat`），然后强制停止元宝再打开。

第一次生效可以这样验证：

1. 打开模块 App，确认「总开关」和「LaTeX 防护」都开着
2. 找个人（或者自己拉个派）发一条带公式的消息，比如 `测试 $$\frac{a}{b}$$`
3. 公式正常显示，说明常规模式在工作
4. 发一条 `$$\def\x{\x}\x$$`，应该看到 Toast 提示，并且公式变成一段源码文字
5. 回到模块 App，「最近事件」里应该多了一条记录

---

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| 总开关 | 开 | 关掉等于没装 |
| LaTeX 防护 | 开 | 只关公式防护，其他 hook 仍在 |
| 踹飞公式渲染 | 关 | 打开后 `JLatexMathPlugin` 不注册任何渲染器 |
| 拦截危险宏 | 开 | `\def` `\newcommand` `\input` `\catcode` 等 |
| 每秒最多渲染公式数 | 24 | 超过就熔断 10 秒 |
| 单个公式字符上限 | 1200 | 超过直接不渲染 |
| 熔断时弹提示 | 开 | 宿主进程里的 Toast |
| 熔断时发通知 | 开 | 模块进程发的系统通知，锁屏也能看到 |

改配置不用重启元宝 —— 宿主每秒重新读一次 prefs。

内部还有几个没暴露到界面的阈值（`GuardConfig` 里）：硬上限 60 个公式、嵌套深度 12、
尺寸 40em、并发 2 路。觉得误杀太多就去调，这些默认值是拿"正常消息不该被拦"当第一优先
定的，不是"宁可错杀"。

---

## 它不做什么

这几条是有意为之，写在代码里，也写在这里：

- **不上传任何内容**。事件只记在手机本地的一个 SQLite 里，最多 500 条，只存哈希和计数。
  公式原文不落盘（`PoisonBook` 只存 SHA-256 前 8 字节）。
- **不修改元宝派的代码**。所有改动都在内存里，卸载模块 + 重启就是原样。
- **不接管网络**。它跟消息收发没有任何关系。
- **不在别的 App 里生效**。作用域只勾元宝。

---

## 已知限制

老实说，这个版本解决的是最常见的一类问题，不是全部：

1. **"可视窗口"是代理指标**。真实可见性需要 hook RecyclerView 的 layout 过程，
   兼容性太差。现在用的是"1 秒窗口内累计渲染量"——快速滚动时效果接近，但严格来说
   不是同一件事。
2. **只认 `$$` 分隔符**。元宝派用的是 `$$...$$`。如果哪天换成 `\(...\)`，需要加规则。
3. **不防图片炸弹**。Markdown 里的 `![](超长URL)`、base64 巨图、超大尺寸远程图，
   同样能把内存打满，这一块还没做（见路线图）。
4. **OOM 不吞**。内存已经见底时继续跑，容易出现半渲染的鬼状态，不如让系统重启来得干净。
   我们能做的是在它发生之前熔断。
5. **不同版本可能需要改包名**。`core/Targets.kt` 里是两个已知包名，渠道包不同的话
   自己加一行。

---

## 路线图

- [x] 三道闸 + 熔断 + 事件记录
- [x] 踹飞模式（一键让公式插件失效）
- [ ] 图片炸弹防护：尺寸上限、data URI 体积上限、远程图先量后载
- [ ] 代码块高亮炸弹：超过 N 行关掉 Prism 高亮
- [ ] 按发送者记忆：连续触发 3 次的人，本会话内消息一律纯文本
- [ ] 白名单：长按消息「这条允许渲染」，避免误杀数学讨论
- [ ] 规则热更新：不重新发版也能加新的危险宏特征

---

## 开发

```bash
./gradlew :app:testDebugUnitTest   # 纯逻辑层单测（扫描器 + 净化器）
./gradlew assembleDebug
```

代码分层，尽量让"判断逻辑"和"Android 框架"分开 —— 这样阈值调参、
新的攻击样本，都可以先写成一个单测跑通，再去改 hook：

```
core/LatexScanner.kt      纯 Kotlin：数公式、量深度、认危险宏、算成本
core/LatexSanitizer.kt    纯 Kotlin：删定义、裁尺寸、平衡截断
core/Guard.kt             决策：三道闸的判定与熔断状态机
core/Config.kt            prefs -> GuardConfig（宿主进程每秒读一次）
core/PoisonBook.kt        毒公式指纹（只存哈希）
core/Reporter.kt          事件上报（ContentProvider，跨进程）
hooks/PluginHook.kt       JLatexMathPlugin.configure -> 踹飞
hooks/MarkwonHook.kt      Markwon 入口 -> 数一遍
hooks/RenderHook.kt       TeXFormula 构造 -> 净化 + 限流 + 限字号
hooks/CrashHook.kt        ThreadGroup.uncaughtException -> 兜底
data/*                    事件库、通知、Provider（模块进程）
ui/MainActivity.kt        设置页
```

加一条新的危险特征，改 `LatexScanner.DANGEROUS` 或 `DIMENSION_CMDS`，
然后在 `LatexScannerTest` 里加一个用例 —— 这两步都过了，才算改完。

---

## 构建状态

- Android SDK 34 / AGP 8.5.2 / Gradle 8.7 下 `assembleDebug` 通过，产物约 5.5 MB
- 16 个纯逻辑单测全绿（扫描器 9 个、净化器 7 个）
- **未在真机上验证**：写这个模块的环境里没有装了 LSPosed 的设备。
  所以"hook 在元宝派里确实生效"这一条需要你装上去自己确认 ——
  日志里看到 `[PaiGuard] 已挂载 ...` 就说明挂上了；看不到就查作用域和包名。

## 相关

- [生态调研](docs/ECOSYSTEM.md)：GitHub 上跟元宝派有关的项目都过了一遍，
  机器人客户端、网页版增强各有一批，渲染防护这块是空的
- [威胁模型](docs/THREAT-MODEL.md)：一条消息怎么把群聊打死，八种手法逐一对应
- [设计说明](docs/DESIGN.md)：为什么 hook 这三个点，为什么降级用零宽空格
- [路线图](docs/ROADMAP.md)

## 免责

这是一个针对**客户端渲染性能**的防护工具，不是安全产品。它不阻止别人发消息，
也不改变服务端行为。按 MIT 协议使用，出问题自己负责。

如果你在元宝派里遇到真的崩溃，顺手抓一份 logcat（`adb logcat -b crash`），
欢迎开 issue —— 带上版本号、机型、以及那条消息的源码（脱敏后）。
