# Bob Companion

**An experimental AI-chat companion for Minecraft Java Edition 1.21.1.** Bob appears as a human-shaped Survival player, follows you, gathers nearby blocks with his own tools, helps with a small set of tasks, and talks through chat. Microphone input is optional and off by default.

> **Project status: experimental.** This is an early community project. The source currently compiles in the maintainer's Java 21 environment, but the latest source has not yet completed an end-to-end packaged build and in-game test. Expect rough edges, make world backups, and report reproducible issues.

## What Bob can do

- Spawn as a Steve-like player using Carpet's fake-player support, and follow you with basic sprinting, jumping, and swimming movement.
- Use nearby block context when answering chat, and react to nearby hostiles.
- Mine a requested number of nearby connected blocks over time, using only tools in his own Survival inventory, then collect the drops.
- Pick up supplies that you deliberately drop near him. Bob has a separate inventory and does not take items from your inventory.
- Build a limited set of small structures, eat food from his inventory, attempt to sleep in a safe nearby bed, and trade with villagers using his own items.
- Chat through a configured OpenAI-compatible API provider. You supply and pay for your own provider key; the mod does not include one.
- Optionally accept voice input through push-to-talk. Voice input is disabled until you enable it in Bob's settings.

Bob is not a general-purpose autonomous player. He cannot reliably pathfind over long distances, craft items, farm, manage chests, or operate arbitrary menus. If he needs a tool or material, drop it near him; some tasks may require you to craft or prepare the item first. Actions are constrained to the implemented task handlers, but this project is experimental and should be tried in a copied world.

## Compatibility

| Component | Supported setup |
| --- | --- |
| Minecraft | Java Edition 1.21.1 |
| Java | 21 or newer |
| Mod loader | Fabric Loader 0.16.14 or newer |
| Required mods | Fabric API 0.116.15+1.21.1; Carpet 1.4.147 for 1.21.1 |
| Optional mods | Mod Menu 11.0.3 (settings screen); Simple Voice Chat 2.5.22 (voice integration) |
| Build | Fabric Loom 1.10; Gradle 8.12; Java 21 |

Use the same Minecraft version and loader for every mod in the profile. Bob currently targets 1.21.1 only.

## Install

### Download a build

After the first successful GitHub Actions run, open [Actions](https://github.com/YsNinja/Bob-Companion/actions), select the latest successful **Build Bob Companion** run, and download the `bob-companion` artifact. A tagged release will also publish a ready-to-install JAR under [Releases](https://github.com/YsNinja/Bob-Companion/releases) once maintainers begin making versioned releases.

### Set up a clean profile

1. Install Java 21 and [Fabric Loader for Minecraft 1.21.1](https://docs.fabricmc.net/players/installing-fabric/windows).
2. Create a separate Minecraft game directory/profile for Bob. Back up any world before testing.
3. Put the Bob JAR, Fabric API, and Carpet for 1.21.1 in that profile's `mods` folder.
4. Launch the Fabric 1.21.1 profile. Mod Menu is optional; add it with its required dependencies if you want to open settings from the Mods screen.
5. Press **K** to summon or dismiss Bob. Press **B** in a world to open Bob's settings. Key assignments can be changed in Minecraft's Controls menu.

### Configure AI chat and voice

Open Bob's settings with **B** or through Mod Menu. Choose a provider, enter your own compatible API endpoint/model and API key, then save. The key is stored in the local Minecraft config file (`config/bob-companion.json`); do not share or commit it. Provider usage may incur charges under that provider's terms.

Text chat can work without microphone access. To speak, enable voice input in Bob's settings and use the on-screen microphone control or hold **V**. Voice output and provider-specific speech services may need their own provider settings and keys. Simple Voice Chat is optional; the mod also has a client microphone capture path, whose behavior depends on the operating system and audio setup.

## Controls

| Key | Action |
| --- | --- |
| **K** | Summon or dismiss Bob |
| **B** | Open Bob settings while in a world |
| **V** (hold) | Push-to-talk when voice input is enabled |
| **P** | Toggle voice mute |

Chat to Bob using the in-game chat while he is summoned. The title-screen **Q** key opens the mod's world picker flow.

## Build from source

Requirements: Git, a JDK 21 installation, and Gradle 8.12.

```powershell
git clone https://github.com/YsNinja/Bob-Companion.git
cd Bob-Companion
gradle build --no-daemon
```

The distributable mod JAR is written to `build/libs/`. GitHub Actions runs this build for pushes and pull requests. The Windows maintainer helper `build-bob.ps1` targets the maintainer's isolated local test profile and is not needed by contributors.

## Contributing

Bug reports, focused fixes, documentation improvements, and small tested features are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request. Please include the Minecraft, Fabric Loader, Java, and mod versions plus relevant `latest.log` excerpts. Never attach API keys, config files containing credentials, or private world data.

## Project tools and dependencies

- **Java 21** — implementation and compilation target.
- **Minecraft 1.21.1 mappings by Yarn** — readable Minecraft names during development.
- **Fabric Loader and Fabric API** — mod loading, lifecycle, networking, commands, and client integration.
- **Fabric Loom** — Minecraft-aware Gradle build and remapping.
- **Carpet** — runtime fake-player implementation used for Bob's player body and Survival inventory.
- **Mod Menu API** — optional in-game configuration entry.
- **Simple Voice Chat API** — optional microphone/audio integration.
- **OpenAI-compatible chat API** — optional external language-model service, configured by each player.
- **GitHub Actions** — automated Java 21 build and downloadable CI artifacts.

## Credits and license

Bob Companion adapts code from [Quackingly by StXaviersOfficial](https://github.com/StXaviersOfficial/Quackingly), distributed under the MIT License. The upstream copyright and license are retained in [LICENSE](LICENSE). Minecraft and Fabric are trademarks and projects of their respective owners; this project is unofficial and is not affiliated with Mojang or Microsoft.

## Security

Do not commit API keys, `config/bob-companion.json`, personal recordings, or world saves. If you find a security issue, please avoid posting secrets or exploit details publicly; open a private vulnerability report through GitHub's **Security** tab if private vulnerability reporting is enabled, or contact the maintainer through the repository profile.

