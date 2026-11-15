# Changelog

All notable changes to dmdl are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/). While the version is 0.0.x, any release may contain breaking changes.

## [Unreleased]

### Added

- `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md` and `AGENTS.md`.
- README: warning about creating the admin account before exposing a new instance, and credits for the default badge artwork.

## [0.0.9] - 2026-11-15

### Changed

- Logs page entries are sorted.

## [0.0.8] - 2026-11-15

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

## [0.0.7] - 2026-11-14

### Added

- Disk activity next to the download speed, and a button to restart aria2.

### Changed

- Download speed shows the real transfer rate.
- More reliable live download updates (SSE).

## [0.0.6] - 2026-11-14

### Added

- Offline page shown while the backend is unavailable.
- Show/hide toggle for password and secret fields.
- Downloads page: request and start times, a speed and connections chart, and a toggle for the event list.
- `DMDL_NGINX_ACCESS_LOG` and `DMDL_ARIA2_LOG_LEVEL`.
- Ratings and a refreshed UI, with settings split into tabs.

### Changed

- The per-connection minimum speed is now opt-in (off by default) and the default retry wait is 10 s, so downloads keep all their connections.
- The container log is quieter: nginx access logging is off by default, aria2 lines are prefixed with `[aria2] ` and its per-second readout is no longer logged. Compose examples rotate logs.

## [0.0.5] - 2026-11-13

### Fixed

- Jellyfin 10.11+ answered HTTP 401 to the connection test and library refresh.

## [0.0.4] - 2026-11-13

### Added

- `DMDL_REDIS_CONFIGURE_ACTION` to skip enabling Redis keyspace notifications for Redis users without `CONFIG` permission.

## [0.0.3] - 2026-11-13

### Added

- `DMDL_REDIS_DATABASE` to choose the Redis database.

## [0.0.2] - 2026-11-13

### Added

- Redis username authentication (`DMDL_REDIS_USERNAME`).

### Fixed

- Docker image build that never finished.

## [0.0.1] - 2026-11-13

First public version: search through Stremio addons, download with aria2, setup wizard, Docker image for linux/amd64 and linux/arm64.

[Unreleased]: https://github.com/ynixt/dmdl/compare/v0.0.9...HEAD
[0.0.9]: https://github.com/ynixt/dmdl/compare/ce9a812...v0.0.9
[0.0.8]: https://github.com/ynixt/dmdl/compare/v0.0.7...ce9a812
[0.0.7]: https://github.com/ynixt/dmdl/compare/v0.0.6...v0.0.7
[0.0.6]: https://github.com/ynixt/dmdl/compare/v0.0.5...v0.0.6
[0.0.5]: https://github.com/ynixt/dmdl/compare/v0.0.4...v0.0.5
[0.0.4]: https://github.com/ynixt/dmdl/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/ynixt/dmdl/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/ynixt/dmdl/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/ynixt/dmdl/releases/tag/v0.0.1
