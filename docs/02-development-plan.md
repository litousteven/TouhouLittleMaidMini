# 开发阶段与验收计划

## 1. 开发原则

- 先证明身体与 MCP 闭环，再扩展游戏内容。
- 每个阶段必须有可运行的端到端场景，不以“代码已经写了”代替验收。
- 基础动作只有一个入口；玩法只组合动作，不能绕过权限和事件记录。
- 新玩法默认先写 Skill 和数据；不满足 Java 准入条件时不得新增 Task 类。
- MCP schema、动作结果和事件格式一旦进入 `0.1`，变化必须带兼容迁移。
- 所有长时间动作异步受理，HTTP 调用不等待整个 Minecraft 动作完成。

## 2. 阶段依赖

```text
M0 技术验证
  ├─> M1 模组骨架与同伴实体
  │     └─> M2 MCP 垂直切片
  │           └─> M3 AI 组合能力与安全
  │                 └─> M4 客户端体验与发布准备
  └─> M5 TLM 26.3 可选适配（仅在官方 26.3 可用后）
```

`M5` 与 `M4` 可以并行，但不得阻塞 `0.1`。

## 3. M0：技术验证

目标：证明目标平台和关键依赖可行。

任务：

- 创建 `26.3` NeoForge、Java 25、ModDevGradle 项目。
- 启动 `runClient`、`runServer` 和 `runGameTestServer`。
- 注册一个无 AI 的测试实体和客户端 renderer。
- 验证实体 NBT、SavedData、客户端增量同步。
- 获取或 vendor `numen-mcp-26.3`。
- 用 MCP Inspector 或 Codex 连接一个只含 `ping`、`state` 工具的测试端点。
- 验证服务端关闭时 HTTP 线程能完整退出。

验收：

- 全新环境 `./gradlew build` 成功。
- `runClient` 召唤测试实体并看到占位渲染。
- `runServer` 可连接 MCP，返回固定状态。
- 停服后没有残留 HTTP 线程或端口占用。

Go/No-Go：

- MCP 依赖不可用且 vendor 成本超过一个迭代：暂停 M2，先解决协议依赖。
- `26.3` 自定义实体或 renderer 无法稳定编译：评估改用 Numen `ServerPlayer`，
  但该变更会重写产品边界，必须重新评审。

## 4. M1：模组骨架与同伴实体

目标：建立可单独游玩的 TLM 风格基础同伴。

任务：

- 同伴实体、owner、name、UUID 和持久化。
- 跟随、待命、返回 home。
- 生命、饥饿、进食、自卫和安全反射。
- 9 格小背包、基础装备槽。
- 召唤、删除、改名和设置 home 的命令。
- 右键打开紧凑管理界面。
- 服务端配置与服务端/客户端同步。

验收：

- 单人游戏和专用服均可召唤、保存和重新加载。
- 主人在复杂地形中移动时，同伴可在有限偏离内跟随。
- 同伴不会把主人、村民、其他玩家宠物当作默认攻击目标。
- 删除同伴前有明确确认，且背包内容不会静默消失。

## 5. M2：MCP 通用动作垂直切片

目标：外部 Agent CLI 能观察并与同伴身体完成一个可组合动作闭环。

任务：

- `/mcp` Streamable HTTP 端点。
- Bearer Token、作用域、撤销、过期与审计。
- 实现 `companion_list`、`companion_state`、`companion_context`、
  `companion_set_behavior`、`companion_move`、`companion_interact`、
  `companion_inventory`、`companion_stop`、`companion_wait_events`。
- 工具注解：read-only、destructive、idempotent。
- 长轮询事件和事件游标。
- 请求 ID 幂等。
- 客户端显示 MCP 连接状态。
- `.codex/config.toml`、连接提示词和 Token 生成流程。

验收场景：

1. CLI 列出同伴。
2. 读取位置、生命和当前本地行为。
3. 设置 `follow`，观察同伴开始跟随。
4. 查询指定标签附近方块，移动到一个方块并右键交互。
5. 设置 `stay`，等待 `behavior_changed`、`operation_completed` 事件。
6. 停止同伴运行后，下一次 CLI 请求得到明确、可恢复的连接错误。

## 6. M3：AI 组合能力与安全

目标：证明最常见玩法可以由 Skill + 通用动作完成，而不是继续增加 Java Task。

任务：

- 编写农作、钓鱼和拾取 Skill 示例。
- 为作物、种子、敌对实体和交互方块提供数据标签。
- 实现按方块/实体 ID、标签和半径过滤的上下文查询。
- 完成移动、左右键交互、装备与物品转移。
- home、半径、工作时间和日程。
- 操作快照、停止、超时和重启策略。
- 游戏内授权界面。
- 其他玩家、村民、宠物和他人容器保护。
- Agent 断开安全降级。
- 背包、物品交付和装备前置检查。
- 事件日志轮转。

验收：

- 小麦与另一种普通作物由同一套动作和不同数据/Skill 完成。
- 新增一种遵守标签约定和原版交互的作物时，不需要新增 Java 类或 MCP 工具。
- 玩家拒绝授权后，Agent 不得继续执行该动作。
- Agent 断线超过宽限期后，外部操作停止，同伴进入安全 `stay`。
- 服务端重启后身体操作按配置恢复、重开或安全终止。

## 7. M4：客户端体验与发布准备

目标：达到可公开测试的 `0.1.0-beta`。

任务：

- 简单模型、基础动画、名字与状态图标。
- 紧凑管理界面视觉整理。
- 中英文文本。
- MCP 连接引导、连接复制和故障提示。
- 配置校验、迁移与默认值。
- 性能剖析、长稳测试、安全检查。
- LGPL、MIT、第三方 NOTICE 和资产许可清单。
- 安装说明、已知限制和 Issue 模板。

验收：

- 干净环境按 README 可在 30 分钟内跑出 M2 验收场景。
- 8 名待命同伴和 4 名执行 Agent 组合动作的同伴，其服务器 MSPT 保持在可接受范围。
- 24 小时浸泡无持续内存增长、线程泄漏和端口残留。
- 公开配置不含 Token、API Key 或本机绝对路径。

## 8. M5：TLM 适配

触发条件：TouhouLittleMaid 官方发布 `26.3`，且其 API 能在不改内部类的情况下使用。

任务：

- 新增 `TouhouMaidProvider`。
- 只映射公开 `ILittleMaid`、`IMaidTask`、`EntityMaid` 能力。
- 保持 MCP 工具和动作 schema 不变。
- 可选支持 TLM 模型包的只读兼容。

验收：

- 未安装 TLM 时主模组行为和构建不变。
- 安装 TLM 后仍只暴露本模组定义的十个工具。
- TLM 内部 API 变化不能让主 MCP 层编译失败。

## 9. 0.1 MVP 范围锁

必须交付：

- 1 个同伴实体。
- 3 种本地行为：follow、stay、guard。
- 5 个通用动作族：observe、move、interact、inventory、wait。
- 10 个以内 MCP 工具。
- 1 个紧凑管理界面。
- Token、授权、审计、事件和断线降级。
- 单人、专用服、Codex E2E 测试。

可以延后：

- Gecko/TLM 模型包。
- 多同伴群组管理。
- 更多 Skill 和必要的专用 action provider。
- Fabric。
- 跨维度复杂跟随。

不得混入 MVP：

- 内置 LLM。
- 任意命令或脚本。
- 通用玩家式挖建。
- 语音、TTS、聊天记忆。

## 10. Definition of Done

一个功能只有同时满足以下条件才算完成：

- 有强类型动作 API，不依赖 MCP 文本 JSON。
- 有单元测试或 GameTest；涉及实体行为时有游戏内场景。
- 新玩法没有无理由新增 Java Task；如新增专用 Java，文档中写明通用动作为何不足。
- 成功、拒绝、超时和中断都有明确结果。
- 不阻塞服务端主线程。
- 能被 MCP 客户端观察到最终状态。
- 日志不含 Token、完整背包、对话或私有配置。
- 文档、语言文件和配置示例同步更新。

