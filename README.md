# Debrid Media Downloader

Find a movie or one episode through configurable Stremio-compatible addons, choose a release and destination, and download its HTTP link with aria2. Completed files use configurable Jellyfin-friendly destinations and names. An external Jellyfin server can optionally receive a refresh notification.

The supported stack is one Kotlin/Spring backend, an Angular/PrimeNG frontend, Redis and aria2. SQLite owns settings, requests, reservations, checkpoints and history. Workers execute locally; no NATS or other message broker is required. Redis supplies authenticated sessions, caches and live progress. Run one backend against each local SQLite application-data directory.

This is a breaking replacement for Kaizoku Stream. It provides no managed library, subscriptions, automatic release selection, season/batch acquisition, playback service, published addon, quality scoring or Trakt integration.

## Fresh Docker installation

Requirements: Docker Engine with Compose, and writable media storage with hard-link support. NTFS and ordinary Linux/macOS filesystems are intended targets. Network shares must pass the storage capability check; FAT/exFAT are unsuitable for recoverable final placement.

1. Copy `.env.example` to `.env` and set the Redis password. Compose refuses to start while it is missing; there is no default password.
2. Set `APP_MEDIA_PATH` to your media directory. Relative paths resolve beside `compose.yaml`; Windows accepts paths such as `C:/Media`.
3. Start the application:

```sh
docker compose up -d --build
```

Open [http://localhost:8181](http://localhost:8181), or the port configured by `DMDL_PORT`. An installation without an account opens registration. Create the administrator username and password, then sign in to open configuration onboarding. Registration closes as soon as the account exists. Existing installations sign in with their stored account.

Authenticated sessions are stored in Redis and survive backend restarts. The default inactivity timeout and persistent login cookie last 30 days, configurable through `APP_SESSION_TIMEOUT` using a duration such as `7d` or `30d`. Logout invalidates the server-side session. Both Compose environments enable Redis AOF persistence in the existing Redis data volume.

The Compose project is `dmdl`. It mounts the persistent `application_data` volume at `/data`, with SQLite at `/data/dmdl.db` and the aria2 session at `/data/aria2`. The entire directory holds the database, its WAL/SHM files and the aria2 session. The backend, the frontend (nginx) and aria2 run together in the single `dmdl` container, built from the root `Dockerfile`; Redis runs in its own container. Media is mounted at `/media`, and `/downloads` inside the container is a symlink to it, so the private staging directories `/media/.dmdl` and `/downloads/.dmdl` refer to the same files. aria2's fallback download directory is `/tmp`; the backend always assigns each download its own staging directory. nginx serves the frontend and proxies `/api/` on the same origin, so no CORS configuration is needed, including behind an outer TLS reverse proxy that sends `X-Forwarded-Proto`/`X-Forwarded-Host`. Only nginx is published; the backend (port 8081) and aria2 RPC (port 6800) listen on the container's loopback interface only. The aria2 RPC secret is generated randomly on each start unless `ARIA2_RPC_SECRET` is set, so it needs no configuration.

The frontend proxies `/api/` and live SSE events to the backend. Startup does not wait for Jellyfin, generated token files, an old database or any message broker.

## Configure the manual journey

Initial setup uses sequential steps for metadata, search addons, storage, and optional Jellyfin. Each intermediate step has an advance action. `Save and finish` validates and commits the complete addon and storage draft in one transaction.

Onboarding requires:

- Exactly one metadata manifest with metadata resources and searchable catalogs, such as a compatible AIOMetadata or Cinemeta configuration. Catalog IDs and content/video IDs come from the manifest/source; IMDb is not mandatory.
- At least one enabled search manifest, such as compatible AIOStreams, Torrentio or Comet. Arrange sources in the desired display order. Each source retains its original result order and repeated torrents remain present in different groups. An unsupported ID namespace is reported per source.
- Configure debrid services inside your search addon and paste its complete configured manifest URL into DMDL. Downloadable results must provide HTTP(S) links. Service identification and cache availability are displayed when supplied by each result.
- At least one writable absolute destination per category, with a selected default. Setup checks actual exclusive hard-link support.

Search a title, inspect details and select one episode when applicable, then query releases. Choose a release and destination. A title in history can create a new independent request. Destination collisions preserve the existing file.

The addon resolves the selected release to an HTTP(S) resource. Results containing only magnets or info-hashes are excluded from downloadable releases. Pause, resume, cancel and retry use the request's recorded HTTP link and aria2 identity.

## Destinations and naming

Each category has named absolute destination folders and one default. Register HDD and SSD folders in onboarding or settings, then choose the destination when requesting a download. Download forms preselect the category default. Accepted requests retain their chosen folder and organization snapshot through pause, retry, and restart.

For Docker, mount every destination into the backend and use its container-visible absolute path. For example, add `D:/Movies:/media/hdd/movies` and `E:/Movies:/media/ssd/movies` under the `dmdl` service's `volumes`, then register `/media/hdd/movies` and `/media/ssd/movies` as movie destinations. Keep the existing shared aria2 staging mount. Finalization supports copying from staging to a different destination filesystem with request-owned recovery and collision handling.

Title directories use metadata title, optional year, and a recognized provider ID. Movies prefer TMDB, then TVDB, then IMDb. Series and anime prefer TVDB, then TMDB, then IMDb. Content without a recognized external ID uses its metadata title and available year. Episode season directories use the selected metadata season, including season zero.

The selected torrent file's original basename and extension are copied literally. Codec, HDR, audio, source, and release-group tags are retained in that name. Directory metadata comes from the selected title and episode in the application.

```text
/media/hdd/movies/Example (2024) [tmdbid-123]/Example.2024.2160p.WEB-DL.H265.HDR-GROUP.mkv
/media/ssd/series/Breaking Bad (2008) [tvdbid-81189]/Season 01/Breaking.Bad.S01E01.1080p.WEB-DL.H264-GROUP.mkv
/media/hdd/anime/Example [tvdbid-456]/Season 00/Example.Special.1080p.HEVC-GROUP.mkv
```

Folder templates support `{title}`, `{year}`, `{season}`, `{episode}`, `{episodeTitle}`, and `{providerId}`, with `[optional segments]` and numeric padding such as `{season:02}`. `{providerId}` expands to the complete bracketed identifier. The title-folder default is `{title}[ ({year})][ {providerId}]`, with `/Season {season:02}` for episodic content. Restoring naming defaults preserves registered destinations and category defaults. Previews show illustrative metadata and original names.

Original basenames are validated as single filesystem components. A name unsupported by the target filesystem produces an actionable placement error and retains staging. Metadata-derived directories are sanitized, while containment validation checks existing symlinks and junctions against the selected root. Unrelated destination files are preserved on conflicts. Retry recognizes a request's output through its durable ownership witness. Completed files keep their recorded paths after configuration changes.

### Setup API

The wizard maintains its draft in memory. `POST /api/setup/validate` accepts a step and complete draft and returns `valid` and field-addressable `errors`. Steps are `metadata`, `search`, `storage`, and `jellyfin`. Manifest validation reports errors against the corresponding addon field.

```json
{"valid":false,"errors":{"metadata.manifestUrl":"KZ.INVALID_CONFIGURATION"}}
```

`POST /api/setup/complete` accepts `draft`, `expectedRevision` from `GET /api/settings`'s `configurationRevision`, and a UUID `submissionId`. The draft has `settings`, `metadataUrl`, `metadataName`, and an ordered `search` array. Each search entry supplies `manifestUrl`, an optional display `name`, and its `enabled` flag. Settings contain locale, organization, transfer preferences, and optional Jellyfin details. Organization categories contain `folderTemplate`, `destinations` with UUID/name/absolute-path entries, and `defaultDestinationId`.

Final submission commits addons, settings, destinations/defaults, and completion state atomically. Its success response is `{"completed":true}`. Retrying an unchanged payload uses the same submission identifier and recovers the committed result. Configuration-revision conflicts return HTTP 409 with `KZ.CONFIGURATION_CHANGED`. Final validation returns HTTP 400 with `KZ.SETUP_INVALID` and an `errors` map. An unsuccessful submission retains the editable frontend draft.

`POST /api/downloads` accepts `content`, the selected HTTP `release`, and a registered `destinationId`. Download responses expose the snapshotted destination name/root, optional service metadata, and `originalFileName` discovered from the transfer.

## Optional external Jellyfin

Jellyfin is disabled by default. To enable notification, supply the base URL of an existing server and an existing API token. The connection test only reads server information. The application creates no Jellyfin users, tokens or libraries and provides no playback proxy.

Mount the final media directories in Jellyfin and configure its movie/series/anime libraries yourself. Container paths may differ between applications. After successful final placement, the backend sends `POST /Library/Refresh`, with no item/path lookup. A timeout or authorization failure is recorded separately while the download remains completed. Retry notification after correcting settings; it uses the recorded completed download. Disabling notification prevents pending/retried refreshes.

## SQLite backup and restore

Use a stopped backend or a SQLite-consistent backup tool. Copying only the live `.db` file can omit committed WAL content. Back up application data separately from media.

Example stopped-directory backup, run beside `compose.yaml` after creating a `backups` directory:

```sh
docker compose stop dmdl
docker compose run --rm --no-deps --entrypoint sh --volume ./backups:/backup dmdl -c 'tar -czf /backup/dmdl-data.tar.gz -C /data .'
docker compose start dmdl
```

For restoration, stop the backend, retain the original application-data volume, and extract the archive into a new empty volume. Bind that restored volume as `application_data` through a Compose override, then start the backend. Keep the original volume and archive until the restored configuration/history are verified. Media is not included in this database backup and must be backed up independently.

## Cutover and rollback

Stop the old application before the new application writes to the same media destinations. Preserve the old Compose/env files, PostgreSQL volume, Jellyfin configuration and all existing media. The new project/volume names initialize fresh SQLite state; no PostgreSQL import is required. Existing media is neither imported into a library nor renamed.

Configure the new integrations, inspect a naming preview and verify a movie and one episode. Existing destination conflicts fail safely. To roll back, stop Debrid Media Downloader and redeploy the prior build with its own original configuration and volumes. New SQLite history is not converted back into PostgreSQL. Avoid simultaneous old/new writers to shared media.

`docker compose down` stops this project while preserving volumes and media. The supplied Makefile has no volume-deletion target. No installation/cutover command removes old database volumes or media.

## Local development and checks

Use JDK 25, Node.js 24.15.0 or newer in the 24.x line, and npm 12.2.2 (`npm install --global npm@12.2.2`). The frontend also accepts Node.js 22.22.3+ and 26+; `frontend/.nvmrc` selects Node.js 24. Angular 21 uses TypeScript 5.9.x to match the build tooling.

The UI uses PrimeNG 21 Community, PrimeUIX Themes 2 and PrimeIcons 7 under their MIT licenses. These dependencies are pinned to keep the UI on open source releases.

The frontend lockfile includes a `minimatch` override to the patched 11.x release for the lint tooling. npm 12 install-script approvals are pinned in `package.json`; `.npmrc` allows the registry tarball used by Tailwind's optional WASI fallback. The backend keeps ktlint's embedded Kotlin compiler separate from the application's Kotlin dependency management.

From the repository root, start Redis and aria2:

```sh
docker compose --env-file dev.env -f docker-compose-dev.yml up -d --build
```

The development aria2 RPC endpoint is `http://127.0.0.1:16843/jsonrpc`. Set `ARIA2_RPC_PORT` for both Compose and the backend to choose another host port, or set `ARIA2_RPC_URL` explicitly when connecting to a different endpoint.

Export the values from `dev.env` into the backend process. The IDE and Gradle `bootRun` use the repository root as their working directory. The default `./media` path matches the development aria2 `/downloads` mount, including `.dmdl` staging. When overriding storage, set the same absolute `APP_MEDIA_PATH` for Compose and the backend. SQLite defaults to repository `data/dmdl.db`.

In `backend/`, run `./gradlew bootRun` (`.\gradlew.bat bootRun` in PowerShell). In `frontend/`, run `npm ci` and `npm start`. The development frontend proxies `/api` to `localhost:8080`.

Required checks:

```sh
cd backend
./gradlew test integrationTest ktlintCheck
cd ../frontend
npm ci
npm run build
npm run lint
npm test -- --watch=false
```

Integration tests manage isolated real SQLite files, Redis and aria2 containers, and local addon/file/Jellyfin HTTP fixtures. They need Docker but no provider accounts, PostgreSQL, NATS or external Jellyfin. See the change's `implementation-notes.md` for the recorded verification results and remaining limitations.

Upgrading applies SQLite migration `V7__addon_http_downloads`, which drops saved debrid credentials, default-provider selection, and manual addon bindings. Historical HTTP requests can continue. Unfinished magnet/info-hash requests receive `KZ.ADDON_HTTP_REQUIRED` so the user can select a fresh addon HTTP result. Completed history remains readable.
