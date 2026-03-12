# TreasureRun (Minecraft / Spigot Game Plugin)

A feature-rich **Minecraft (Spigot 1.20.x)** mini-game plugin built in **Java**, designed with a “production-like” mindset:
reproducible local setup (Docker + MySQL), modular gameplay systems, real-time ranking, multilingual UI (19 languages), and audio/visual effects.

> Keywords: **Java / Spigot API / Gradle / Docker / MySQL / i18n / Game Systems**

---

## Overview

**TreasureRun** is a time-limited treasure hunting mini-game:
players explore stages, find treasure chests, earn rewards, and compete on leaderboards.

This project focuses on:
- gameplay system design (stages, events, dynamic effects)
- reliable persistence (MySQL)
- UX polish (ranking notifications, effects, localization, sound design)
- reproducible developer workflow (Gradle builds + Docker-based test server)

---

## Demo

- Gameplay + effects: *(add YouTube / mp4 / GIF link here)*
- Ranking (weekly / monthly / all-time): *(add clip here)*
- i18n language switching: *(add clip here)*

---

## Key Features

### Leaderboards (Score-based, unified)
- `/gameRank weekly` / `/gameRank monthly` / `/gameRank all-time`
- Top 10 ranking output in-game
- Season-style rotation to avoid “hall of fame lock-in”
- Rank update notifications and reward hooks

### Multilingual UI (i18n)
- Language selection UI: `/lang`
- I18n key-based translation system
- YAML-based language store (`LanguagesYamlStore`) for scalable translation maintenance
- Designed for **19 languages** (ongoing refactor to replace hard-coded strings with `i18n.tr(lang, key)`)

### Dynamic Gameplay Effects
- **MovingSafetyZone (MSZ)**: moving safe zone around entities with visual/audio feedback
- **UFO caravan** stage elements and event-driven stage transitions
- Rich particle / sound effects to create a distinctive “arcade game” feel

### Audio System
- Start theme player (intro BGM)
- Chest proximity sound service (distance-based sound feedback)

### Quote / Favorites Module (Game UX)
- In-game quote/favorites features (for “collection” and replay motivation)
- Template-driven favorites content (YAML)

---

## Tech Stack

- **Language:** Java
- **Game Platform:** Spigot API (Minecraft 1.20.x)
- **Build:** Gradle
- **Database:** MySQL
- **Dev/Test Environment:** Docker (Spigot server + MySQL)
- **i18n:** YAML language files + key-based translation access
- **Persistence / Mapping:** MyBatis-style mapping (see `mybatis-config.xml`)

---

## Repository Structure
src/main/java/plugin/
├── GameStageManager.java            # stage flow + major event wiring
├── TreasureRunMultiChestPlugin.java # main plugin entry + wiring
├── RealtimeRankTicker.java          # real-time leaderboard updates
├── rank/                            # season ranking repositories
├── quote/                           # quote/favorites module
├── LanguagesYamlStore.java          # language file store
├── I18n.java                         # translation helper
├── MovingSafetyZoneTask.java        # MSZ visuals / logic
├── UfoCaravanController.java        # UFO stage behavior
├── StartThemePlayer.java            # start theme BGM
└── ChestProximitySoundService.java  # proximity audio feedback

src/main/resources/
├── plugin.yml
├── config.yml
├── mybatis-config.xml
└── favorites_templates.yml

---

## Architecture (High Level)

- **Command layer**: `/gameStart`, `/gameMenu`, `/gameRank`, `/lang`, etc.
- **Game core**: stage lifecycle managed by `GameStageManager`
- **Effects**: MSZ / UFO / particles / sounds encapsulated in dedicated classes
- **Persistence**:
  - MySQL for score history and seasonal ranking
  - repository-style classes under `plugin/rank/`
- **Localization**:
  - `I18n` + `LanguagesYamlStore` to resolve UI strings per player language

---

## Installation (Local Dev)

### 1) Requirements
- Java 17+
- Docker (for local Spigot + MySQL test)
- Gradle (wrapper included)

### 2) Build
```bash
./gradlew clean shadowJar



3) Run a local server (Docker)

This repo contains a docker-compose setup for local testing.

docker compose up -d


4) Deploy plugin

Copy the built jar into the server plugins directory.
(Example path may differ depending on your container setup.)

# Example
docker cp build/libs/*.jar minecraft_spigot:/data/plugins/
docker restart minecraft_spigot

Usage (Commands)
	•	/gameStart — start a game session
	•	/gameMenu — open game menu UI
	•	/gameRank weekly|monthly|all-time — show leaderboards
	•	/lang — open language selection UI
	•	/quoteFavorite — quote/favorite interaction

See plugin.yml for the full command list and permissions.

⸻

What I Learned (Hiring Signals)
	•	Designing a “production-like” game system:
state, timing, ranking fairness, UX feedback loops
	•	Reliable DB-backed persistence:
schema evolution, query ordering for ranking, seasonal resets
	•	Refactoring for scale:
moving from hard-coded strings to key-based i18n (i18n.tr(lang, key))
	•	Debugging discipline:
isolating issues, reproducing locally, incremental commits, backup strategy
	•	Developer experience:
Docker-based reproducible environment and repeatable deployment steps

⸻

Roadmap
	•	Complete i18n refactor: replace all hard-coded UI strings with i18n.tr(lang, key)
	•	Extract reusable modules:
	•	ranking service
	•	i18n store
	•	audio service
	•	Add CI build (GitHub Actions) + artifact output
	•	Add integration test harness for DB ranking logic

⸻

License

TBD
