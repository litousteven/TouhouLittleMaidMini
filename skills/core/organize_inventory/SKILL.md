---
name: organize-inventory
description: Inspect and rearrange the companion backpack and equipment.
applies_to:
  - minecraft
  - "*"
requires_tools:
  - companion_context
  - companion_inventory
game_versions:
  - "26.3"
---

# Organize inventory

## Goal

Keep the backpack usable: seeds and tools reachable, equipment worn, junk dropped only
with owner approval.

## Procedure

1. Read `companion_context` with `category=inventory`.
2. Decide what belongs in equipment using item IDs and block/entity tags, never slot
   numbers alone.
3. Move stacks with `companion_inventory action=move item=<id> count=<n>
   source=inventory destination=<slot>`.
4. Equip with `action=equip`, `destination=head|chest|legs|feet|main_hand|off_hand`.
5. Drop only when the owner has approved. The first drop returns
   `permission_required`; retry the same request after the owner answers.

## Stop conditions

- `inventory_full`
- `missing_item`
- `busy` — the destination equipment slot is occupied; pick another slot.
- `permission_required` — wait for the owner, never approve on the agent's behalf.
