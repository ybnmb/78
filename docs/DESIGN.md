# 设计说明

## 为什么是 hook，而不是改包

改包（apktool 重打包）有三个问题：签名变了要重装、版本一更新就得重来、元宝派的
聊天界面几乎每个版本都在动。Xposed 的好处是只依赖**第三方库**的类名，而
Markwon / jlatexmath 是成熟库，类名几年不变。

所以整个模块的 hook 点只落在三个地方：Markwon 的入口方法、jlatexmath 的
`TeXFormula` 构造、以及 `ThreadGroup`。这三处都属于"库"，不属于业务代码。

## 数据流

```
                    ┌──────────────────────────────────────────┐
   消息进入          │  MarkwonHook.setMarkdown(text)           │
   RecyclerView ───▶ │    ├─ LatexScanner.scan(text)            │
                     │    ├─ 数量/长度/深度/宏 判定             │
                     │    └─ 超阈值 → Guard.neuter(text)        │
                     └───────────────┬──────────────────────────┘
                                     │ 改写后的文本（或原样）
                                     ▼
                     ┌──────────────────────────────────────────┐
                     │  JLatexMathPlugin (可选：踹飞)           │
                     │    configure() ──▶ [killPlugin] 直接返回 │
                     └───────────────┬──────────────────────────┘
                                     ▼
                     ┌──────────────────────────────────────────┐
                     │  RenderHook: new TeXFormula(latex)       │
                     │    ├─ PoisonBook 命中？ → 占位           │
                     │    ├─ 熔断中？ → 占位                    │
                     │    ├─ LatexSanitizer.sanitize()          │
                     │    ├─ 并发信号量 tryAcquire()            │
                     │    └─ 埋时间戳，构造完算耗时             │
                     └───────────────┬──────────────────────────┘
                                     ▼
                     ┌──────────────────────────────────────────┐
                     │  jlatexmath 解析 → 排版 → Bitmap         │
                     │    抛 Error？ ──▶ CrashHook 接住         │
                     └───────────────┬──────────────────────────┘
                                     ▼
                     ┌──────────────────────────────────────────┐
                     │  Reporter ──ContentResolver.call()──▶    │
                     │  模块进程 EventStore(SQLite) + Notifier  │
                     └──────────────────────────────────────────┘
```

## 三个设计选择

### 1. 降级用"打断分隔符"，不用抛异常

`Guard.neuter()` 在每个 `$$` 中间插一个零宽空格。原因是：

- 抛异常 → Markwon 走 `errorHandler` → 用户看到空白，以为消息丢了
- 直接删掉公式 → 内容丢失，更糟
- 换成 `[公式已省略]` → 需要重新生成整段文本，且丢失原文

插 ZWSP 之后，原文一个字没少，只是解析器认不出公式了。用户想看图就自己复制出去看。

### 2. 熔断是时间窗，不是开关

一次误判不应该让用户永久看不到公式。所以 `trippedUntil = now + 10s`，
到点自动恢复。攻击者连续刷屏，就每次都被这 10 秒挡住；正常用户碰巧踩到阈值，
10 秒后一切如常。

### 3. 只存哈希，不存原文

`PoisonBook` 只存 SHA-256 前 8 字节。理由有两个：群里别人的内容不该被我们留档；
哈希足够用来判重。事件表里存的 `detail` 只有"多少个公式、多长、什么宏"，没有原文。

## hook 点清单

| 目标 | 方法 | 模式 | 版本依赖 |
|---|---|---|---|
| `io.noties.markwon.Markwon` | `setMarkdown` / `toMarkdown` / `render` | `hookAllMethods` | Markwon 4.x |
| `io.noties.markwon.ext.latex.JLatexMathPlugin` | `configure` | `hookAllMethods` | ext-latex 4.3+ |
| `org.scilab.forge.jlatexmath.TeXFormula` | 全部构造 | `hookAllConstructors` | jlatexmath-android 1.x |
| `ru.noties.jlatexmath.JLatexMath` | 参数含 TeXFormula 且含 float 的方法 | 反射遍历 | 同上 |
| `java.lang.ThreadGroup` | `uncaughtException` | `hookAllMethods` | 系统类 |

方法名能不写死就不写死：`JLatexMath` 那层用"参数类型里出现 TeXFormula"来匹配，
比 `getTeXIcon` 这种具体名字抗版本变化。

## 线程模型

- 判定在调用线程上同步做（就是 UI 线程或 Markwon 的渲染线程）。所以判定必须快：
  扫描是纯字符遍历，2000 字符的文本在 1ms 以内。
- 解析信号量是**非阻塞** `tryAcquire`。拿不到就当场降级，绝不阻塞 UI 线程。
- Toast / 通知都在主线程或模块进程里发，不占渲染线程。
- 事件上报走 `ContentResolver.call`，同步但极短；失败就只写 Xposed 日志。

## 一个被否掉的方案

**hook RecyclerView 的 `onBindViewHolder`，只处理可见 item。**

看起来更准确，实际上：

1. 元宝派的列表适配器是混淆过的，类名每个版本都变
2. `onBindViewHolder` 拿不到"这一条现在是否真的可见"
3. 快速滚动时 binding 的 item 数远多于可见数，统计反而更不准

最后选了"1 秒窗口内累计渲染量"作为代理指标。它不完美，但它不依赖任何业务类。
