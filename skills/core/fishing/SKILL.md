---
name: fishing
description: Fish with a rod by the generic block/air interaction surface.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
  - companion_inventory
  - companion_wait_events
game_versions:
  - "26.3"
status: draft
---

# Fishing

## Goal

Catch fish with a fishing rod without a dedicated Java task.

## Current limitation

`companion_interact` supports block targets (mature crop harvest and replanting) and
dropped item entities. `target_kind=air` and non-item entities return
`unsupported_interaction`.

Fishing needs a non-player path that vanilla does not provide: `FishingHook`'s owner must
be a `Player` (`getPlayerOwner()`, `retrieve()` both depend on it). TouhouLittleMaid
solves this with its own `MaidFishingHook extends Projectile`. That is an L2 action
provider, not a fake player, and it is not part of this build.

Do not claim a catch that was not observed. Report the limitation instead.

## Procedure once a non-player fishing provider exists

1. Equip the rod with `companion_inventory action=equip item=minecraft:fishing_rod
   destination=main_hand`.
2. Move next to water with `companion_move distance=2`.
3. Cast and wait for the provider's bite event.
4. Retrieve on the bite and confirm the item delta.
5. Stop on `missing_item`, `unsupported_interaction`, `unsafe_area` or owner stop.

## Stop conditions

- `unsupported_interaction`
- `missing_item`
- `unsafe_area`
- Owner asks to stop.
