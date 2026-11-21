# Third-party notices

Touhou Little Maid Mini itself is licensed under the MIT License (see `LICENSE`).
This file lists the third-party software and assets used to build, run or validate the
project. The project does not bundle Minecraft, Fabric Loader, Fabric API or Gson into
the released mod jar; they are provided by the game, launcher or loader. Build and test
dependencies are not redistributed either.

## Runtime and build dependencies

| Component | Version used here | License | How it is used |
|---|---|---|---|
| Fabric Loader | `0.20.1` | Apache-2.0 | Runtime loader; not bundled |
| Fabric API | `0.165.2+26.3` | Apache-2.0 | Runtime API; not bundled |
| Fabric Loom | `1.14.10` | MIT | Gradle build plugin; not bundled |
| Gradle | `9.7.1` (wrapper) | Apache-2.0 | Build tool; not bundled |
| Gson | `2.14.0` (declared) | Apache-2.0 | JSON for the MCP transport; Minecraft provides the runtime copy |
| JUnit Jupiter | `5.11.4` | EPL-2.0 | Unit tests only; not bundled |
| Minecraft / Mojang mappings | `26.3` | Proprietary | Game runtime; not bundled or redistributed |
| LWJGL | Minecraft-provided | BSD-3-Clause | Minecraft runtime dependency |
| SpongePowered Mixin | Minecraft/Fabric-provided | MIT | Loader runtime dependency |
| ASM | Fabric Loom-provided | BSD-3-Clause | Build-time bytecode tooling |

The Minecraft jar and its transitive libraries are downloaded by the user's launcher or
Fabric Loom. Their licenses and notices remain in those distributions. In particular,
some Minecraft runtime libraries use LGPL-2.1 (for example JNA); this project does not
modify or redistribute those libraries.

## Assets

- `client/src/main/resources/assets/tlmm/textures/entity/companion.png` is an original
  pixel-art texture generated for this project and is covered by the project MIT license.
- The debug-only entity renderer references the vanilla zombie texture at runtime by
  resource id. The texture is not copied into this repository or the mod jar.
- The mod uses the vanilla font and no third-party model, sound, music or GUI asset.

## Reference projects

The design was informed by TouhouLittleMaid and minecraft-numen. No source file, internal
class, model, texture or other asset from those projects is copied into this release.
See [`docs/01-technical-design.md`](docs/01-technical-design.md) for the review and
dependency conclusions.
