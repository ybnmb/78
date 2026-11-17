# 生态调研：GitHub 上跟元宝派有关的项目

开工前把能搜到的都过了一遍。结论先说：**目前没有针对元宝派的渲染防护模块**，
现存的都是"机器人客户端"和"网页版增强"两类。这个位置是空的，所以才动手写 PaiGuard。

## 一、机器人 / 协议侧

### [pugongying0616/yuanbao_bot_client-Web](https://github.com/pugongying0616/yuanbao_bot_client-Web)

Termux 上跑的 Python Web 控制台，通过 WebSocket 操控元宝派：收发消息、@全体、
撤回监听、自动回复、铭牌系统，带一个 `PluginManager` 插件生态。GPL-3.0，
基于 `anxi78/yuanbao_bot_client` 修改。

**能借鉴的**：它的插件模型（安装/启停/重载 + 生命周期钩子）比我们这个"编译期写死 hook"
灵活得多。如果 PaiGuard 以后要做"按群配置策略"，可以参考它那套。

**不能直接用的**：它是**发送侧**，跑在服务器/Termux 上，管不了"我这台手机的客户端怎么渲染"。
我们要解决的是客户端卡死，跟它不在一条链路上。

### [YuanbaoTeam/yuanbao-openclaw-plugin](https://github.com/YuanbaoTeam/yuanbao-openclaw-plugin)

官方出的 OpenClaw 通道插件，WebSocket 接入，支持私聊与群聊，protobuf 传输。

**能借鉴的**：它确认了元宝派的协议是 WebSocket + protobuf。理论上可以在**服务端**做
"消息进群前先检查公式体积"，那是釜底抽薪。但需要逆向协议、还要有群管理权限，
成本比客户端 hook 高一个量级，而且对"别人建的群"无效。

## 二、网页版增强

### [leviosa-e/yuanbao-sidebar-extension](https://github.com/leviosa-e/yuanbao-sidebar-extension)

给 yuanbao.tencent.com 加侧边栏，记录提问历史、一键复问。MutationObserver 抓 DOM，
支持 Chrome 扩展和油猴脚本两种装法。

### [yuanbao_question_selector](https://github.com/yuanbao_question_selector)

网页版的问题导航器，浮层面板 + 拖拽 + 滚动定位，走 `.hyc-content-text` 这类选择器。

**能借鉴的**：这两个证明了"网页版是另一条独立战场"。网页版渲染公式用的多半是
KaTeX / MathJax，防护思路相同（限数量、限尺寸），但实现完全不一样。
如果以后做 Web 版防护，DOM 层的 `MutationObserver` + 计数就是入口。

## 三、没有找到的

- 针对 `com.tencent.hunyuan.app.chat` 的 Xposed / LSPosed 模块
- 任何专门处理"Markwon + jlatexmath 渲染炸弹"的开源项目（通用的 Markwon 讨论里
  能搜到 LaTeX 性能问题的帖子，但没有成型的防护方案）

所以这个仓库的定位很清楚：**补上"客户端渲染防护"这个空位**，
不碰机器人、不碰协议、不碰网页版。
