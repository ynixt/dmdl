.PHONY: build-backend build-frontend up down logs test

build-backend:
	cd backend && ./gradlew clean build

build-frontend:
	cd frontend && npm ci && npm run build

up:
	DMDL_APP_VERSION="$$(git describe --tags --exact-match 2>/dev/null)" \
	DMDL_GIT_SHA="$$(git rev-parse HEAD)" \
	docker compose -f compose.build.yaml up -d --build

up-dev:
	docker compose -p dmdl-dev -f docker-compose-dev.yml --env-file dev.env up -d

down:
	docker compose -f compose.build.yaml down

logs:
	docker compose -f compose.build.yaml logs -f --tail=200

test:
	cd backend && ./gradlew test integrationTest ktlintCheck
	cd frontend && npm run build && npm run lint && npm test -- --watch=false
