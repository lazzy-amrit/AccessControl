# AccessControl

A tiny, extremely lightweight username allow-list plugin for a Paper server in
**offline mode** with **AuthMeReloaded**. Only operators or the console manage
the list. A listed player is never blocked at join and never waits on an
AuthMe check.

## Target platform

- Paper **26.2** (latest stable build of the 26.2 release the server runs;
  "1.26.2" means Minecraft **26.2** in the new calendar versioning scheme,
  verified as a stable Paper release with latest build 26.2-133 on
  fill.papermc.io). `api-version` is set to `26.2` to match the target server.
- Paper 26.x requires **Java 25** (Java 25 became the required runtime with
  Minecraft 26.1), so the plugin is compiled with `--release 25`.
- AuthMeReloaded **6.0.x** as a **soft** dependency (`softdepend: [AuthMe]`).
  The plugin loads fine without it: listed players can still join and new adds
  are treated as pending.

## Install

1. Build with `mvn package` (see below), producing `target/AccessControl.jar`.
2. Drop the jar into the server's `plugins/` folder and restart.
3. Manage the list with `/access` (operators and console only).

## Files (in `plugins/AccessControl/`)

- `allowed.txt` — one username per line, case-insensitive. Lines starting with
  `#` are comments. A name may carry the marker `# pending`, meaning it is on
  the list but its AuthMe registration has not been seen yet, e.g.
  `Steve # pending`. The file is written atomically (temp file, then move).
  Hand edits are picked up by comparing the file's last-modified time on the
  next join or command; there is no polling or file watcher.
- `logs.txt` — the last **50** denied join attempts only, one per line:
  `yyyy-MM-dd HH:mm:ss | name | ip`. Exactly 50 maximum, oldest dropped. It is
  rewritten only when a new denial happens.

## Commands (`/access`, permission `accesscontrol.admin`, default op)

- `/access add <name>` — validates the name (3-16 chars, letters/digits/
  underscore). If already registered in AuthMe the name is added as active;
  otherwise (or if AuthMe is unavailable) it is added as **pending** and the
  sender is told the plugin is waiting for the player to register. A name
  already on the list is reported and left unchanged.
- `/access remove <name>` — removes the name from the list (active or pending)
  and kicks the player if online, with a clear message.
- `/access list` — shows all allowed names, pending ones marked.
- No args or wrong args show the usage. Subcommands tab-complete, and listed
  names tab-complete for `remove`.

## Behaviour

- **Join** (`AsyncPlayerPreLoginEvent`, lowest priority, ignoreCancelled): a
  name on the list (active or pending) is allowed immediately — no AuthMe call,
  no registration check, no delay. A name not on the list is disallowed with
  "You are not allowed on this server." and recorded in `logs.txt`. This is the
  only place denials happen.
- **Pending confirmation** (event-driven, no polling): when AuthMe fires its
  registration event for a pending name, the entry flips to active,
  `allowed.txt` is rewritten, a console line is logged, and online operators
  are messaged. A safety net checks pending names against AuthMe every 10
  minutes, but only while at least one pending entry exists — the repeating
  task is cancelled when the last pending entry is resolved. Pending entries
  never expire automatically; remove them with `/access remove`.

## Build

```
mvn package
```

Output: `target/AccessControl.jar`. No dependencies are shaded; the Paper API
and the AuthMe API are `provided`-scope only.

## Unconfirmed / assumptions

- The server version "1.26.2" is assumed to be Minecraft **26.2**; Paper 26.2
  was verified as a stable release (latest build 26.2-133) and its
  `paper-api 26.2.build.133-stable` version string format confirmed via the
  official javadoc.
- The AuthMe Maven coordinates `fr.xephi:authme-core:6.0.1` were verified
  against CodeMC's maven-metadata.xml (6.0.0, 6.0.0-R1, 6.0.1 published). The
  old `fr.xephi:authme` artifact stops at 5.7.0 and must not be used.
- `AuthMeApi#isRegistered` hits the AuthMe DataSource. AuthMe itself calls the
  DataSource from async tasks (e.g. its async register/email processes), which
  is why it is treated as async-safe; concurrent-load behaviour with an SQLite
  backend was not measured.
- Paper 26.2 was verified to exist (build 133, stable channel) and to use the
  version string format `26.2.build.<N>`; the dependency uses the documented
  Maven range `[26.2.build,)` to resolve the latest build.
