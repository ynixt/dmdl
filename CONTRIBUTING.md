# Contributing to dmdl

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting bugs and ideas

- Search the existing issues first.
- For a bug, include the dmdl version (shown in the navbar), how you run it and the relevant part of the logs (**Logs** page or `docker compose logs dmdl`). Remove addon URLs, debrid links, tokens and passwords before posting.
- Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Development setup

You need:

- JDK 25
- Node.js 24 (see `frontend/.nvmrc`) and npm
- Docker with Docker Compose (local Redis/aria2 and integration tests)
- Make (optional, for the shortcuts below)

### 1. Start Redis and aria2

```bash
make up-dev
```

This runs `docker-compose-dev.yml` with the values in [`dev.env`](dev.env): Redis on `127.0.0.1:6379` and aria2 RPC on `127.0.0.1:16843`, with `./media` mounted as aria2's download folder.

### 2. Run the backend

Export the same `dev.env` values and start Spring Boot. `bootRun` runs from the repository root, so the database ends up in `./data/dmdl.db` and downloads in `./media`.

```bash
set -a && . ./dev.env && set +a && cd backend && ./gradlew bootRun
```

The API listens on <http://localhost:8080>. IntelliJ users can use the shared `Application` run configuration and load `dev.env` into it.

### 3. Run the frontend

```bash
cd frontend && npm ci && npm start
```

Open <http://localhost:4200>. The dev server proxies `/api` to the backend on port 8080.

### Building the full image

`make up` builds the production image from source (`compose.build.yaml`) and starts it with Redis on <http://localhost:8080>.

## Before opening a pull request

1. Run the checks and make sure they pass:

   ```bash
   make test
   ```

   That runs the backend unit tests, integration tests (needs Docker) and ktlint, then the frontend build, lint and tests.
2. Format what you touched: `./gradlew ktlintFormat` in `backend/`, `npm run prettier:fix && npm run lint:fix` in `frontend/`.
3. Follow the conventions in [AGENTS.md](AGENTS.md). The most common ones:
   - keep `domain/` free of Spring/JPA and of HTTP concepts (the ArchUnit tests check this);
   - add a new Flyway migration instead of editing a released one;
   - add every UI text to both `en-US.json` and `pt-BR.json`.
4. Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for any user-visible change.
5. Keep pull requests focused, and describe what changed and how you tested it. Screenshots help for UI changes.

Commit messages are short and in English; prefixes such as `feat:` and `fix:` are welcome but not required.

## Releases

Releases are cut by the maintainer with `make release X.Y.Z`; see [Creating a release](README.md#creating-a-release).

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
