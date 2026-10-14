# XinConnect

> 面向**任意标准 Minecraft 服务器**的 [xinbot](https://github.com/huangdihd/xinbot) 通用元插件（MetaPlugin）+ 可配置登录规则引擎。

xinbot 非常轻量，但它的元插件形式注定需要**为每个服务器编写专用插件**才能运行——连接地址、登录/注册/排队流程在每个服务器都不同。XinConnect 的目标是：**不写代码，只改配置**，让 xinbot 连上任意服务器并自动跑通其登录流程。

## 它解决什么

| 层 | 职责 | 实现方式 |
| --- | --- | --- |
| **连接层** | 决定连到哪个服务器 | 读取 `xinconnect.conf` 的 `connection.host/port`，单阶段直连 |
| **登录层** | 适配各服不同的 register / login / 排队 / 验证流程 | **事件驱动状态机 + 规则链**：命中规则 → 执行动作 |

差异化登录被抽象为「**当收到某个信号（聊天 / actionbar / 菜单 / 阶段变化）满足某些条件时，执行某些动作（发命令 / 发消息 / 切换阶段 / 交互物品与容器）**」，全部写在配置文件里，无需为每个服务器编码。

## 特性

- **通用元插件**：一个 jar 适配所有服务器，只改配置。
- **规则引擎**：条件支持 `contains / notContains / equals / startsWith / regex / stage / notStage / sender / overlay`，并可用 `all / any / not` 组合嵌套。
- **会话状态机**：`stage` 变量在不同登录阶段间流转；断开自动重置。
- **动作丰富**：发命令、发聊天、切阶段、写变量、打日志、命令 Tab 补全，以及**物品/容器交互**（使用物品、切换快捷栏槽位、点击容器槽/按钮、关闭窗口）。
- **上下文插值**：动作里可用 `{{password}}`、`{{text}}`、`{{sender}}`、`{{stage}}`、`{{containerId}}`、正则捕获 `{{g1}}…` 等变量。
- **内置流程模板**：`authme`、`register-then-login`、`join-login-fallback`、`queue-join-item`，选名字即可用，与自定义规则合并。
- **自动窗口跟踪**：监听服务器打开菜单的包，记录 `containerId`，让容器动作无需硬编码窗口 id。
- **热重载**：`xinconnect reload` 改配置立即生效；`debug = true` 打印每条信号便于编写规则。

## 前提

- **JDK 17**
- **Maven 3.8+**
- 运行宿主：**xinbot 2.0.0**（XinConnect 以 `provided` 作用域依赖它，通过 JitPack 获取）

## 构建

```bash
mvn clean package
```

产物：`target/XinConnect-1.0.jar`（已用 maven-shade 打包）。

## 安装

1. 把 `XinConnect-1.0.jar` 放入 xinbot 的 `plugin/` 目录。
2. **移除其它元插件**（如某服务器专用插件）——每个 xinbot 进程只允许一个 MetaPlugin。
3. 首次启动会在 xinbot `config.conf` 同目录自动生成 `xinconnect.conf`，编辑它后重启。

## 快速开始

```hocon
connection {
  host = "play.example.net"
  port = 25565
}

credentials {
  password = "yourPassword"
  email = ""
}

# 直接选用内置模板，大多数服务器只改 host + password 即可跑通
templates = ["authme"]
```

## 配置结构

顶层键：

| 键 | 说明 |
| --- | --- |
| `connection` | `host`（必填）、`port`（默认 25565） |
| `credentials` | 登录凭据，可在规则里用 `{{password}}` 等引用 |
| `variables` | 任意自定义变量，同样以 `{{名字}}` 引用 |
| `debug` | `true` 时打印每条信号与跳过原因 |
| `templates` | 选用内置流程模板（自动展开成规则） |
| `rules` | 自定义规则链：命中 `when` → 顺序执行 `actions` |

`templates` 与 `rules` 会**合并**，统一按 `priority` 升序评估。

### 规则条件（`when`）

```hocon
when {
  types        = ["SYSTEM_CHAT", "PUBLIC_CHAT", "OVERLAY"]  # 信号来源
  contains     = ["Please log in", "/login"]                # 文本包含任一
  notContains  = ["logged in", "successfully"]              # 命中则排除
  equals       = "GAME"                                     # 文本全等
  startsWith   = "..."
  regex        = "(?i)position[: ]+(\d+).*queue"            # find，捕获组 -> {{g1}}…
  stage        = ["LOGGED_IN"]                              # 仅当当前阶段属于该数组
  notStage     = ["LOGGED_IN"]                              # 当前阶段落在其中则不命中
  sender       = "SomePlayer"                               # 发送者名（仅 PUBLIC/PRIVATE_CHAT）
  overlay      = true                                       # 仅 actionbar(true)/聊天(false)
  ignoreCase   = true                                       # 默认忽略大小写
  all / any / not = { ... }                                 # 组合子条件
}
```

### 规则动作（`actions`，按序执行，字符串支持 `{{变量}}` 插值）

文本 / 会话类：

```hocon
{ command = "login {{password}}" }     # 发送命令（可带或不带前导 /）
{ chat    = "hello" }                  # 发送聊天消息
{ stage   = "LOGGED_IN" }              # 切换阶段
{ setVariable = { key = "value" } }    # 写入会话变量
{ log     = "文本" }                    # 打印日志
{ tab     = "msg {{sender}}" }         # 命令 Tab 补全请求
```

物品 / 容器交互类（需已连接且处于游戏状态，否则该动作自动跳过）：

```hocon
{ useItem        = "MAIN_HAND" }                              # 使用手持物品
{ selectSlot     = 0 }                                        # 选择快捷栏槽位（0-8）
{ closeContainer = 0 }                                        # 关闭窗口（0=玩家背包）
{ containerButton = { containerId = 0, button = 1 } }         # 点击容器按钮
{ containerClick  = { slot = 11, button = 0 } }               # 点击容器槽位（省略 containerId 自动用当前窗口）
```

可用插值变量：`credentials` / `variables` 中的键，外加 `{{text}} {{sender}} {{stage}} {{type}} {{overlay}} {{containerId}}` 以及正则捕获 `{{g1}}…`。

### 规则修饰

```hocon
priority     = 100    # 越小越先评估（默认 100）
once         = true   # 整场会话仅触发一次
cooldownMs   = 4000   # 两次触发最小间隔毫秒
stopOnMatch  = true   # 命中后终止后续规则
```

### 完整示例

```hocon
templates = ["authme"]

rules = [
  {
    id = "queue-info"
    priority = 30
    cooldownMs = 10000
    when {
      types = ["SYSTEM_CHAT", "OVERLAY"]
      regex = "(?i)position[: ]+(\d+).*queue"
    }
    actions = [
      { setVariable = { queuePos = "{{g1}}" } },
      { log = "当前排队位次:{{g1}}" }
    ]
  }
  {
    id = "pick-menu-item"
    priority = 35
    once = true
    when {
      types = ["WINDOW_OPEN"]
      contains = ["选择", "Select", "Kit"]
    }
    actions = [
      { containerClick = { slot = 11, button = 0 } }
    ]
  }
]
```

## 内置模板

运行 `xinconnect templates` 可查询。

| 名称 | 用途 |
| --- | --- |
| `authme` | AuthMe 类：`/login`、`/register <pwd> <pwd>`，登录完成置 `LOGGED_IN` |
| `register-then-login` | 先按邮箱 `/register` 再 `/login` |
| `join-login-fallback` | 进服后未登录时兜底补发一次 `/login` |
| `queue-join-item` | 排队时选中原物品槽并使用主手物品（点击物品离开排队） |

## 命令

| 命令 | 别名 | 说明 |
| --- | --- | --- |
| `xinconnect reload` | `xc`、`xconnect` | 重新加载配置与规则 |
| `xinconnect status` | | 显示目标地址、规则数、当前阶段、调试状态 |
| `xinconnect templates` | | 列出可用内置模板 |
| `xinconnect try <文本>` | | 用给定文本模拟一条系统聊天信号，便于调试规则匹配 |

## 信号来源（`types`）

`SYSTEM_CHAT`、`PUBLIC_CHAT`、`PRIVATE_CHAT`、`OVERLAY`、`SERVER_CHANGE`、`DISCONNECT`、`WINDOW_OPEN`。

## 目录结构

```
src/main/java/top/mcocet/
├── XinConnect.java                 # 主类（MetaPlugin 契约 + 生命周期）
├── config/XinConnectConfig.java    # 配置解析（HOCON）
├── engine/
│   ├── Signal.java                 # 归一化信号
│   ├── Condition.java              # 条件（叶子 + 组合）
│   ├── Action.java                 # 动作模型
│   ├── ActionExecutor.java         # 动作 -> Bot 调用 / serverbound 包
│   ├── Rule.java / RuleEngine.java # 规则与评估引擎
│   ├── RuleContext.java            # 会话状态机（stage/变量/once/cooldown）
│   └── TemplateLibrary.java        # 内置流程模板
├── listener/RuleListener.java      # xinbot 事件 -> Signal 适配层
└── command/XinConnectCommandExecutor.java

src/main/resources/
├── plugin.yml          # type: META_PLUGIN
├── commands.yml
└── xinconnect.conf     # 默认配置模板 + 注释 + 示例规则
```

## 已知边界

- **单阶段直连**：收到登录包即判定为游戏阶段；不适用于需要多阶段（Login→Game 分离）的特殊服务器。
- **容器交互尽力而为**：`containerClick` 不跟踪真实 `stateId` / 背包内容，适用于服务端托管的登录/排队菜单；对严格校验 `stateId` 的原生容器可能被服务端拒绝。
- `custom` 动作为预留扩展点，暂未执行。
