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

Requires Java 25 for the compile toolchain, which Gradle provisions through
the foojay resolver when it is missing. Gradle 8.14 itself must run on JDK
21-24: point `JAVA_HOME` at one of those (a JDK 25 `JAVA_HOME`, as the
`collins-fabric` build wants, does not work with this Gradle version).

```bash
./gradlew build
```

The plugin jar is written to `build/libs/collins-paper-<version>-mc26.3.jar`.
GitHub Actions builds every push. A push to `main` that carries a new
`version` (in `build.gradle`) publishes the jar as Release
`v<version>-mc26.3`; pushing a `v*` tag does the same.

## Install

Drop the jar into the server's `plugins/` folder and restart. On first start
the plugin downloads `yt-dlp` and `ffprobe` into `plugins/collins-paper/tools/`
(see `config.yml`, section `ffprobe`).

## Commands (permission `collins.admin`, player only)

Every player has `collins.admin` by default, op or not. To take it away from
someone, deny it with a permissions plugin, e.g. LuckPerms:
`lp user <name> permission set collins.admin false`.

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
- `/collins remote [player]` - hand out a TV Remote (see below).

## Which sites can be played

Every nearby player's game downloads the video itself, straight from the
address on the screen. So regular players can only use **YouTube and Twitch**
links; anything else is refused with "Only YouTube and Twitch links can be
played here." and nothing changes. This applies to `/collins seturl`,
`/collins playlist ... add` and the TV Remote alike.

- **Admins**: the permission `collins.url.any` (ops by default) lets its
  holder use any public http(s) address. For a non-op admin group, e.g.
  LuckPerms: `lp group admin permission set collins.url.any true`.
- An address an admin has put on a screen is approved: regular players can
  then play, pause and resume it (and reuse that exact address), but cannot
  set a different one.
- `security.allowedHosts` in `config.yml` is the list (YouTube and Twitch as
  shipped). A listed host covers its subdomains and nothing else:
  `youtube.com.evil.example` and `evil.example/?youtube.com` do not match.
- `security.restrictHosts: false` turns the limit off (any public address for
  everyone, as before 1.7.0). Both keys are added to an existing `config.yml`
  on first start.
- Addresses saved before 1.7.0 that are not on the list stop playing at
  startup (the console names each screen) until someone with
  `collins.url.any` sets or starts them again. They are never sent to
  players' games in the meantime.
- The check is on the address as typed. Where a listed site itself redirects
  is trusted.

## TV Remote

`/collins remote` gives you a TV Remote (`/collins remote <player>` gives one
to someone else), so nobody has to type the commands above. It can also be
crafted by anyone with the permission `collins.remote.craft`, who also get
the recipe in their recipe book on joining. Every player has that permission
by default; take it away with a permissions plugin, e.g. LuckPerms:
`lp group default permission set collins.remote.craft false`. To drop the
recipe altogether, set `remote.craftable: false` in `config.yml`.

```
  .  G  .      G = glass pane
  I  R  I      R = redstone dust
  .  I  .      I = iron ingot
```

Using it needs `collins.admin`, like the commands. Everything it does goes through the same
code as the commands, and it needs nothing on the client beyond the mod.

- **Right-click while looking at a screen** (up to 200 blocks away): that
  screen's menu. Play / Pause / Resume / Stop, back and forward 30 seconds,
  loop, YouTube quality, Set URL, the playlist, and Remove screen (click
  twice).
- **Right-click anywhere else**: the list of all screens, with a New screen
  button and the server-wide global volume and hear radius.
- **Set URL** and the playlist's **Add a video** close the menu and take your
  next chat message as the URL. The message is not shown to anyone. Type
  `cancel` to back out; it also gives up after 60 seconds. Set URL starts
  playing straight away.
- **New screen**: left-click one corner block with the remote, right-click the
  opposite corner, then type the screen's name in chat. Sneak-click cancels.
- **Playlist**: click a video to play it, shift-click to remove it. The shears
  button switches to "remove mode", where a plain click removes, for clients
  that cannot shift-click.

While you hold the remote it does nothing else: it does not break, place,
hit or open anything. The item is `BLAZE_ROD` by default (`remote.material` in
`config.yml`); only a hidden tag makes it a remote, so renaming it is harmless.

## Differences from upstream

- Regular players can only play YouTube and Twitch (`security.allowedHosts`,
  permission `collins.url.any` for everything else).
- The TV Remote item and its menus (`/collins remote`).
- Ported to Paper 26.3 (paper-api 26.3.build.230-stable, Java 25).
- The Modrinth update checker is off by default (`updates.check_modrinth` in
  `config.yml`); this fork is private and never published there.
- `collins.admin` defaults to every player instead of ops only.
- Screen loop and playlists share one switch: `/collins loop <screen> on` makes a
  single video replay and a playlist start over from its first entry.
