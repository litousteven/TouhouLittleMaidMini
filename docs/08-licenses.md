# 许可证与资产清单

本项目的许可证是 **MIT**，全文见 [`LICENSE`](../LICENSE)。

## 1. 直接依赖

| 组件 | 版本 | 许可证 | 是否随模组 jar 分发 |
|---|---|---|---|
| Fabric Loader | `0.20.1` | Apache-2.0 | 否，由加载器提供 |
| Fabric API | `0.165.2+26.3` | Apache-2.0 | 否，由模组运行环境提供 |
| Fabric Loom | `1.14.10` | MIT | 否，仅构建时使用 |
| Gradle | `9.7.1` | Apache-2.0 | 否，仅构建时使用 |
| Gson | `2.14.0` 声明 | Apache-2.0 | 否，Minecraft 运行时已提供 |
| JUnit Jupiter | `5.11.4` | EPL-2.0 | 否，仅测试 |

## 2. Minecraft 与运行时库

Minecraft `26.3`、Mojang mappings、LWJGL、Mixin、ASM 以及 Minecraft 的传递依赖由
玩家启动器或 Fabric Loom 获取，本项目不重分发。它们仍受各自许可证约束：

- Minecraft 与 Mojang 资产：专有许可，不得从本仓库重新分发。
- LWJGL：BSD-3-Clause。
- SpongePowered Mixin：MIT。
- ASM：BSD-3-Clause。
- JNA 等部分 Minecraft 运行时库使用 LGPL-2.1；本项目不修改也不重分发它们。

完整说明和引用来源见 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 3. 资产

| 资产 | 位置 | 许可证 |
|---|---|---|
| 同伴像素皮肤 | `client/src/main/resources/assets/tlmm/textures/entity/companion.png` | 项目 MIT，原创生成 |
| 调试实体纹理 | 运行时引用原版 `minecraft:textures/entity/zombie/zombie.png` | 不复制、不重分发 |
| 字体与 GUI | 运行时使用原版资源 | 不复制、不重分发 |

没有使用第三方模型、声音、音乐、字体文件或 GUI 贴图。`DebugCompanionRenderer` 只用于
M0 探针，发行版主同伴使用 `CompanionRenderer`。

## 4. 参考仓库

设计参考了 TouhouLittleMaid 和 minecraft-numen，但没有复制其源码、内部类、模型、
纹理或其他资产。审阅结论和依赖边界见
[`docs/01-technical-design.md`](01-technical-design.md#0-参考仓库与借鉴边界)。
