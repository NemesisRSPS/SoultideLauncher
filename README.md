# SoultideLauncher

A small Swing app: checks the VPS for the latest SoultideClient release, downloads it (plus loose
runtime assets like the loading/login screen art) if out of date, then launches the client.

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
`com.soultide.launcher.Launcher`, dependencies bundled).
