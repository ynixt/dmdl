.PHONY: build-backend build-frontend up down logs test

build-backend:
	cd backend && ./gradlew clean build

build-frontend:
	cd frontend && npm ci && npm run build

up:
	docker compose up -d --build

up-dev:
	docker compose -p dmdl-dev -f docker-compose-dev.yml --env-file dev.env up -d

down:
	docker compose down

logs:
	docker compose logs -f --tail=200

test:
	cd backend && ./gradlew test integrationTest ktlintCheck
	cd frontend && npm run build && npm run lint && npm test -- --watch=false
