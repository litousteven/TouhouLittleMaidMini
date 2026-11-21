# 已知限制（`0.1.0-beta`）

本文件只列出当前版本真实存在、已验证的边界。未实现的内容不要通过提示词或 Skill 假装
完成；Agent 应读取错误码并调整计划，或明确报告缺少可表达的交互。

## 1. 游戏与平台

- 首发和主验证加载器是 Fabric，Minecraft `26.3`，Java `25`。NeoForge 仍在后续阶段，
  当前不提供等价玩法。
- MCP 只能绑定回环地址。需要远程访问时请使用 SSH 隧道，不要修改绑定公网地址。
- 模型能力有两种来源：外部 MCP Agent CLI，或在游戏内配置 OpenAI 兼容 base URL、model name 与 API Key 后的直连客户端。模组不内置第三方 LLM SDK、STT 或 TTS。
  - **当前 `0.1.0-beta`（M5 切片）**：直连请求由服务端发起，Key 存放在服务端凭据文件。
  - **`0.2`（M6）起**：模型调用迁移到玩家客户端，服务端不再保存 Key 或发起请求；见 [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md)。
- 未配置模型时只能使用确定性基础脑：跟随、驻守、守卫/自卫和作物收割补种。
- 专用服没有玩家时不能执行 `/tlmm summon`；它需要玩家上下文。协议健康检查不受影响。
- `0.2` 起自主模式依赖主人客户端在线；客户端退出后同伴只保留基础行为。

## 2. 交互能力

- 空气交互和钓鱼不支持：原版 `FishingHook` 的 owner 必须是 `Player`。非玩家钓鱼需要
  后续 L2 自定义 `Projectile` provider，本版本不会伪造完成。
- `companion_interact target_kind=entity` 目前只支持掉落物 `ItemEntity` 的拾取。村民、
  动物和其他需要 `Entity.interact(player, ...)` 的实体会返回 `unsupported_interaction`。
- 方块交互目前支持成熟 `CropBlock` 收获，以及空方块下方为耕地时的补种。其他方块
  交互（按钮、机器、容器 GUI）明确返回 `unsupported_interaction`。
- 容器上下文会返回已加载普通容器的槽位内容；上锁容器只返回 `locked = true`，不返回
  内容。不返回完整 NBT。
- 当前版本没有箱子整理：只能逐次 `inventory` 取放，没有同类归并、目的地规划或数量守恒
  校验，也不能整仓库清理。目标设计见 [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) §3.5，**尚未实现**。
- 当前版本没有工具授权门控：`farm` 由行为开关控制，而不是由背包内工具决定；也没有
  "缺工具就不做"的规则。目标设计见 [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) §3.4，**尚未实现**。
- 当前版本空手也会对威胁目标还击（`guard` 的现有实现），没有"空手只后撤并呼救"的行为；
  `0.2` 起按 §3.4 改为持有剑或斧才进入还击与巡逻。
- 当前版本（`0.1.0-beta` / M5 切片）中，模型响应只能切换 `stay/follow/guard/farm`、给主人短反馈和提出 Skill 提案；尚不能编排多步动作来建造、开垦或操作工作台。M6 的模块化计划设计见 [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md)，**尚未实现**。
- 当前版本没有模组内容标注：`farm` 只认原版 `CropBlock` 子类、成熟方块状态和现有种子标签；不继承该体系、或需要语义判断的新作物（例如右键采摘的灌木、自定义成熟属性）不会被识别，模型也不会自动生成分类。目标设计（知识叠加层、批量标注、审阅与回滚）见 [`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) §10，**尚未实现**。
- 不支持无模型时的通用挖矿、搭桥、建造；即使 M6 开放动作模块，也只覆盖已注册模块与限额内的操作，不能保证完成任意建筑或模组 GUI 流程。
- 不支持任意命令、任意脚本、动态加载、模型训练或服务器控制台操作；模型只能提交白名单模块的有序计划。

## 3. 客户端

- 模型是原版人体网格加蝴蝶结的简单占位模型，不是 Gecko/TLM 模型包；首版不加载
  TouhouLittleMaid 模型包。
- 本机开发客户端必须使用 Vulkan 后端。少数启动会在 unifont 资源加载阶段以
  `0xC0000005` 或 `0xC0000409` 退出；删除 `run/clientGametest/options.txt` 和截图缓存
  后重试即可，未观察到 Java 异常或模组侧堆栈。该问题与模组逻辑无关。
- 管理界面固定为 176x240 的紧凑布局；极小窗口或高 GUI 缩放时可能需要调整缩放。
- 授权提示是可点击聊天消息，不是独立授权窗口；只有 owner 或 OP 可以决定。

## 4. 服务端与安全

- MCP Token 明文只在签发时显示一次，服务端只保存 SHA-256 摘要。模型 API Key 在 `0.1.0-beta` 由服务端凭据文件保存；`0.2` 起改由客户端保存，不经服务端。两种情况都不进入日志或审计。
- 模型 API Key 不是端到端加密：能读取对应配置目录的人仍能取得该文件，生产环境应限制文件系统权限。M5 遗留的服务端 `[llm]` 配置在 `0.2` 中废弃，升级后建议手动删除旧凭据文件。
- 当前破坏性动作（丢弃物品）要求真人授权。其他目标类（他人容器、其他玩家、村民）
  的配置项已解析并保留，但对应交互尚未开放，因此这些开关目前不会触发新的动作。
- 外部操作不会被持久化；服务端重启后只恢复持久化的本地行为，不恢复未完成的 Agent
  计划。
- Agent 断线超过 `disconnect_grace_seconds` 后会停止导航并发 `mcp_disconnected` 事件，
  然后进入配置的安全模式（默认 `stay`）。

## 5. 性能与长稳

- M4 GameTest 的 12 名同伴场景（8 待命 + 4 本地行为）实测平均 MSPT 约 `0.3`，单次
  最大约 `1.3`（测试服、无真实玩家负载）。这是下限参考，不是生产服容量承诺。
- 24 小时浸泡必须由发布操作者使用 [`scripts/soak.ps1`](../scripts/soak.ps1) 在目标
  专用服上执行。脚本记录 MSPT、堆、线程和事件积压，并在线程增长、堆增长或单 tick
  超预算时失败。仓库不保存 Token 或本机绝对路径。
- 真实多人、跨维度和大量实体场景仍需在发布前按
  [`docs/03-testing-and-debugging.md`](03-testing-and-debugging.md) 的矩阵执行。

## 6. 扩展模型

- 新玩法优先使用数据标签、Skill 和现有动作组合。只有逐刻控制、客户端专属交互或安全
  检查无法由 Agent 可靠完成时，才新增 L2 Java provider。
- MCP 工具面保持 10 个以内；新增 provider 不得为每种作物或机器新增顶层工具。
- 具体适配层级和准入测试见 [`docs/05-skill-and-extension-model.md`](05-skill-and-extension-model.md)。
