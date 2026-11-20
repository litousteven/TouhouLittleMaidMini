---
name: collect-items
description: Pick up nearby dropped items with the generic actions that exist in this build.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_move
  - companion_interact
game_versions:
  - "26.3"
status: draft
---

# Collect items

## Goal

Bring nearby dropped items into the companion's backpack.

## Current limitation

This build does not expose entity interaction yet: `companion_interact` with
`target_kind=entity` returns `unsupported_interaction`. Do not pretend an item was
picked up. Report the limitation and ask for a Java action provider if item collection
must be automated.

## Procedure when entity interaction becomes available

1. Use `companion_context` with `category=entities`, `entity_types=[minecraft:item]`
   and a bounded `radius`.
2. Move to the nearest item with `distance=1`.
3. Interact with `target_kind=entity`, `entity_id=<uuid>`.
4. Wait for `operation_completed`, then read the inventory to confirm the item delta.
5. Stop when the backpack is full; report `inventory_full`.

## Stop conditions

- `unsupported_interaction` — report that the build does not support item pickup.
- `inventory_full`
- `unsafe_area`
