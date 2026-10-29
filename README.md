# collins-paper

Server half of **Collins**, the video-screen system for Minecraft: a Paper
plugin that creates screens and tells every player's client what to play on
them. The matching client mod lives in the sibling repository
`collins-fabric`; you need both.

This repository is a fork of [imsawiq/collins](https://github.com/imsawiq/collins)
by Sawiq, Zorsh and EssciZ, licensed under the GPL-3.0 (see `LICENSE` and
`NOTICE`). The fork starts from upstream commit `6d77f687adedc2814265116e76235d81d58bffbf` (branch 26.1) and
targets Paper 26.3.

## Build

Requires Java 25 for the compile toolchain; Gradle itself runs on JDK 21 (or
newer) and provisions JDK 25 through the foojay resolver when it is missing.

```bash
./gradlew build
```

The plugin jar is written to `build/libs/collins-paper-<version>-mc26.3.jar`.
GitHub Actions builds every push and attaches the jar to a Release on a `v*`
tag.

## Install

Drop the jar into the server's `plugins/` folder and restart. On first start
the plugin downloads `yt-dlp` and `ffprobe` into `plugins/collins-paper/tools/`
(see `config.yml`, section `ffprobe`).

## Commands (permission `collins.admin`, player only)

- `/collins pos1`, `/collins pos2` - mark the two corners of a flat rectangle (looking at the blocks).
- `/collins create <name>` - create the screen from the selection.
- `/collins seturl <screen> <url>` - set a YouTube / Twitch / direct video URL.
- `/collins play|pause|resume|stop <screen>`.
- `/collins loop <screen> on|off` - replay when the video ends (default off). With a playlist on the screen, the whole playlist starts over instead.
- `/collins quality <screen> [auto|360|480|720|1080|1440|2160]`.
- `/collins seek <screen> <seconds>`, `/collins back <screen> <seconds>`.
- `/collins volume set <0..2>|reset`, `/collins radius set <1..512>|reset`.
- `/collins playlist <screen> add <url>|remove <index>|play [index]|next|prev|list|clear|loop on|off|enable|disable`.
- `/collins list`, `/collins remove <screen>`.

## Differences from upstream

- Ported to Paper 26.3 (paper-api 26.3.build.230-stable, Java 25).
- The Modrinth update checker is off by default (`updates.check_modrinth` in
  `config.yml`); this fork is private and never published there.
- Screen loop and playlists share one switch: `/collins loop <screen> on` makes a
  single video replay and a playlist start over from its first entry.
