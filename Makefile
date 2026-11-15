.PHONY: build-backend build-frontend up down logs test

ifeq ($(OS),Windows_NT)
NULL_DEVICE := NUL
else
NULL_DEVICE := /dev/null
endif

build-backend:
	cd backend && ./gradlew clean build

build-frontend:
	cd frontend && npm ci && npm run build

up: export DMDL_APP_VERSION = $(shell git describe --tags --exact-match 2>$(NULL_DEVICE))
up: export DMDL_GIT_SHA = $(shell git rev-parse HEAD)
up:
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
