# TouhouLittleMaidMini 项目约定

## 当前阶段

`0.1.0-beta` 与 `M5` 双脑切片已完成并验证。`M6` 方向已确认：模块化计划 +
客户端大脑。当前处于 `M6.0` 之前的实现准备阶段，尚未开始写模块框架代码；
按 `docs/10-autonomy-and-scripting.md` 推进。

## 不可改变的边界

- 首发和主验证加载器是 Minecraft `26.3`、Fabric、Java 25。NeoForge 保留为后续适配，
  不阻塞首个 Fabric `0.1.0-beta`。
- 采用三脑模式：无模型的基础脑、外部 MCP Agent、客户端直连模型。**模型调用只在客户端**：
  服务端不保存 API Key、不发起模型请求，只做计划校验与执行。发行模组不内置第三方
  LLM SDK、STT 或 TTS。
- 无模型时必须具备参考原版 TLM 的基础能力：跟随、回家驻守、持武器时守卫并主动攻击威胁主人的怪物、按数据标签收割成熟作物并补种、按同类归并整理登记仓库。空手不参与战斗，被攻击时后撤并播报，不还手。
- 模型不写代码，只提交"模块序列 + 参数"的计划。动作模块由 Java 预制、强类型、可单测；
  模块清单不随模组数量增长。
- 不为每种作物、工作或模组新增一个 Java 模块。玩法决策优先由本地基础行为、Skill、
  数据标签和通用模块组合完成；模型只扩展未知场景，不替代安全规则。
- 模组内容由模型补语义分类，不写注册表：只能从封闭枚举取类别，写入可审阅、可回滚的
  `config/tlmm/knowledge/*.json`。不得覆盖真实标签或数值；`hostile` 必须玩家确认才对
  `guard` 生效。
- 工具即授权：主动作业只用同伴背包现有的工具，缺工具不生成机会、不静默降级；
  自主模式永不从箱子取工具或装备。玩家把工具放进背包就是授权动作。空手不还手，
  被攻击时只后撤并向主人播报；有剑或斧才进入还击与巡逻。
- 箱子整理是确定性 Java 算法（同类归并 + 数量守恒），只在登记仓库之间移动，不搬进同伴
  背包、不碰保留箱与玩家正在使用的容器；模型只能决定是否整理，不能决定怎么搬。
- MCP 默认只能绑定回环地址，专用服默认关闭。
- 不提供任意命令、任意脚本、反射执行或服务器控制台工具；不接受宿主级代码、Lua/JS
  或动态加载。
- 世界修改动作必须经过权限、事件和审计的唯一入口。
- 不把 TouhouLittleMaid 的内部类作为首版依赖。

## 设计真源

- 愿景与边界：[`docs/00-project-charter.md`](docs/00-project-charter.md)
- 架构：[`docs/01-technical-design.md`](docs/01-technical-design.md)
- 阶段与验收：[`docs/02-development-plan.md`](docs/02-development-plan.md)
- 测试和调试：[`docs/03-testing-and-debugging.md`](docs/03-testing-and-debugging.md)
- MCP 契约：[`docs/04-mcp-contract.md`](docs/04-mcp-contract.md)
- Skill 与模组适配：[`docs/05-skill-and-extension-model.md`](docs/05-skill-and-extension-model.md)
- 版本升级 Runbook：[`docs/06-version-upgrade-playbook.md`](docs/06-version-upgrade-playbook.md)
- 自主模式与模块化计划：[`docs/10-autonomy-and-scripting.md`](docs/10-autonomy-and-scripting.md)

设计变化先改文档，再改实现。实现与文档冲突时，不能默认文档错误；必须明确决定哪一份
是新的真源。

## 代码边界

开始建立 Gradle 工程后，目录目标为：

```text
core/      纯 Java 的通用动作、计划模型、事件和状态模型
mcp/       MCP host、参数解码和协议映射
llm/       模型 HTTP 客户端与响应解析，纯 Java，不依赖 Minecraft
fabric/    Fabric 入口、实体、Brain、动作模块、计划执行器、注册和持久化
neoforge/  实体、Brain、动作执行器、注册和持久化
client/    渲染、界面、客户端模型循环与计划提交
```

`core`、`mcp`、`llm` 不得 import Minecraft、Fabric 或 NeoForge。模型请求线程不得直接修改世界；
客户端不得持有权威世界状态，计划只能通过 C2S 包进入服务端校验后执行。

## 验证顺序

1. 修改纯模型：运行相关 JVM 单测。
2. 修改 MCP：运行协议测试、认证与幂等测试。
3. 修改实体、本地行为或动作执行器：运行相关 GameTest。
4. 修改 MCP 到实体的接缝：运行最小 CLI E2E。
5. 交付阶段成果前运行完整构建和本阶段验收脚本。

失败时查根因，不用重复运行掩盖不稳定测试。涉及服务端线程、动作或重连的回归不得只靠
单测证明。

## 日志规则

- 禁止记录 Token、完整背包、聊天正文和本机绝对路径。
- Tool 日志只写 request ID、Token 摘要 ID、工具名、companion ID、耗时、结果码。
- 调试开关可以增加序号、耗时和状态迁移，但不能扩大敏感数据范围。

## 变更规则

- MCP 工具名进入 `0.1` 后保持稳定。
- 新字段优先以可选字段加入；删除或重命名字段需要新 major schema 版本。
- 新玩法先尝试本地基础行为、Skill、数据标签和现有动作组合。
- 只有逐刻控制、客户端专属交互或安全性不能在模型层可靠完成时，才新增 Java Provider。
- 模型“学习”只产生可审阅的 Skill/计划模板，不进行模型训练，也不生成代码。
- 新增动作模块必须声明参数 schema、风险等级、预估成本与失败码，并复用 Action、Event、
  Permission 和 Audit 的现有入口。
- 服务端新增世界修改能力必须同时更新测试矩阵和安全文档；客户端不得拥有权威世界状态。

## 升级规则

- 与 Minecraft/NeoForge 强耦合的代码只放在 `neoforge/` 和 `client/`。
- 与 Minecraft/Fabric 强耦合的代码只放在 `fabric/` 和 `client/`。
- `core/`、`mcp/`、Skill 和动作 schema 不依赖具体 Minecraft 类。
- 每个受支持游戏版本必须有一条迁移检查表，记录 API 重命名、包名变化和运行验证结果。
- 不允许为了少写一个动作而把某种玩法逻辑放进 MCP 传输层。


