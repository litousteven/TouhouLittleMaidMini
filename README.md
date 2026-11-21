# TouhouLittleMaidMini

显示名：**Touhou Little Maid Mini**

项目名：**TouhouLittleMaidMini**

一个面向 Minecraft `26.3`、Java 25、Fabric 首发的 AI-first 轻量同伴模组。项目借鉴
TouhouLittleMaid 的实体和交互形态，但**不复制它不断增长的任务类与专用逻辑**。
NeoForge 保留为 Fabric 行为边界稳定后的后续适配，不阻塞首个发行版。

核心目标不是“用更少 Java 重写一遍 TLM”，而是换掉扩展模型：

- Java 只保留实体、寻路、交互、状态、安全和版本适配等必须逐刻执行的能力。
- 外部 Agent 负责理解意图、规划、选择动作和处理未知模组。
- 新玩法和常见模组适配优先进 Skill、动作组合和数据标签，而不是新增 Java Task。
- 新增游戏版本时，只移植薄的身体适配层，不让 Agent、协议和玩法知识跟着重写。

外部 Codex、Claude Code 或其他 MCP 客户端负责推理和长期记忆；模组负责身体、
可组合动作、本地安全行为、持久化、权限和审计。

## 当前状态

**`0.1.0-beta` 发布候选（Fabric 26.3）**。M0 至 M3 已完成，M4 已落地客户端模型与
动画、紧凑管理界面、中英文文本、MCP 连接引导与复制、配置版本与迁移、MCP 并发/频率
限制、健康诊断、性能与浸泡脚本、许可证清单和发布文档。

- M0 技术验证：Go（[记录](docs/validation/M0.md)）。
- M1 同伴实体、命令、背包、装备、饥饿、守卫与安全反射、右键管理界面
  （[记录](docs/validation/M1.md)）。
- M2 MCP 垂直切片：10 工具契约、认证、作用域、审计、幂等、事件
  （[记录](docs/validation/M2.md)）。
- M3 通用能力与安全：上下文、背包、移动/交互、日程、长轮询、按同伴 Token、
  游戏内授权、断线降级、Skill 示例（[记录](docs/validation/M3.md)）。
- M4 发布准备：客户端模型/动画/状态、界面与双语、配置迁移、MCP 限制、诊断、长稳脚本、
  许可证与 Issue 模板（[记录](docs/validation/M4.md)）。

24 小时浸泡是发布操作者门禁：使用 [`scripts/soak.ps1`](scripts/soak.ps1) 在目标专用服
执行并保存 CSV。仓库内的短时验证不能替代该运行。

## 设计修订：自主模式与模块化计划（`M6`，已确认方向）

`0.1.0-beta` 只支持被动 MCP：同伴不会主动请求模型。`M5` 增加了基础脑和主动请求，
但模型只能切换行为枚举。`M6` 换成"预制动作模块 + 有界计划"：

- **无模型可用时**：同伴仍具备参考原版 TLM 的基础能力——跟随、固定在家、持武器时守卫、空手时回避并呼救、按数据标签收割成熟作物并补种、按同类归并整理登记仓库。
- **模型不写代码**：它只提交"模块序列 + 参数"的计划。模块由 Java 预制、强类型、可单测，执行前可完整校验步数、成本与风险。不引入 Lua/JS，也没有解释器。
- **大脑在客户端**：玩家在自己的客户端填写 base URL、model 与 API Key；服务端不保存 Key、不发起模型请求，只做计划校验与执行。
- **工具即授权**：主动采石要背包里有镐、砍树要有斧、开垦要有锄；没有就不做，也不会自己去箱子里翻工具。空手不打仗，被攻击时后撤并告诉主人，给了剑才还击。玩家把工具放进背包，就是最直接的控制方式。
- **两种模式**：对话模式听指令；自主模式在空闲、白天、无威胁时从"机会清单"里选择事务（收菜做饭、采石入库、整理箱子），受保护区、预算和熔断约束。
- **箱子整理学铜傀儡**：同类归并到已有该物品的箱子，不按精确顺序排；只在登记仓库之间搬，不搬进自己背包，不碰保留箱和玩家正开着的箱子。
- **模组内容自动标注**：遇到原版之外的作物、食物、工作站、敌对生物时，模型给出封闭枚举内的语义分类，写入可审阅、可回滚的知识文件，让基础脑无需专用 Java 代码也能识别新模组；它只写类别，不改真实标签与数值，`hostile` 必须玩家确认才对 `guard` 生效。
- **学习产物**：模型只能产出可审阅的 Skill/计划模板，不进行模型训练，不生成代码。
- **MCP 仍保留**：外部 Agent CLI 继续是受支持的驱动方式，与客户端计划落到同一套动作入口。

当前状态：`M5` 切片已落地（`farm` 行为、AI 设置页、主动请求、Skill 提案）；`M6.0`–`M6.2`
已落地（有界计划框架、挖掘/放置/容器/合成/熔炼、区域农作/采集/整理），77 个 GameTest
通过。`M6.3` 起的语义标签、客户端脑、自主模式与学习闭环尚未实现，建造/开垦仍需显式计划
（`till_area` 已可用），客户端对话与自主找活不可用。

设计真源见 [`docs/10-autonomy-and-scripting.md`](docs/10-autonomy-and-scripting.md) 与
[`docs/01-technical-design.md`](docs/01-technical-design.md)；`M5` 验收见
[`docs/validation/M5.md`](docs/validation/M5.md)，`M6.0`–`M6.2` 见
`docs/validation/M6.0.md`、`M6.1.md`、`M6.2.md`。

## 快速开始（30 分钟）

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\jdk-25.0.5.1+2"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:TLMM_MCP_TOKEN = -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })

.\gradlew.bat build
.\gradlew.bat :fabric:runClient '-Ptlmm.graphicsBackend=vulkan'
```

新建世界后执行 `/tlmm summon`，然后在另一个终端执行：

```powershell
.\scripts\e2e-mcp.ps1 -Token $env:TLMM_MCP_TOKEN
```

完整安装、Token、故障提示和专用服说明见 [安装与首次连接](docs/07-installation.md)。

## 文档

- [项目宪章](docs/00-project-charter.md)
- [技术方案](docs/01-technical-design.md)
- [开发记录与 0.2 计划](docs/02-development-plan.md)
- [测试、调试与本地环境](docs/03-testing-and-debugging.md)
- [MCP 工具与事件契约](docs/04-mcp-contract.md)
- [Skill 与模组适配模型](docs/05-skill-and-extension-model.md)
- [Minecraft 版本升级 Runbook](docs/06-version-upgrade-playbook.md)
- [安装与首次连接](docs/07-installation.md)
- [许可证与资产清单](docs/08-licenses.md)
- [已知限制](docs/09-known-limitations.md)
- [Codex 项目级 MCP 配置](.codex/config.toml)
- [服务端配置示例](config-examples/touhou-little-maid-mini-server.toml)
- [调试环境变量示例](config-examples/mcp-token.env.example)

## 产品边界

首版目标：

- `26.3` Fabric 首发、单同伴、三类本地行为与五种通用动作。
- 外部 Agent CLI 通过 Streamable HTTP MCP 驾驶。
- Agent 用通用动作组合完成农作、钓鱼和其他模组交互，不为每种玩法复制 Java Task。
- 同伴依赖原版 Brain、寻路与本地安全本能。
- MCP 默认只绑定 `127.0.0.1`，使用按同伴授权的 Bearer Token。
- 不提供任意命令、任意脚本、模型 Key 或服务器控制台入口。

明确不做：

- 完整 TouhouLittleMaid 功能复刻。
- Numen 式通用服务端假玩家。
- 模组内置 LLM 对话、语音识别、TTS。
- Fabric 首发路径未稳定前同步开发 NeoForge。
- 首版通用挖矿、建造和复杂模组 GUI 自动化。
- “一个玩法一个 Java Task”的扩展方式。

## 产品成功场景

1. 玩家在 `26.3` 专用服中召唤一名同伴并设置家。
2. 玩家启动 Agent CLI，CLI 通过 MCP 发现同伴和工具。
3. 玩家说“把附近成熟的小麦收了”，CLI 读取农作 Skill 和实时方块上下文。
4. CLI 依据 Skill 与实时上下文，组合扫描、移动、交互和物品操作完成收获与补种。
5. Agent 断开后，同伴停止外部操作，转为本地安全待命。
6. 权威、不可逆或可能破坏他人财产的动作必须由游戏内真人确认。

## 许可证

项目代码使用 MIT。第三方组件和资产清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
和 [许可证与资产清单](docs/08-licenses.md)。
