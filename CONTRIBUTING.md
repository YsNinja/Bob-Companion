# Contributing to Bob Companion

Thanks for considering a contribution. Bob is experimental, limited to Minecraft Java 1.21.1, and still needs careful in-game testing. Small, reviewable changes are easiest to diagnose and maintain.

## Before opening an issue

Search existing issues first. For a bug report, include:

- What you expected and what happened.
- Steps to reproduce, including the exact command or chat request.
- Minecraft, Fabric Loader, Fabric API, Carpet, Bob, and Java versions.
- Relevant log excerpts from `.minecraft/logs/latest.log` or the crash report.
- Whether the issue reproduces with only required dependencies installed.

Remove API keys, account identifiers, voice recordings, and unrelated personal data before attaching logs. Do not attach `config/bob-companion.json` unless all credentials have been removed.

## Development setup

1. Install Git, JDK 21, and Gradle 8.12.
2. Clone the repository and run `gradle build --no-daemon` from its root.
3. Test in a separate Fabric 1.21.1 game directory with Fabric API and Carpet installed. Use a disposable world or a backup.
4. Keep provider credentials in the local Minecraft config. Never add secrets or generated caches to a commit.

GitHub Actions builds pull requests automatically. Include the build result and any in-game checks you performed in the pull request description. If a behavior cannot be tested in game, say so clearly.

## Pull requests

- Keep changes focused and explain the player-facing effect.
- Preserve compatibility with Minecraft 1.21.1 unless the pull request is explicitly a version port.
- Do not add broad world-edit behavior, creative-mode shortcuts, or access to the host player's inventory. Bob should use his own Survival inventory; transfer items only through a clear player-requested handover.
- Treat chat, model output, and provider responses as untrusted input. Validate action names, counts, block/item identifiers, distances, and world state before acting.
- Update README or status documentation when features, requirements, controls, or known limits change.
- Preserve the upstream Quackingly MIT attribution and include appropriate license notices for any new third-party code.

## Review scope

Maintainers review behavior, compatibility, security, performance, clarity, and test evidence. Submissions are welcome, but a passing CI build alone does not establish that an in-game behavior is safe or correct.
