# Waystone (SoultideLauncher repo)

Waystone is the multi-server launcher: it lists every world from the VPS hub (`/waystone/api/servers`),
installs and updates each world's client, then runs it on the Java it needs. The old single-server Soultide
launcher (`Launcher.java`, the `/launcher/*` routes below) is still in the repo for old installs.

## Getting it

- **Windows, no Java required:** grab `Waystone-Setup.exe` from the
  [latest release](https://github.com/NemesisRSPS/SoultideLauncher/releases/latest) and run it - a
  real installer with Java bundled inside, built by `jpackage` (see `.github/workflows/build.yml`).
  The same installer is also uploaded as `SoultideLauncher-Setup.exe`, the name old Soultide
  launchers self-update from, so existing players move to Waystone automatically.
- **Any OS with Java already installed:** grab `SoultideLauncher.jar` from the same release and run
  `java -jar SoultideLauncher.jar`, or double-click it if your system runs jars on double-click.

## How it fits together

- **SoultideClient** publishes a GitHub Release (private repo) whenever a `vX.Y.Z` tag is pushed -
  see that repo's `.github/workflows/release.yml`. The release includes the built jar (as a stable
  `SoultideClient.jar` asset) and everything in `launcher-assets/` (currently `load.png`,
  `login.png`).
- **SoultideCacheEditor**'s web service (already running on the VPS) proxies that release via two
  unauthenticated routes - `GET /launcher/version` (latest tag + asset list) and
  `GET /launcher/download/{assetName}` (streams one asset). It holds the only GitHub credential
  involved (a read-only, repo-scoped token, `LAUNCHER_GITHUB_TOKEN`) - this launcher never sees a
  GitHub token at all, by design, since the client repo is private and this jar ends up on every
  player's machine.
- **This launcher** calls those two VPS routes, compares the latest version against
  `~/SoultideCache/.launcher-version` (a file it manages itself), downloads whatever's missing or
  out of date straight into `~/SoultideCache/` - the exact directory SoultideClient's own
  `signlink.findcachedir()` already reads from, so nothing needs a second install location - then
  launches `java -jar ~/SoultideCache/SoultideClient.jar`.

If the VPS is unreachable but a client jar is already installed, the launcher still lets you play
rather than blocking - only a genuine first-time install (no jar yet) has to wait on a successful
check.

## Building

```
mvn package
```

Produces a shaded, runnable jar at `target/soultide-launcher-*.jar` (Main-Class:
`com.soultide.launcher.WaystoneApp`, dependencies bundled).
