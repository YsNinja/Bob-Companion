# Development status

Bob Companion is an experimental adaptation of Quackingly for Minecraft Java 1.21.1. This document records implemented behavior and the current verification boundary; a successful compile does not mean every feature has been tested in game.

## Implemented in source

- Carpet-backed fake player spawned in Survival mode with a Steve-like body and a separate player inventory.
- Follow behavior with basic sprinting, collision-triggered jumping, and vertical swimming control.
- Nearby block and crosshair-target context for the chat/action planner.
- Nearby resource gathering over Survival ticks, including a requested connected-block count, Bob-owned tools, drop pickup, and hostile-threat pause behavior.
- Limited construction, hostile defense, food use, nearby-bed sleep attempts, villager trading, and player-requested item handover.
- Clickable HUD microphone control and hold-to-talk input. Microphone use is disabled by default.
- In-game settings screen, reachable with B; Mod Menu integration is optional.
- OpenAI-compatible provider handling with graceful errors for missing response text.

## Known limitations

- Bob has no general-purpose long-distance pathfinding, crafting, farming, chest management, or arbitrary GUI interaction.
- Gathering searches loaded nearby blocks (up to 16 blocks) and uses simple movement; it is not a reliable mining expedition system.
- Bob needs supplies dropped near him. He cannot craft missing tools or materials.
- AI replies and parsed action plans depend on an external provider and may be delayed, unavailable, or imperfect. In-game action handlers impose local bounds, but this remains experimental code.
- Voice input/output depends on device audio support and optional provider/voice-chat configuration; it is not enabled by default.

## Verification record

- The source was previously compiled with Java 21 against cached Minecraft/Fabric dependencies.
- The full Gradle build has succeeded on the maintainer's Windows PowerShell setup. A subsequent source change added a player-inventory access guard; a new packaged build and an in-game verification pass are still required before treating the current public source as a tested release.
- No current claim is made that the latest source has passed the GitHub Actions build or a complete in-game regression session. CI will provide the first clean public build result after this repository is populated.

## Required in-game checks before a stable release

1. Launch a copied survival world with Minecraft 1.21.1, Fabric API, and Carpet.
2. Confirm Bob's inventory is separate; test that he cannot remove, equip, or consume host inventory items without the explicit handover interaction.
3. Test a multi-block gathering request with Bob-owned and missing tools; check block drops and tool durability.
4. Test follow, swimming, threat response, food use, bed sleep, construction, trading, and item handover.
5. Test settings, API errors, and optional voice input with microphone permission enabled only for that test.
6. Check `latest.log` and crash reports, and repeat with optional mods removed to identify dependency assumptions.
