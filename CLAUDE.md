# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PawHax is a Minecraft Fabric addon built on top of [Meteor Client](https://meteorclient.com/), targeting Minecraft 1.21.4 and designed for use on the 2b2t anarchy server. It is a Java-only project built with Gradle + Fabric Loom.

## Build Commands

```bash
# Build the mod JAR (output: build/libs/)
./gradlew build

# Run the Minecraft client with the addon loaded
./gradlew runClient

# Clean then build
./gradlew clean build
```

On Windows use `gradlew.bat` instead of `./gradlew`.

There are no unit tests in this project.

## Architecture

### Entry Point

`src/main/java/com/pawhax/PawHax.java` extends `MeteorAddon` and registers all modules in `onInitialize()`. This is the only place where modules are instantiated — add new modules here.

### Module System

All feature modules live in `src/main/java/com/pawhax/modules/`. Each module extends Meteor Client's `Module` class and follows this pattern:

- Constructor calls `super(...)` with a name, description, and category (`Modules.Category.MISC` or similar)
- Settings are declared via `SettingGroup` / `Setting<T>` using Meteor's fluent builder API
- Event handlers are annotated with `@EventHandler` and subscribe to Meteor's event bus (e.g., `SendMessageEvent`, `TickEvent`)
- The module is enabled/disabled via Meteor's toggle system — no manual lifecycle management needed

### Key modules

| Module | File | Purpose |
|---|---|---|
| PawChat | `PawChat.java` | Prefix/suffix/antispam for outgoing chat |
| PawtoAnvilRename | `PawtoAnvilRename.java` | Automated anvil item renaming (largest module) |
| PawtoDyeShulkers | `PawtoDyeShulkers.java` | Automated shulker box dyeing |
| PawtoLogoutWhisper | `PawtoLogoutWhisper.java` | Sends whisper on player logout |
| InvResync | `InvResync.java` | Inventory desync correction |
| PearlGUI / PearlWheelScreen | `PearlGUI.java` / `PearlWheelScreen.java` | Pearl management HUD/screen |
| Pitch40AutoRocket | `Pitch40AutoRocket.java` | Auto-rocket at pitch 40 |

### Resources

`src/main/resources/fabric.mod.json` — mod metadata; version/name are injected at build time from `gradle/libs.versions.toml`. Do not hardcode the version string there.

## Dependency Versions

All versions are centralized in `gradle/libs.versions.toml`:

- Minecraft: `1.21.4`
- Fabric Loader: `0.18.4`
- Yarn Mappings: `1.21.4+build.8`
- Meteor Client: `1.21.4-SNAPSHOT` (from `maven.meteordev.org/snapshots`)
- Mod version: `1.0-1.21.4`
- Java target: 21

## Adding a New Module

1. Create `src/main/java/com/pawhax/modules/MyModule.java` extending `Module`
2. Register it in `PawHax.java` inside `onInitialize()`: `Modules.get().add(new MyModule());`
3. Use `@EventHandler` for event subscription — Meteor handles registration when the module is active