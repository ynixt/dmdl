# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## Project

Debrid Media Downloader (dmdl) is a self-hosted app that searches movies, series and anime through Stremio addons and downloads the debrid HTTP links they return with aria2. It ships as one Docker image (Angular UI served by nginx + Spring Boot backend + aria2) plus Redis.

## Layout

```
backend/    Kotlin + Spring Boot (Gradle, Kotlin DSL)
frontend/   Angular (npm). Gradle does NOT build the frontend.
docker/     Image runtime: entrypoint, nginx, aria2, log collector, healthchecks
bruno/      Bruno collection documenting the HTTP API
scripts/    Release script (used by `make release`)
```

## Stack

- Backend: Kotlin on Java 25, Spring Boot 4, Spring MVC with virtual threads (no WebFlux/Reactor).
- Persistence: SQLite through Spring Data JPA/Hibernate (community SQLite dialect); Flyway migrations in `backend/src/main/resources/db/sqlite/`.
- Sessions: Spring Security (session based) with Spring Session on Redis.
- Downloads: aria2 over JSON-RPC (`resource/gateway/aria2`).
- Webhooks: Mustache templates (jmustache).
- Backend tests: JUnit 5, MockK, ArchUnit; integration tests (`src/integrationTest`) use Testcontainers (Redis, aria2).
- Frontend: Angular 21 (standalone components, signals), PrimeNG + Tailwind CSS, ngx-translate, Vitest.

## Backend architecture (hexagonal)

Base package: `com.ynixt.dmdl`.

- `application/` – Spring configuration and inbound adapters: `config/`, `web/controller/`, `web/dto/`, `job/`.
- `domain/` – pure Kotlin: `model/`, `enum/`, and **interfaces only** in `service/`, `gateway/`, `repository/`, `queue/producer/`.
- `resource/` – implementations: `service/`, `gateway/*`, `persistence/` (entities, repositories, mappers, stores), `cache/`, `queue/`.

Rules (most are enforced by `ArchitectureConventionsTest`):

1. `domain/` has no dependency on Spring, Jakarta Persistence, `application/` or `resource/`.
2. `domain/service`, `domain/gateway` and `domain/queue/producer` contain interfaces only.
3. Resource services use domain interfaces, never JPA repositories directly.
4. Never throw `ResponseStatusException` (or any HTTP concept) from the domain. Throw domain exceptions and map them to HTTP statuses in the web layer.
5. `@Entity` classes live only in `resource/persistence/entity/`.
6. Use constructor injection (Kotlin primary constructor), never field injection.

## Conventions

- New database change → a new Flyway file `V{n}__{description}.sql` in `db/sqlite/`. Never edit a released migration.
- New or changed endpoint → update the matching request in `bruno/`.
- Every user-facing frontend text goes through ngx-translate, with keys in both `frontend/public/i18n/en-US.json` and `pt-BR.json`.
- Never log secrets: addon manifest URLs and debrid links often carry API keys or tokens.
- Code, comments, commit messages and docs are written in English.

## Commands

Backend (from `backend/`):

```bash
./gradlew test             # unit tests
./gradlew integrationTest  # needs Docker (Testcontainers)
./gradlew ktlintFormat     # run after changing any Kotlin file
```

Frontend (from `frontend/`):

```bash
npm ci
npm start                  # dev server on :4200, proxies /api to :8080
npm test -- --watch=false
npm run prettier:fix && npm run lint:fix   # run after changing any frontend file
```

Everything (from the repository root): `make test`. Local Redis + aria2 for development: `make up-dev` (uses `dev.env`; export the same values for the backend's `bootRun`). Full image built from source: `make up`.

## Do not commit

- `openspec/` (local planning only; already in `.gitignore`).
- Anything under `data/`, `media/`, `.runtime-logs/` or real `.env` files.
