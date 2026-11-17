# Debrid Media Downloader (dmdl)

Debrid Media Downloader (dmdl) is an easy way to download movies and TV shows using debrid services (TorBox, Real-Debrid, AllDebrid) and Stremio addons (AIOStreams, Torrentio, ...).

> [!WARNING]
> dmdl does not host, index or provide any content. It only downloads links returned by the addons and debrid services **you** configure. You are solely responsible for what you download and for complying with the copyright laws of your country and with your debrid provider's terms of service. Running several downloads at the same time on one debrid account may break your provider's rules.

## Features

- Search movies, series and anime through any Stremio metadata addon (AIOMetadata, Cinemeta, ...).
- Find releases through one or more Stremio stream addons (AIOStreams, Torrentio, Comet, ...), in the order you choose.
- Works with any debrid supported by your addon: TorBox, Real-Debrid, AllDebrid and others.
- Shows debrid cache status and customizable quality, resolution, codec, audio and language badges for each release.
- Downloads a movie or a single episode per request with aria2, using multiple connections.
- Live progress, speed per connection and ETA, with pause, resume, cancel and retry.
- Download history, where any title can be requested again.
- Several destination folders per category (movies, series, anime), with a default for each.
- Jellyfin-friendly folder names with editable templates and a live preview; original release file names are kept.
- Tunable aria2 settings: concurrent downloads, connections, split size, retries, timeouts and more.
- Webhooks to Jellyfin, Home Assistant, ntfy or any HTTP service when downloads change state, or on demand.
- Guided setup wizard, single admin account and persistent login.
- English and Brazilian Portuguese interface.
- One Docker image (web UI + backend + aria2) plus Redis.

## How to use

dmdl is self-hosted with Docker. You need Docker with Docker Compose.

### 1. Get the files

Create an empty folder and download two files into it:

- [`compose.yaml`](compose.yaml) – an example Compose setup with dmdl, Redis and healthchecks. Adjust it to your needs.
- [`.env.example`](.env.example) – save it as `.env`.

```bash
curl -o compose.yaml https://raw.githubusercontent.com/ynixt/dmdl/master/compose.yaml
```

```bash
curl -o .env https://raw.githubusercontent.com/ynixt/dmdl/master/.env.example
```

In `.env`, change `DMDL_REDIS_PASSWORD` and point `DMDL_MEDIA_PATH` to the folder where your movies and shows should be saved (for example `D:/Media`).

### 2. Start it

```bash
docker compose up -d
```

Open <http://localhost:8080> (or the port you set in `DMDL_PORT`).

> [!IMPORTANT]
> Until the admin account exists, **anyone who can reach dmdl can create it** and take over the instance. Create your account right after the first start, before exposing the port to the internet or putting dmdl behind a public reverse proxy. If someone else got there first, run `docker compose down -v` (it deletes the `application_data` and `redis_data` volumes, including all settings and login sessions) and start again.

### 3. Set it up

1. Create your admin account. Registration closes once the account exists.
2. Follow the setup wizard:
   - **Metadata addon** – paste the manifest URL of a metadata addon such as [AIOMetadata](https://aiometadata.elfhosted.com/configure) or Cinemeta.
   - **Search addons** – paste the manifest URL of one or more stream addons such as [AIOStreams](https://aiostreams.viren070.me/) or Torrentio. **Configure your debrid service inside the addon**; dmdl only uses the HTTP links it returns.
   - **Storage** – add at least one folder for each category you want to use. Paths are paths inside the container, so anything under `DMDL_MEDIA_PATH` starts with `/media`.
3. Search a title, pick a release and a destination folder, and download.
4. Optionally add [webhooks](#webhooks) in **Settings → Webhooks**, for example to refresh Jellyfin or get a phone notification.

### File ownership

By default aria2 and the backend run as root, so downloaded files and folders belong to root on your machine and other apps (for example Jellyfin deleting a watched episode) may not be able to change them. To make them belong to your user, set `DMDL_PUID` and `DMDL_PGID` on the `dmdl` service (run `id` on the host to see yours):

```yaml
    environment:
      DMDL_PUID: 1000
      DMDL_PGID: 1000
      # Optional: 002 also gives the group write access.
      # DMDL_UMASK: "002"
```

Do not use Compose's `user:` option instead: the container has to start as root to prepare its runtime folders, and it stops with an error if it does not.

On every start dmdl gives that user ownership of `/data` and of its staging folder `/media/.dmdl`. It does not touch the rest of your library, and it stops with an error if the user cannot write to `/media`. Files and folders created by an earlier root run keep their owner; give them to your user once on the host, for example `sudo chown -R 1000:1000 /path/to/media`, or dmdl may fail to save into those folders.

### Extra destination folders

To save to more than one disk, mount each folder into the `dmdl` service in `compose.yaml` and register its container path in **Settings**. For example, adding `D:/Movies:/media/hdd/movies` and `E:/Series:/media/ssd/series` under `volumes:` lets you use `/media/hdd/movies` and `/media/ssd/series` as destinations.

### Webhooks

A webhook is an HTTP request dmdl sends to another service. Add as many as you want in **Settings → Webhooks**. Each one has a method, a URL, optional headers and either no body or a [Mustache](https://mustache.github.io/mustache.5.html) template body. A webhook failure never affects the download.

A webhook can be sent automatically on any of these events, and/or be available for manual dispatch from any download on the **Downloads** page:

| Event | When |
| --- | --- |
| `download.requested` | The download was created (once). |
| `download.transferring` | It starts transferring, including after a resume or a retry. |
| `download.needs_action` | It waits for you to choose a file. |
| `download.paused` | It was paused. |
| `download.failed` | It failed. |
| `download.cancelled` | It was cancelled. |
| `download.completed` | The file was placed in its destination. |

The URL, header values and body can use these variables: `{{event}}` (empty for a manual dispatch), `{{trigger}}` (`automatic` or `manual`), `{{#manual}}…{{/manual}}`, `{{timestamp}}`, `{{download.id}}`, `{{download.state}}`, `{{title}}`, `{{category}}`, `{{year}}`, `{{season}}`, `{{episode}}`, `{{episodeTitle}}`, `{{posterUrl}}`, `{{provider}}`, and `{{path}}` / `{{size}}` on completion or `{{error}}` on failure. A section such as `{{#error}}…{{/error}}` is only rendered when the value exists. Values are URL-encoded in the URL and JSON-escaped in the body when the `Content-Type` header is JSON.

Use **Preview** to see the rendered request and **Test connection** to send one real request with sample data before saving. Every delivery is listed on its download; a failed automatic delivery can be resent from there. There is no automatic retry.

Presets fill the form for a **Jellyfin 10.11+** library refresh, a **Home Assistant** webhook trigger and **ntfy**. Replace the placeholders in capitals. URLs and header values are treated as secrets because they often contain tokens.

#### Example: phone notification through Home Assistant

1. In dmdl, add a webhook from the **Home Assistant** preset and set its URL to `http://<home-assistant>:8123/api/webhook/dmdl-downloads`.
2. In Home Assistant, add an automation such as:

   ```yaml
   alias: dmdl download finished
   triggers:
     - trigger: webhook
       webhook_id: dmdl-downloads
       allowed_methods: [POST]
       local_only: true
   conditions:
     - condition: template
       value_template: "{{ trigger.json.event == 'download.completed' }}"
   actions:
     - action: notify.mobile_app_your_phone
       data:
         title: Download finished
         message: "{{ trigger.json.title }}"
   ```

`local_only: true` requires dmdl to reach Home Assistant from your local network.

### Using your own Redis

Remove the `redis` service (and `depends_on`) from `compose.yaml` and point dmdl at your server with `DMDL_REDIS_HOST`, `DMDL_REDIS_PORT`, `DMDL_REDIS_PASSWORD` and, if needed, `DMDL_REDIS_USERNAME` and `DMDL_REDIS_DATABASE`.

dmdl stores login sessions in Redis and needs Redis keyspace notifications to clean up expired sessions. By default it enables them itself at startup with `CONFIG GET` / `CONFIG SET`, which requires admin permissions. If your Redis user is not allowed to run `CONFIG` (for example an ACL user with `-@admin`, which fails with `NOPERM ... 'config|get'`), configure the notifications on the Redis server instead and tell dmdl to skip that step:

1. Add this line to your `redis.conf` and restart Redis:

   ```conf
   notify-keyspace-events Egx
   ```

2. Set `DMDL_REDIS_CONFIGURE_ACTION: none` on the `dmdl` service.

Skipping step 1 does not break startup, but expired sessions are then never cleaned up from Redis.

### Updating

```bash
docker compose pull
```

```bash
docker compose up -d
```

Settings and history are kept in the `application_data` volume. `latest` follows the newest release; you can pin a version such as `ghcr.io/ynixt/dmdl:0.1.1`, or use `edge` for the latest `master` build.

Upgrading from a version with the built-in Jellyfin integration converts its settings into a **Jellyfin** webhook sent on `download.completed`, together with its notification history. That migration removes the old Jellyfin settings, so going back to an older version requires restoring a backup of the `application_data` volume taken before the upgrade.

The navbar shows which build is running: the release tag (such as `v0.1.1`) for releases, the short commit for `edge` builds, and `local` for images you built yourself without `make up`.

### Environment variables

These go in `.env` and are used by the example `compose.yaml`:

| Variable | Default | Description |
| --- | --- | --- |
| `DMDL_PORT` | `8080` | Port on your machine where the web UI is published. |
| `DMDL_MEDIA_PATH` | `./media` | Folder on your machine where downloads are saved. It is mounted at `/media` inside the container. |
| `DMDL_REDIS_PASSWORD` | – | Password of the bundled Redis container. Always set one. |
| `DMDL_SESSION_TIMEOUT` | `30d` | How long you stay signed in without activity, e.g. `12h`, `7d`, `30d`. |

These are read by the `dmdl` container and can be set under its `environment:`:

| Variable | Default | Description |
| --- | --- | --- |
| `DMDL_PUID` | `0` (root) | User id that aria2 and the backend run as, and that owns downloaded files. See [File ownership](#file-ownership). |
| `DMDL_PGID` | `DMDL_PUID` | Group id for the same processes and files. |
| `DMDL_UMASK` | `022` | umask for created files and folders, e.g. `002` to give the group write access. |
| `DMDL_REDIS_HOST` | `localhost` | Redis host name only, without port or database. The example sets it to `redis`. |
| `DMDL_REDIS_PORT` | `6379` | Redis port. |
| `DMDL_REDIS_USERNAME` | empty | Optional Redis ACL user. Leave empty to authenticate with the password only (the `default` user). |
| `DMDL_REDIS_PASSWORD` | empty | Redis password. |
| `DMDL_REDIS_DATABASE` | `0` | Redis database number, e.g. `2` to keep dmdl apart from other apps on the same server. |
| `DMDL_REDIS_CONFIGURE_ACTION` | `notify-keyspace-events` | `notify-keyspace-events` makes dmdl enable Redis keyspace notifications at startup (needs `CONFIG` permission). `none` skips it: then you **must** add `notify-keyspace-events Egx` to your Redis server's `redis.conf`. See [Using your own Redis](#using-your-own-redis). |
| `DMDL_SESSION_TIMEOUT` | `30d` | Login session and cookie lifetime. |
| `DMDL_ARIA2_RPC_SECRET` | random on each start | Secret for the internal aria2 RPC. Only set it if you need a fixed value. |
| `DMDL_ARIA2_POLL_INTERVAL` | `1s` | How often download status is read from aria2. |
| `DMDL_ARIA2_PROGRESS_PUBLICATION_INTERVAL` | `1s` | How often live progress is pushed to the browser. |
| `DMDL_ARIA2_SAVE_SESSION_INTERVAL` | `30` | Seconds between saves of the aria2 session (used to resume after a restart). |
| `DMDL_ARIA2_LOG_LEVEL` | `notice` | How much aria2 writes to the container log: `debug`, `info`, `notice`, `warn` or `error`. Use `info` to see why connections are dropped. **Warning:** at `info` and `debug` aria2 logs download URLs, and debrid links can carry access tokens, so don't share those logs as they are. Any other value stops the container at startup. |
| `DMDL_NGINX_ACCESS_LOG` | `false` | `true` writes one nginx access line per request to the container log. Off by default because the web UI polls and streams constantly. nginx errors are always logged. |
| `DMDL_DB_BUSY_TIMEOUT_MILLIS` | `5000` | How long the SQLite database waits for a lock before failing. |
| `DMDL_JAVA_OPTS` | empty | Extra JVM options, e.g. `-Xmx512m`. |

### Logs

Signed-in users can open **Logs** (`/logs`), including during onboarding. The page combines backend, nginx and processed aria2 records, with source, literal text and severity filters, older history and pause/resume. A pause stops browser delivery while collection continues. The page reports expired history, source capture failures and truncated records.

The image initializes `/run/dmdl/logs` on every start, including a restart of an existing container. Each source keeps one active JSONL segment and two archives, each at most 10 MiB (30 MiB per source, 90 MiB total). This temporary history is separate from `/data` and is cleared at startup. Capture also forwards the original output to the container console. nginx access capture follows `DMDL_NGINX_ACCESS_LOG`, and aria2 capture follows `DMDL_ARIA2_LOG_LEVEL` after allocation-readout processing.

For separate-process development, set `DMDL_LOG_DIRECTORY` on the backend to a dedicated capture directory. Initialize it once before starting any services, then pipe each service's combined output through its own collector. For example, from the repository root in a POSIX shell with Python 3 installed, capture the backend with the commands below. Use `--source nginx` or `--source aria2` for the other processes. Pipe the aria2 wrapper output so allocation processing stays upstream. Each source uses a single collector per execution. The API reports sources without a collector as unavailable.

```bash
export DMDL_LOG_DIRECTORY="$PWD/.runtime-logs"
python3 docker/app/log-collector.py --directory "$DMDL_LOG_DIRECTORY" --initialize
sh backend/gradlew -p backend bootRun 2>&1 | python3 docker/app/log-collector.py --directory "$DMDL_LOG_DIRECTORY" --source backend
```

The backend defaults to 200 records per history query and accepts at most 1,000. Records are capped at 64 Ki characters, the parsed history at 50,000 records or 45 Mi characters, and the browser at 2,000 records. Capture rotation and parsed-history eviction both produce explicit cursor gaps. History and SSE endpoints require a session and return `Cache-Control: no-store`.

`docker compose logs dmdl` shows the backend, nginx errors and aria2. Every aria2 line starts with `[aria2] `, so you can show only those:

```bash
docker compose logs dmdl | grep '\[aria2\]'
```

The example compose files keep at most three 10 MB log files per service.

## Development

### Creating a release

First move the **Unreleased** entries of [CHANGELOG.md](CHANGELOG.md) under a new `## [X.Y.Z] - YYYY-MM-DD` heading and commit it; the GitHub Release uses that section as its notes.

With Make, Node.js and Git installed, run this from the repository root on Windows, Linux or macOS:

```sh
make release 0.0.8
```

Use a version in `MAJOR.MINOR.PATCH` format, optionally prefixed with `v`. The command updates `frontend/package.json`, `frontend/package-lock.json`, `backend/build.gradle.kts` and the version examples in this README. It commits those four files with the version tag as the commit message and creates a local Git tag such as `v0.0.8`. Other staged files stay staged. An existing tag causes the command to stop before changing files.

To publish the release commit and tag:

```sh
git push origin HEAD
git push origin v0.0.8
```

## Contributing

Bug reports, ideas and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to run dmdl locally and what a pull request needs, and [CHANGELOG.md](CHANGELOG.md) for what changed in each release. Please report security issues privately as described in [SECURITY.md](SECURITY.md).

## Credits

The default release badges reuse artwork from these community projects. The images are loaded from their repositories, not shipped with dmdl, and belong to their authors:

- [BetterFormatter](https://github.com/9mousaa/BetterFormatter) by [9mousaa](https://github.com/9mousaa) – quality, resolution, HDR/Dolby Vision, audio and channel badges.
- [cardgenerator](https://github.com/je19921/cardgenerator.github.io) by [je19921](https://github.com/je19921) – streaming service, SDR and codec badges.

Streaming service names and logos are trademarks of their respective owners and are used only to identify the source of a release.

## License

[MIT](LICENSE)
