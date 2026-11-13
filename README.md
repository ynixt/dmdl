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

### 1. Create the files

Create an empty folder and put these two files in it.

`compose.yaml`:

```yaml
name: dmdl
services:
  dmdl:
    image: ghcr.io/ynixt/dmdl:latest
    container_name: dmdl
    restart: unless-stopped
    stop_grace_period: 30s
    environment:
      APP_REDIS_HOST: redis
      APP_REDIS_PASSWORD: ${REDIS_PASSWORD}
      APP_SESSION_TIMEOUT: ${APP_SESSION_TIMEOUT:-30d}
    ports:
      - ${DMDL_PORT:-8181}:8080
    volumes:
      - application_data:/data
      - ${APP_MEDIA_PATH:-./media}:/media
    depends_on:
      redis:
        condition: service_healthy

  redis:
    image: redis:8.10.3-alpine
    restart: unless-stopped
    command: [redis-server, --requirepass, "${REDIS_PASSWORD}", --appendonly, "yes"]
    environment:
      REDISCLI_AUTH: ${REDIS_PASSWORD:?REDIS_PASSWORD must be set}
    volumes:
      - redis_data:/data
    healthcheck:
      test: [CMD, redis-cli, ping]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  application_data:
  redis_data:
```

`.env`:

```env
DMDL_PORT=8181
APP_MEDIA_PATH=./media
REDIS_PASSWORD=choose-a-strong-password
APP_SESSION_TIMEOUT=30d
```

Change `REDIS_PASSWORD` and point `APP_MEDIA_PATH` to the folder where your movies and shows should be saved (for example `D:/Media`).

### 2. Start it

```bash
docker compose up -d
```

Open <http://localhost:8181> (or the port you set in `DMDL_PORT`).

### 3. Set it up

1. Create your admin account. Registration closes once the account exists.
2. Follow the setup wizard:
   - **Metadata addon** – paste the manifest URL of a metadata addon such as [AIOMetadata](https://aiometadata.elfhosted.com/configure) or Cinemeta.
   - **Search addons** – paste the manifest URL of one or more stream addons such as [AIOStreams](https://aiostreams.viren070.me/) or Torrentio. **Configure your debrid service inside the addon**; dmdl only uses the HTTP links it returns.
   - **Storage** – add at least one folder for each category you want to use. Paths are paths inside the container, so anything under `APP_MEDIA_PATH` starts with `/media`.
   - **Jellyfin** (optional) – server URL and API token to refresh your library after each download.
3. Search a title, pick a release and a destination folder, and download.

### Extra destination folders

To save to more than one disk, mount each folder into the container and register its container path in **Settings**:

```yaml
    volumes:
      - application_data:/data
      - ${APP_MEDIA_PATH:-./media}:/media
      - D:/Movies:/media/hdd/movies
      - E:/Series:/media/ssd/series
```

Then add `/media/hdd/movies` and `/media/ssd/series` as destinations.

### Updating

```bash
docker compose pull
docker compose up -d
```

Settings and history are kept in the `application_data` volume. `latest` follows the newest release; you can pin a version such as `ghcr.io/ynixt/dmdl:0.0.1`, or use `edge` for the latest `master` build.

### Environment variables

These go in `.env` and are read by `compose.yaml`:

| Variable | Default | Description |
| --- | --- | --- |
| `DMDL_PORT` | `8181` | Port on your machine where the web UI is published. |
| `APP_MEDIA_PATH` | `./media` | Folder on your machine where downloads are saved. It is mounted at `/media` inside the container. |
| `REDIS_PASSWORD` | – (required) | Password for the Redis container. Compose refuses to start without it. |
| `APP_SESSION_TIMEOUT` | `30d` | How long you stay signed in without activity, e.g. `12h`, `7d`, `30d`. |

These can be set under `environment:` of the `dmdl` service:

| Variable | Default | Description |
| --- | --- | --- |
| `APP_REDIS_HOST` | `localhost` | Redis host name. Use `redis` with the compose file above. |
| `APP_REDIS_PORT` | `6379` | Redis port. |
| `APP_REDIS_PASSWORD` | empty | Redis password. Must match `REDIS_PASSWORD`. |
| `APP_SESSION_TIMEOUT` | `30d` | Login session and cookie lifetime. |
| `ARIA2_RPC_SECRET` | random on each start | Secret for the internal aria2 RPC. Only set it if you need a fixed value. |
| `ARIA2_POLL_INTERVAL` | `1s` | How often download status is read from aria2. |
| `ARIA2_PROGRESS_PUBLICATION_INTERVAL` | `1s` | How often live progress is pushed to the browser. |
| `ARIA2_SAVE_SESSION_INTERVAL` | `30` | Seconds between saves of the aria2 session (used to resume after a restart). |
| `APP_DB_BUSY_TIMEOUT_MILLIS` | `5000` | How long the SQLite database waits for a lock before failing. |
| `JAVA_OPTS` | empty | Extra JVM options, e.g. `-Xmx512m`. |

The image also sets internal variables (`SERVER_ADDRESS`, `SERVER_PORT`, `APP_DB_PATH`, `APP_MEDIA_PATH`, `ARIA2_RPC_PORT`, `ARIA2_RPC_URL`, `ARIA2_RPC_LISTEN_ALL`, `ARIA2_CONFIG_DIR`, `ARIA2_DEFAULT_DIR`, `ARIA2_APPLICATION_STAGING_ROOT`, `ARIA2_DAEMON_STAGING_ROOT`) that wire the services together inside the container. Leave them as they are.

## License

[MIT](LICENSE)
