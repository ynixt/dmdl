---
apply: always
---

# Kaizoku Stream — AI Rules

## Stack
- Kotlin on Java 25, Spring Boot 4, MVC + Virtual Threads (NO WebFlux/Reactor)
- PostgreSQL 18.3 via Spring Data JPA + Hibernate. Migrations via Flyway.
- Redis for rate limiter state persistence (survives app restarts)
- NATS JetStream, OpenFeign, JobRunr (PostgreSQL storage)
- Spring Security (session-based), Angular 21 + Angular Material
- Tests: JUnit 5 + Mockk + Testcontainers (PostgreSQL, Redis, NATS)
- Backend build: Gradle single project (Kotlin DSL). Frontend build: npm. **Gradle does NOT manage the frontend.**

**Do NOT use**: WebFlux, Reactor, RestTemplate, RestClient, MongoDB, JWT for dashboard auth, Spring Scheduling (use JobRunr).

NEVER throw ResponseStatusException in a domain package. ResponseStatusException is an HTTP/web concept that belongs to the application.web layer. The domain should throw domain exceptions (e.g., EntityNotFoundException, InvalidConfigurationException) that the controller or @ControllerAdvice converts into HTTP statuses.

Anytime you make a new endpoint or modify you need to update bruno collection inside "bruno" folder.

## Monorepo Structure
```
kaizoku_stream/
├── backend/                   # Gradle single project (Kotlin DSL)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/kotlin/com/ynixt/kaizokustream/
│       └── resources/db/migration/   # Flyway migrations (V1__, V2__, ...)
├── frontend/                  # Angular 19+ (npm)
│   └── package.json
├── docker-compose.yml
└── Makefile
```

## Architecture — Hexagonal + SOLID

Base package: `com.ynixt.dmdl`

### `application/` — Config and inbound adapters
- `config/` — Spring config, Security, Feign, NATS, JobRunr, Redis
- `web/controller/api/` — Dashboard REST (`/api/**`)
- `web/controller/addon/` — Stremio addon (`/{token}/**`)
- `web/dto/request/`, `web/dto/response/` — DTOs
- `web/exception/` — @ControllerAdvice
- `job/` — JobRunr job definitions

### `domain/` — ZERO framework dependencies
- `entity/` — Rich entities (pure Kotlin)
- `model/` — Value objects
- `service/` — Service **interfaces only**
- `gateway/` — Output port **interfaces only**
- `queue/producer/` — Message producer **interfaces only**
- `enum/` — DownloadStatus, MediaType, DownloadTarget, PostWatchPolicy, DebridProviderType, QueueItemStatus, ReleaseType, Resolution, JobType, JobStatus
- `exception/` — Domain exceptions

### `resource/` — Concrete implementations
- `service/` — Implements domain.service
- `gateway/debrid/` — DebridGateway impl. **Never reference RealDebrid outside this package.**
- `gateway/metadata/`, `gateway/mediaserver/`, `gateway/tracking/`, `gateway/stremio/` — Feign clients
- `queue/producer/` — NATS publishers
- `queue/listener/` — NATS consumers
- `persistence/entity/` — JPA @Entity classes (PostgreSQL)
- `persistence/repository/` — Spring Data JPA repositories
- `persistence/mapper/` — Domain ↔ JPA entity mappers

## Inviolable Rules

1. **Domain purity**: `domain/` has ZERO framework annotations. No @Entity, @Service, @Component, @FeignClient. Pure Kotlin only.
2. **Debrid abstraction**: Never reference RealDebrid outside `resource/gateway/debrid/`. Use `DebridGateway` interface everywhere. `DebridGatewayFactory` resolves active provider.
3. **Rate limiting**: NEVER exceed API limits. State persisted in **Redis** (survives restarts). Feign interceptors enforce. Block virtual thread if no quota.
4. **Jobs never download**: Jobs only publish to NATS via `DownloadQueueProducer`. Listener orchestrates.
5. **Security split**: `/api/**` = Spring Security session. `/{token}/**` = outside Security, token in path. `/api/health` = public.
6. **Default user**: admin/admin on first boot. No registration.
7. **State machine in domain**: `DownloadStatus` validates transitions. Invalid → `DomainException`. DEBRID_CACHED→DONE if CLOUD, DEBRID_CACHED→DOWNLOADING if PHYSICAL. Transitions logged in `download_status_log` table.
8. **Release selection**: Both release types AND resolutions support filter + prioritize:
   - `blockedReleaseTypes` / `blockedResolutions` = hard filter, NEVER shown
   - `prioritizedReleaseTypes` (+15) / `prioritizedResolutions` (+8) = score boost
   - `preferredResolution` = exact match +10. Files <100MB filtered.
   - Merge: MediaSubscription (non-null fields) > media_type_defaults (by media_type)
9. **MediaType independence**: SERIES, ANIME, MOVIE each have separate rows in `media_type_defaults`.
10. **Queue**: maxConcurrentDownloads configurable. Use `SELECT FOR UPDATE SKIP LOCKED` for safe dequeue. Priority 1-10 (lower=higher).
11. **PostgreSQL conventions**: Use native enums. Use array types (resolution[], release_type[], varchar[]) for list fields. Flyway for all migrations. Proper FKs with ON DELETE CASCADE. Indexes on frequently queried columns.

## Coding Conventions

- Constructor injection (Kotlin primary constructor), never field injection
- `sealed class Result<T>` (Success/Failure) for gateway returns
- Data classes for DTOs and value objects
- Separate request/response DTOs from domain entities — always map via persistence/mapper
- @Entity only in `resource/persistence/entity/`
- @FeignClient only in `resource/gateway/`
- Flyway migrations: `V{n}__{description}.sql` in `resources/db/migration/`
- Test naming: `should [behavior] when [condition]`

## Key Domain Types

```
MediaType: SERIES | ANIME | MOVIE
DownloadStatus: NONE→PENDING→SEARCHING→FOUND→SENT_TO_DEBRID→DEBRID_CACHED→DOWNLOADING→RENAMING→SCANNING→DONE (any→FAILED)
DownloadTarget: PHYSICAL | CLOUD
PostWatchPolicy: KEEP | DELETE | MOVE_TO_CLOUD
QueueItemStatus: QUEUED | PROCESSING | PAUSED | CANCELLED | DONE | FAILED
ReleaseType: CAM | TS | SCR | HDTS | TELECINE | HDTV | WEB_DL | WEB_RIP | BLURAY | REMUX | DVDRIP
Resolution: UHD_4K | FULL_HD | HD | SD | CUSTOM
DebridProviderType: REAL_DEBRID | ALL_DEBRID | PREMIUMIZE | TORBOX
```

Episode for MOVIE: season=null, episode=null. File paths: series→`/series/`, anime→`/anime/`, movies→`/movies/`.

## Stremio Addon Endpoints
- `GET /{token}/stream/series/{imdbId}:{season}:{episode}.json`
- `GET /{token}/stream/movie/{imdbId}.json`
- Returns: `{ "streams": [{ "name", "title", "url", "behaviorHints" }] }`

## Docker Services (all with healthchecks)
kaizoku-stream (Spring Boot :8080), kaizoku-dashboard (nginx :3000), postgres 18.3, redis 7-alpine (64MB max), nats (--js), jellyfin

## Ktlint
Always that you made a change in any kotlin file run the gradle task ktlintFormat, to ensure lint.

## Prettier + ESLint
Always that you made a change in any frontend file run the npm task prettier:fix and lint:fix, to ensure lint.

## Notes
- All texts in frontend should use ngx-translate (en-US and pt-BR)