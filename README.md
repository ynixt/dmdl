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
- Optional library refresh on an existing Jellyfin server after each download.
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

### 3. Set it up

1. Create your admin account. Registration closes once the account exists.
2. Follow the setup wizard:
   - **Metadata addon** – paste the manifest URL of a metadata addon such as [AIOMetadata](https://aiometadata.elfhosted.com/configure) or Cinemeta.
   - **Search addons** – paste the manifest URL of one or more stream addons such as [AIOStreams](https://aiostreams.viren070.me/) or Torrentio. **Configure your debrid service inside the addon**; dmdl only uses the HTTP links it returns.
   - **Storage** – add at least one folder for each category you want to use. Paths are paths inside the container, so anything under `DMDL_MEDIA_PATH` starts with `/media`.
   - **Jellyfin** (optional) – server URL and API token to refresh your library after each download.
3. Search a title, pick a release and a destination folder, and download.

### Extra destination folders

To save to more than one disk, mount each folder into the `dmdl` service in `compose.yaml` and register its container path in **Settings**. For example, adding `D:/Movies:/media/hdd/movies` and `E:/Series:/media/ssd/series` under `volumes:` lets you use `/media/hdd/movies` and `/media/ssd/series` as destinations.

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

Settings and history are kept in the `application_data` volume. `latest` follows the newest release; you can pin a version such as `ghcr.io/ynixt/dmdl:0.0.5`, or use `edge` for the latest `master` build.

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

`docker compose logs dmdl` shows the backend, nginx errors and aria2. Every aria2 line starts with `[aria2] `, so you can show only those:

```bash
docker compose logs dmdl | grep '\[aria2\]'
```

The example compose files keep at most three 10 MB log files per service.

## License

[MIT](LICENSE)
