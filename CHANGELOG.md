# Changelog

All notable changes to dmdl are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/). While the version is below 1.0.0, a minor release may contain breaking changes; they are listed under **Changed** or **Removed**. Versions before 0.1.0 predate the public repository and have no tags.

## [Unreleased]

## [0.1.1] - 2026-11-15

Maintenance release: dependency updates and test fixes, no change in behavior.

### Changed

- Dependency updates: Gradle 9.9.1, Chart.js 4.6.1, Angular CLI and build 21.2.30, Prettier 3.10.1.
- The Docker image workflow uses the latest major versions of `actions/checkout` and the Docker actions.
- Dependabot no longer proposes major versions of PrimeNG, PrimeIcons, `@primeuix/*`, TypeScript and Vitest; they need Angular 22 and will be upgraded together with it.

### Fixed

- Frontend unit tests failed on Node 25 and newer, because Node's built-in `localStorage` hid the jsdom one.

## [0.1.0] - 2026-11-15

First public release.

dmdl searches movies, series and anime through Stremio metadata addons, finds releases through Stremio stream addons (with the debrid service configured in the addon) and downloads them with aria2 into Jellyfin-friendly folders. It ships as one Docker image (`ghcr.io/ynixt/dmdl`, linux/amd64 and linux/arm64) plus Redis. See the [README](https://github.com/ynixt/dmdl#how-to-use) for setup.

### Added

- `SECURITY.md`, `CONTRIBUTING.md`, this changelog and `AGENTS.md`.
- Issue forms for bug reports and feature requests.
- Dependabot configuration for Gradle, npm, Docker images and GitHub Actions.
- README: warning to create the admin account before exposing a new instance, and credits for the default badge artwork.

## 0.0.9 - 2026-11-15

### Changed

- Logs page entries are sorted.

## 0.0.8 - 2026-11-15

### Added

- Webhooks: HTTP requests with optional Mustache bodies sent when downloads change state or on demand, with presets for Jellyfin, Home Assistant and ntfy.
- Logs page showing the backend, nginx and aria2 logs collected inside the container.
- Finalization progress on the downloads page (progress bar, processed bytes, throughput and remaining time).
- Configurable integrity checks in **Settings → Storage**: downloaded-file SHA-256 (on by default), copy verification and final-file verification (off by default).
- Media pages can be opened directly by link.
- Favicon.

### Changed

- Finalization hashes each physical file at most once; cleanup checks identity and size instead of re-hashing, and an interrupted cross-filesystem copy is redone.

### Removed

- The built-in Jellyfin integration, replaced by the Jellyfin webhook preset.

## 0.0.7 - 2026-11-14

### Added

- Disk activity next to the download speed, and a button to restart aria2.

### Changed

- Download speed shows the real transfer rate.
- More reliable live download updates (SSE).

## 0.0.6 - 2026-11-14

### Added

- Offline page shown while the backend is unavailable.
- Show/hide toggle for password and secret fields.
- Downloads page: request and start times, a speed and connections chart, and a toggle for the event list.
- `DMDL_NGINX_ACCESS_LOG` and `DMDL_ARIA2_LOG_LEVEL`.
- Ratings and a refreshed UI, with settings split into tabs.

### Changed

- The per-connection minimum speed is now opt-in (off by default) and the default retry wait is 10 s, so downloads keep all their connections.
- The container log is quieter: nginx access logging is off by default, aria2 lines are prefixed with `[aria2] ` and its per-second readout is no longer logged. Compose examples rotate logs.

## 0.0.5 - 2026-11-13

### Fixed

- Jellyfin 10.11+ answered HTTP 401 to the connection test and library refresh.

## 0.0.4 - 2026-11-13

### Added

- `DMDL_REDIS_CONFIGURE_ACTION` to skip enabling Redis keyspace notifications for Redis users without `CONFIG` permission.

## 0.0.3 - 2026-11-13

### Added

- `DMDL_REDIS_DATABASE` to choose the Redis database.

## 0.0.2 - 2026-11-13

### Added

- Redis username authentication (`DMDL_REDIS_USERNAME`).

### Fixed

- Docker image build that never finished.

## 0.0.1 - 2026-11-13

First version: search through Stremio addons, download with aria2, setup wizard, Docker image for linux/amd64 and linux/arm64.

[Unreleased]: https://github.com/ynixt/dmdl/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/ynixt/dmdl/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/ynixt/dmdl/releases/tag/v0.1.0
