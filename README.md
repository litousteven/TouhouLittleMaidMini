# TouhouLittleMaidMini

显示名：**Touhou Little Maid Mini**

项目名：**TouhouLittleMaidMini**

一个面向 Minecraft `26.3`、NeoForge、Java 25 的 AI-first 轻量同伴模组。项目借鉴
TouhouLittleMaid 的实体和交互形态，但**不复制它不断增长的任务类与专用逻辑**。

核心目标不是“用更少 Java 重写一遍 TLM”，而是换掉扩展模型：

- Java 只保留实体、寻路、交互、状态、安全和版本适配等必须逐刻执行的能力。
- 外部 Agent 负责理解意图、规划、选择动作和处理未知模组。
- 新玩法和常见模组适配优先进 Skill、动作组合和数据标签，而不是新增 Java Task。
- 新增游戏版本时，只移植薄的身体适配层，不让 Agent、协议和玩法知识跟着重写。

外部 Codex、Claude Code 或其他 MCP 客户端负责推理和长期记忆；模组负责身体、
可组合动作、本地安全行为、持久化、权限和审计。

当前状态：**技术设计与开发计划已完成，尚未开始实现代码。**

## 文档

- [项目宪章](docs/00-project-charter.md)
- [技术方案](docs/01-technical-design.md)
- [开发阶段与验收](docs/02-development-plan.md)
- [测试、调试与本地环境](docs/03-testing-and-debugging.md)
- [MCP 工具与事件契约](docs/04-mcp-contract.md)
- [Skill 与模组适配模型](docs/05-skill-and-extension-model.md)
- [Minecraft 版本升级 Runbook](docs/06-version-upgrade-playbook.md)
- [参考仓库与借鉴边界](docs/01-technical-design.md#0-参考仓库与借鉴边界)
- [Codex 项目级 MCP 配置](.codex/config.toml)
- [服务端配置示例](config-examples/touhou-little-maid-mini-server.toml)
- [调试环境变量示例](config-examples/mcp-token.env.example)

## 产品边界

首版目标：

- `26.3` NeoForge 单加载器、单同伴、三类本地行为与五种通用动作。
- 外部 Agent CLI 通过 Streamable HTTP MCP 驾驶。
- Agent 用通用动作组合完成农作、钓鱼和其他模组交互，不为每种玩法复制 Java Task。
- 同伴依赖原版 Brain、寻路与本地安全本能。
- MCP 默认只绑定 `127.0.0.1`，使用按同伴授权的 Bearer Token。
- 不提供任意命令、任意脚本、模型 Key 或服务器控制台入口。

明确不做：

- 完整 TouhouLittleMaid 功能复刻。
- Numen 式通用服务端假玩家。
- 模组内置 LLM 对话、语音识别、TTS。
- Fabric/NeoForge 双加载器同步开发。
- 首版通用挖矿、建造和复杂模组 GUI 自动化。
- “一个玩法一个 Java Task”的扩展方式。

## 产品成功场景

1. 玩家在 `26.3` 专用服中召唤一名同伴并设置家。
2. 玩家启动 Agent CLI，CLI 通过 MCP 发现同伴和工具。
3. 玩家说“把附近成熟的小麦收了”，CLI 读取农作 Skill 和实时方块上下文。
4. CLI 依据 Skill 与实时上下文，组合扫描、移动、交互和物品操作完成收获与补种。
5. Agent 断开后，同伴停止外部操作，转为本地安全待命。
6. 权威、不可逆或可能破坏他人财产的动作必须由游戏内真人确认。

