# Skill 与模组适配模型

本项目的“智能”分为确定性基础脑和模型脑：基础脑覆盖高频、可预测的原版行为；模型脑
来自外部 Agent CLI 或**玩家客户端**的直连模型。模型不写代码，只提交“动作模块序列 +
参数”的计划。为避免重新走回“每个模组写一批 Java Task”的路线，适配必须按能力层级递进。

## 1. 四个适配层级

### L0：通用动作

适用于遵守原版交互语义的内容：

- 看到方块或实体。
- 走到交互距离。
- 右键、左键或使用手中物品。
- 读取背包变化。
- 等待结果。

这一层不需要任何模组专用代码。Minecraft 本身和大多数“能用玩家手完成”的内容都属于
这一层。

### L1：标签、数据与 Skill

需要理解“什么算成熟作物”“哪些是种子”或“这个机器应如何按顺序操作”时：

- 用 tags/IDs 描述对象。
- 用 Markdown Skill 描述流程、前置条件和失败恢复。
- 不改变 MCP 工具。

这是新增玩法的主要路径。

标签的来源分四档，优先级从高到低：真实数据（食物组件、燃烧值、方块状态、实体父类）→
真实标签（`#minecraft:crops`、`#c:ores`、模组自带 tag）→ 玩家手写的知识叠加层 →
模型提案的分类 → 内置启发式。**叠加层不能移除或覆盖真实标签，也不能改写任何数值**，
它只回答“这个对象在语义上属于哪一类”。

模型的作用是把 L1 自动化：遇到模组新增的作物、食物、工作站、敌对生物时，按封闭枚举
给出分类并附理由，写进 `config/tlmm/knowledge/<namespace>.json` 供玩家审阅。
`hostile` 属于危险分类，必须先经玩家确认才对 `guard` 生效。完整规则见
[`10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) §10。

### L2：Java Action Provider

普通交互和 Skill 都无法可靠表达时：

- 只有少量、强类型、可权限检查的专用动作。
- 复用 Action、Event、Permission 和 Audit 的唯一入口。
- MCP 仍通过既有工具映射，不为每个 Provider 新增顶层工具。

例子：一个模组的服务端行为需要特殊参数或需要读取非原版状态。

### L3：Client Bridge

功能只存在于客户端 GUI、输入或渲染状态时：

- 新增受限的 client bridge。
- 服务端仍负责最终权限与权威状态。
- 客户端不能收到任意“执行这个 Java 方法”的请求。

这是成本最高、使用范围最小的层级。

## 2. Java 准入测试

新增内容必须先回答：

1. L0 通用动作能否完成？
2. 能否通过标签、ID、方块状态或实体状态完成判断？
3. 能否用 Skill 描述顺序与恢复？
4. 是否确实需要每 tick 控制？
5. 是否无法由服务端得到必要状态？

如果答案支持 Skill/数据方案，则不得新增专用 Java Task。

### 2.1 工具是前置条件，不是可选优化

任何需要工具的能力，前置检查必须写成硬条件，而不是"尽量找一把"：

- 采石要镐、砍树要斧、开垦要锄；工具等级不够同样拒绝。
- 自主模式只查背包（含装备与双持），**不查箱子、不主动取用**。玩家把工具放进背包，
  就是最直接的授权动作。
- 缺工具时机会不生成，避免"规划完才失败"；正在做时工具被拿走，立即返回 `tool_lost`。

这条规则同样约束 Skill：Skill 可以描述"用镐挖 32 个圆石"，但不能描述"先去箱子拿镐"。
后者属于玩家命令，不属于自主流程。

箱子整理的算法不交给模型：同类归并、目的地选择、数量守恒都是确定性 Java 逻辑，
模型只能决定"要不要整理"。参考铜傀儡的判定规则见
[`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md) §3.5。

## 3. Skill 目录

建议结构：

```text
skills/
├─ core/
│  ├─ follow_owner/
│  │  └─ SKILL.md
│  ├─ harvest_crops/
│  │  └─ SKILL.md
│  └─ organize_inventory/
│     └─ SKILL.md
└─ compat/
   └─ <mod_namespace>/
      └─ <task_name>/
         └─ SKILL.md
```

静态 Skill 随仓库发布，可由 Codex、Claude Code 或其他 CLI 从工作目录加载，也可作为
游戏内直连模型请求的系统上下文。模型提出的新 Skill 只能先写入
`config/tlmm/learned-skills/` 作为提案；经玩家确认并移动到 `skills/compat/` 后才可执行。

## 4. Skill 元数据

建议 frontmatter：

```yaml
---
name: harvest-crops
description: Harvest mature crops and replant when seeds are available.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
  - companion_inventory
game_versions:
  - "26.3"
---
```

`applies_to` 使用模组 ID 或通配符，避免把模组名称写进 Java。

## 5. Skill 正文

Skill 应包含：

- 目标与完成条件。
- 必要物品和工具。
- 先观察什么，不要求 Agent 偷看未加载状态。
- 动作顺序。
- 可公开观察的成功与失败信号。
- 可重试和不可重试错误。
- 权限、危险和停止条件。
- 一个最小示例。

农作示例：

```markdown
## Goal
Harvest mature crops in the configured radius and replant with the same crop's seed
when one is available.

## Procedure
1. Query `companion_context` with `category=blocks` and the mature-crop tag or block IDs.
2. Read `block_state`; only act on crops whose state means mature.
3. Move to each crop with `arrive_distance=2`.
4. Use the correct click or tool from the skill.
5. Collect dropped items with `companion_move` and `companion_interact`.
6. Select a matching seed through `companion_inventory`.
7. Replant into an air or replaceable block above suitable farmland.
8. Repeat until no mature crops remain or the action budget is exhausted.

## Stop conditions

- permission_required
- inventory_full
- missing_item
- companion hurt below the configured threshold
- owner asks to stop
```

Skill 不包含隐藏代码，不能要求模型执行 shell、Lua 或任意 Java。Skill 里可以内嵌
**计划模板**（模块序列 + 参数占位符的 JSON/Markdown 片段），供模型照抄改参数；模板只是
数据，仍要经过服务端校验才能执行。

模块清单、计划 schema 与校验规则见
[`docs/10-autonomy-and-scripting.md`](10-autonomy-and-scripting.md)。

## 6. 结果反馈

每步动作必须返回：

- 是否成功。
- 实际观察到的方块或物品变化。
- 玩家可见的错误原因。
- 可执行的下一条修复提示。

避免把整个背包或大范围世界反复回传。Agent 应按需调用上下文类别和过滤器。

## 7. 模组适配示例

### 普通模组作物

- 如果使用原版 `CropBlock` 语义：L1 数据 + Skill。
- 如果使用自定义成熟状态：L1 Skill 描述状态判定，必要时增加只读数据 provider。
- 如果必须通过客户端按钮收获：L3 bridge。

### 通用容器机器

- 如果客户端界面只是普通槽位：L0。
- 如果有配方与输入输出顺序：L1 Skill。
- 如果状态只在服务端私有对象：L2 只读 provider。
- 如果操作必须点击客户端按钮：L3 bridge。

### 战斗

- 守卫白名单、保护主人、主动攻击附近怪物、每 tick 瞄准与安全反射：基础脑 Java。
- 选目标优先级、何时撤退、武器策略：模型 + Skill。
- 玩家、村民、命名生物和他人宠物永远不能由模型自行选为目标。

### 建造与工作台

- 基础脑不自动建造，也不打开 GUI。
- 配置模型后，模型通过 `place_blocks`、`craft_item`、`smelt_items` 等模块表达意图；
  模块内部负责朝向、碰撞、配方匹配与槽位操作，不依赖客户端界面。
- 建造受限于模块的规模上限（如 64 格蓝图）与保护区规则；不提供 `build_structure`
  这类无界能力。
- 需要客户端按钮或私有模组状态时，转入 L2/L3 provider；模型不得直接调用 Java 方法。

## 8. Skill 质量门

一个 Skill 进入仓库前需要：

- 至少一个原版或测试模组场景。
- 成功、缺物品、权限拒绝和部分完成案例。
- 不用内部 UUID 或不可观察状态作为唯一成功依据。
- 不依赖一个过大的单个工具输出。
- 由真实 Agent 跑一次 E2E，而不是只做文本 lint。

## 9. 反模式

- 为每种作物复制一遍 Skill，只改一个 ID。
- 把大多数逻辑写进一个“万能 act”命令。
- Skill 直接要求任意命令。
- 在多个 Skill 中复制权限与危险动作规则。
- 为了一个模型调用方便而新增固定工作模式。

