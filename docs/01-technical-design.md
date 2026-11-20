# TouhouLittleMaidMini 技术方案

版本：`0.1-design`  
首发平台：Minecraft `26.3`、Fabric Loader `0.20.1`、Fabric API `0.165.2+26.3`、
Java `25`。NeoForge `26.3.0.101` 保留为后续适配基线。

## 0. 参考仓库与借鉴边界

本项目当前只把下面两个公开仓库作为设计参考，不把它们直接变成首版运行时依赖：

| 仓库 | 固定参考点 | 借鉴内容 | 明确不继承 |
|---|---|---|---|
| [TouhouLittleMaid-26.1](https://github.com/TouhouLittleMaid/TouhouLittleMaid-26.1) | `master`；在实现前记录实际审阅 commit | 女仆同伴的产品形态、实体/装备/管理界面需要解决的问题，以及跨版本适配的复杂度 | 不 fork；不依赖 TLM 内部类；不复制模型、声音、贴图或任务体系 |
| [minecraft-numen](https://github.com/Dwinovo/minecraft-numen) | `v0.1.5-26.3-beta`（commit `1abb37e`）；M0 时复核发布状态和实际依赖 | 外部大脑驾驶、纯 JVM MCP/权限边界、事件与长等待、插件/Skill 的扩展思路 | 不引入内置 LLM、Lua/任意脚本、Numen 假玩家身体或其完整模组架构 |

许可证边界也按仓库分别处理：TLM 仓库标注为 MIT；Numen 源码标注为 LGPL-3.0，
其美术资源另有权利声明。只有在 M0 证明确实需要 vendor Numen 的纯 JVM 模块时，才
引入对应源码和 NOTICE；不能把参考仓库中的资源默认视为本项目资产。

## 1. 结论

项目采用**Fabric 首发的独立轻量同伴模组 + 外部 MCP Agent + Skill/数据驱动的扩展方式**。

它不以“减少 TLM 的代码行数”为终点，而是替换扩展模型：TouhouLittleMaid 倾向于为每种
工作增加 Task、Behavior、GUI 和专用逻辑；本项目让 Agent 使用少量稳定动作、上下文和
Skill 组合完成未知任务。只有实时控制、安全、权限、持久化以及无法泛化的游戏交互留在
Java。

选择依据：

- TouhouLittleMaid `26.1` 仓库约有 1,500 个 Java 源文件，包含完整的实体、Brain、
  Task、模型包、GUI、网络、语音和内置 LLM。直接 fork 后再移植到 `26.3` 不符合
  “精简版”目标。
- TLM 当前公开主线仍是 `26.1.2`，没有可依赖的 `26.3` 版本。
- Numen 已有 `v0.1.5-26.3-beta` 预发行标签，并提供了不认识同伴和游戏的纯 JVM
  MCP 模块；README 的支持版本表与 Release 页面需要在 M0 一并核对，不能直接把
  Release 标签当成稳定制品。
- Fabric 26.3 已有可用的 Numen 构建基线，先复用其纯 JVM MCP 边界和加载器接缝经验；
  NeoForge 适配等 Fabric 的动作、权限和事件契约稳定后再实施。
- 自定义 Mob 更接近 TLM 的女仆形态，但会牺牲 Numen `ServerPlayer` 的通用玩家能力。
  首版明确选择“可由 AI 组合的轻量身体”，不承诺成为万能玩家。

## 2. 目标与非目标

### 2.1 目标

- 一名可召唤、可保存、可配置管理的同伴实体。
- 跟随、待命、守卫三类本地行为。
- 外部 Agent CLI 能查询状态、组合移动/观察/交互/背包动作并接收事件。
- 农作、钓鱼、整理箱子等流程由外部 Skill + 通用动作完成，不为每种玩法新增 Java Task。
- 遵守原版交互语义的新模组内容优先通过标签、ID、数据和 Skill 适配。
- Agent 断开后同伴自行进入安全状态。
- MCP 端点在专用服默认关闭，默认只监听回环地址。
- 工具调用、操作状态和错误均可测试、可审计。

### 2.2 非目标

- 不内置模型 SDK、API Key、上下文压缩或聊天记忆。
- 不实现 Lua、JavaScript 或其他任意脚本执行。
- 不暴露 `/op`、`/execute`、`/setblock` 等通用命令入口。
- 不在首版实现挖矿、搭建复杂建筑、交互任意模组 GUI。
- 不直接复制 TLM 的模型、声音或贴图资源。

## 3. 总体架构

```mermaid
flowchart LR
    CLI["Codex / Claude Code / 其他 MCP Client"]
    MCP["MCP Streamable HTTP /mcp"]
    HOST["Touhou Little Maid Mini McpHost"]
    ACTION["Companion Action Facade"]
    SERVER["Server Thread Dispatcher"]
    BODY["Companion Entity + Brain"]
    PRIM["Movement / Observation / Interaction / Inventory"]
    SKILL["External Agent Skills + Data Tags"]
    EVENT["Event Journal + Long Poll"]
    SAFE["Local Safety / Permission"]

    CLI <--> MCP
    MCP <--> HOST
    HOST <--> ACTION
    ACTION <--> SERVER
    SERVER <--> BODY
    BODY <--> PRIM
    SKILL --> CLI
    BODY --> EVENT
    EVENT --> HOST
    SAFE --> BODY
```

### 3.1 模块

| 模块 | 职责 | 是否依赖 Minecraft |
|---|---|---|
| `tlm-mini-core` | 通用动作、操作状态、事件模型、配置模型 | 否 |
| `tlm-mini-mcp` | 把 core 映射为 MCP 工具和长轮询事件 | 否 |
| `tlm-mini-fabric` | Fabric 入口、实体、Brain、动作执行器、注册、持久化和网络 | 是 |
| `tlm-mini-neoforge` | Fabric 稳定后的 Neoforge 适配层 | 是 |
| `tlm-mini-client` | 渲染、声音、管理界面和客户端同步 | 是 |

为减少首版复杂度，可以先用一个 Gradle 项目承载，但包边界必须按上表保留。首版
验收目标和 `0.1.0-beta` 发布物只要求 Fabric；纯 Java
的动作与 MCP 模型不得 import Minecraft 类型。

### 3.2 MCP 实现策略

优先评估 Numen `v0.1.5-26.3-beta` 中与目标版本匹配的 `mcp` 纯 JVM 模块：

- 纯 JVM，不依赖 Minecraft。
- 提供 Streamable HTTP、Bearer Token、限流、会话和审计。
- `McpHost` 只要求宿主提供工具表和文本结果，适合接自定义同伴实体。

阶段 0 必须记录上游标签、commit、实际 Maven 坐标和许可证文件，并验证制品是否能从
公开 Maven 获取。若制品不能稳定获取，就固定已审阅的 `v0.1.5-26.3-beta` 纯 JVM
模块并 vendor 到本项目，保留 LGPL 声明，不把 Numen 的 Minecraft 适配层带入；也可以
改为自研本项目所需的最小协议层。若上游接口与本项目的动作、权限或审计边界不一致，
以本项目的 `core` 和 MCP 契约为准。

## 4. 同伴身体

### 4.1 实体

同伴使用 `TamableAnimal` 或 `PathfinderMob` 路线，使用原版 `Brain` 管理必须实时执行的
本地行为：

- 跟随主人。
- 待命和返回家。
- 附近敌人自卫。
- 饥饿进食。
- 危险方块、坠落、窒息等安全反射。
- 长距离移动的分段寻路。

同伴不是 `ServerPlayer`。该选择意味着：

- 客户端必须安装本模组以渲染自定义实体。
- 首版不做通用玩家级挖掘、建造和 GUI 操作。
- 可以低成本实现 TLM 风格模型、动画、装备和叫声。

### 4.2 状态机

```text
DORMANT -> SPAWNING -> IDLE <-> ACTIVE
                         |        |
                         v        v
                      BLOCKED   RECOVERING
                         |
                         v
                        DEAD
```

- `IDLE`：本地 Brain 可跟随、待命或自卫。
- `ACTIVE`：执行一个已受理的移动、交互、背包动作或外部计划中的下一步。
- `BLOCKED`：等待主人授权、物品或环境条件。
- `RECOVERING`：失败后返回安全位置，或把控制权交回 Agent。
- `DEAD`：停止外部操作；重生策略由配置决定。

同一时刻只允许一个身体动作。短查询可并行，世界修改动作严格串行。

## 5. 能力边界

### 5.1 Java 本地行为

| 行为 | 为什么必须留在 Java |
|---|---|
| `follow` | 每 tick 读取主人状态，持续寻路和保持安全距离 |
| `stay` | 持续处理工作点、意外受击和返回安全位置 |
| `guard` | 即时识别威胁、检查攻击白名单和自卫 |

这些是长期策略，不是固定玩法。Agent 可以切换策略，但不能绕过本地安全优先级。

### 5.2 通用动作

首版动作面向能力，不面向某一种工作：

| 动作族 | 能力 |
|---|---|
| `observe` | 读取自身、owner、背包、附近方块/实体、容器与危险物 |
| `move` | 前往坐标、方块或实体；离开危险目标 |
| `interact` | 对方块、实体或空中使用左右键；读取真实结果 |
| `inventory` | 查看、取放、装备、丢弃、阅读书和简单容器操作 |
| `wait` | 等待完成事件，避免 Agent 高频轮询 |

Agent 根据实时结果决定下一步。例如农作流程：

```text
observe(blocks, tag=minecraft:crops)
  -> 过滤成熟方块
  -> move(target)
  -> interact(block, right_click)
  -> inventory(select seed)
  -> interact(block, right_click)
  -> 重新观察
```

这条流程没有 `TaskFarm`。新增一种遵守原版作物语义的模组作物时，Agent 只需知道方块
标签、成熟判断方式和所需物品。

### 5.3 Skill 与数据

- Skill 用 Markdown 描述玩法、前置条件、失败恢复和安全注意。
- 数据标签声明作物、种子、敌对实体和可交互方块。
- Agent 负责组合；Java 不判断“用户想做农作还是钓鱼”。
- 只有客户端专属 UI、无法用原版交互表达的模组功能才增加专用 bridge。

### 5.4 什么情况下新增 Java

只有满足以下之一：

- 每 tick 或高频控制。
- 安全、权限、持久化和服务端线程。
- 原版玩家交互不足以表达。
- 一种高频循环即使由 Agent 组合也极易出错，并且能用严格的参数 schema 描述。

新增 Java 前必须先尝试 Skill + 现有动作。评审记录必须写明为什么数据方案不可行。

每个身体操作支持：

- `accepted` 或同步拒绝。
- 唯一 `operation_id`。
- `progress`、`completed`、`failed`、`stopped` 事件。
- 主人或 MCP 停止。
- 超时与取消。
- 服务端重启后的 `resume`、`restart` 或 `safe_stop` 策略。

## 6. MCP 接口

### 6.1 传输

- 路径：`/mcp`
- 默认地址：`http://127.0.0.1:8765/mcp`
- 协议：Streamable HTTP
- 认证：`Authorization: Bearer <token>`
- Token 只存 SHA-256 哈希，不写入日志。
- 专用服默认 `enabled = false`。
- 非回环地址必须由管理员显式开启，并建议放在 HTTPS 反向代理后。

### 6.2 工具

| 工具 | 读/写 | 说明 |
|---|---|---|
| `companion_list` | 只读 | 列出该 Token 可见的同伴 |
| `companion_state` | 只读 | 位置、生命、本地行为、home、背包摘要、事件序号 |
| `companion_context` | 只读 | 按过滤条件读取附近方块、实体、容器、危险物 |
| `companion_set_behavior` | 写 | 切换 follow/stay/guard，并设置 home 与半径 |
| `companion_move` | 写 | 前往一个目标或离开一个目标 |
| `companion_interact` | 写 | 对方块、实体或空中使用左右键 |
| `companion_inventory` | 写 | 列表、移动、装备、丢弃、阅读和简单容器操作 |
| `companion_stop` | 写 | 停止当前身体操作或本地行为 |
| `companion_wait_events` | 长轮询 | 等待移动、交互、受伤、授权等事件 |
| `companion_say` | 写 | 在游戏中输出简短反馈 |

工具结果采用兼容性优先的格式：

```json
{
  "ok": true,
  "code": "accepted",
  "message": "Move accepted",
  "data": {
    "companion_id": "6d3d...",
    "operation_id": "op17",
    "action": "move"
  }
}
```

MCP 返回一段可读文本，其中包含上述 JSON。内部动作调用仍使用强类型 record，不把
文本 JSON 当作业务真源。

### 6.3 事件

每个同伴维护单调递增的 `event_seq`：

```json
{
  "seq": 42,
  "kind": "operation_completed",
  "operation_id": "op17",
  "at_game_time": 2384001,
  "data": {
    "action": "interact",
    "target": {"kind": "block", "id": "minecraft:wheat", "pos": [120, 64, -35]},
    "result": "success"
  }
}
```

`companion_wait_events` 使用 `after_seq`、`timeout_seconds`、`max_events`，默认最长
等待 50 秒。返回后 CLI 更新自己的游标，避免重复消费。

### 6.4 并发与幂等

- 所有会影响身体状态的工具由服务端线程串行执行。
- `companion_wait_events` 和只读查询不占身体执行槽。
- 写工具接受可选 `request_id`；同一 Token、同一同伴、同一 `request_id` 重复调用
  返回首次结果，防止 CLI 重试导致重复执行。
- MCP 服务端声明 `supports_parallel_tool_calls = false`。

## 7. 持久化

实体 NBT 保存：

- companion UUID、owner UUID、name。
- 当前本地行为、home、半径和日程。
- 小型背包与装备。
- 正在执行的身体操作快照、恢复策略和取消状态。
- 死亡与重生策略。

世界级 `SavedData` 保存：

- 同伴名册与 owner 索引。
- 每个同伴的事件游标与服务端 Token 作用域校验所需的非秘密元数据。

每名同伴独立追加事件日志：

```text
world/touhou_little_maid_mini/companions/<uuid>/events.jsonl
```

只追加事件不保存完整对话。轮转和保留时长由服务端配置控制。

## 8. 权限与安全

### 8.1 授权

- 只有同伴 owner 或被分享且具有操作权限的玩家可以创建 Token。
- Token 必须带 `companion_ids` 或 `all_owned` 作用域。
- Token 有过期时间、撤销状态和最后使用时间。
- 任何请求先认证，再校验作用域，最后才进入动作层。

### 8.2 危险动作

默认允许：

- 查询状态、读取附近信息、说话。
- 在 home 半径内执行规则允许的低风险动作。

默认要求真人确认：

- 攻击玩家、村民、命名生物和他人宠物。
- 离开 home 半径超过阈值。
- 使用岩浆、火焰、TNT 等危险物品。
- 从他人容器取物、向他人区域放置方块。
- 任何未来新增的破坏性动作。

确认入口在游戏内，MCP Agent 不能替真人批准。

### 8.3 网络

- 默认只监听 `127.0.0.1`。
- 仅允许 Bearer Header，Token 不得出现在 URL。
- 默认关闭 MCP，管理员显式打开。
- Origin 校验、每 Token 限流、总并发限制和失败认证限速必须启用。
- 开源默认配置不能包含固定 Token。

## 9. 客户端与界面

首版界面保持紧凑：

- 右键同伴打开单页面板。
- 顶部显示名字、owner、生命、当前本地行为。
- 中部显示 9 格小背包和装备。
- 底部设置 home、半径、日程、跟随/待命。
- MCP 连接状态只显示“已连接/未连接/已暂停”，不展示 Token。

首版使用简单人形渲染和状态图标。阶段 4 再评估兼容 `26.3` 的 GeckoLib 或自有轻量
模型包格式。TLM 代码是 MIT，但其模型、声音和贴图采用 CC BY-NC-SA 4.0；不能把
TLM 视觉资源当作普通 MIT 资源打包。

## 10. 配置

候选服务端配置：

```toml
enabled = true
host = "127.0.0.1"
port = 8765
require_token = true
disconnect_grace_seconds = 60
continue_operation_after_disconnect = false
safe_mode_after_disconnect = "stay"
```

完整示例见 [`config-examples/touhou-little-maid-mini-server.toml`](../config-examples/touhou-little-maid-mini-server.toml)。

## 11. 兼容策略

### 11.1 TouhouLittleMaid

不在首版依赖 TLM。等 TLM 发布 `26.3` 后新增可选适配层：

```text
CompanionProvider
├─ OwnedCompanionProvider
└─ TouhouMaidProvider
```

MCP 工具名和动作 schema 保持不变，只替换实体提供者。TLM 已有 Task 只作为可选能力
暴露，不成为核心 MCP 契约的一部分。

### 11.2 其他模组

首版通过通用动作和 Skill 支持：

- 原版方块和实体。
- 与原版容器规则兼容、但不需要复杂 GUI 的交互。
- 数据驱动的作物、敌对实体、物品和方块标签。
- 玩家可见的普通右键/左键、装备和使用物品。

只有当普通交互不足以表达某个模组功能时，才通过小型 action provider 注册专用能力，
不直接开放任意 Java 反射或命令执行。

## 12. 技术风险

| 风险 | 影响 | 应对 |
|---|---|---|
| `26.3` API 仍可能变化 | 编译与渲染回归 | 锁定 NeoForge 版本，周末依赖升级门禁 |
| MCP 制品不可公开获取 | 构建不稳定 | 阶段 0 验证；失败则 vendor 固定纯 JVM 模块 |
| 自定义 Mob 能力上限 | 不能通用挖建 | 明确首版动作边界；未来另立 ServerPlayer 方案 |
| 模型包兼容成本 | 视觉开发拖延 | 首版简单模型；Gecko/TLM 兼容放到后续阶段 |
| Agent 断线后失控 | 世界风险 | 默认停止外部操作，回到本地安全待命 |
| 工具表膨胀 | 上下文成本与误调用 | 首要工具不超过 10 个，上下文按类别加载 |
| 动作太原子导致模型调用过多 | 成本高、速度慢 | 上下文过滤、批量交互、Skill 约束和必要的受限循环执行器 |

