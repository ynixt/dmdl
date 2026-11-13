# Single image: backend (Spring Boot), frontend (Angular served by nginx) and aria2.

FROM eclipse-temurin:25-jdk AS backend-build
WORKDIR /app
COPY backend/gradlew gradlew
COPY backend/gradle gradle
COPY backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/src src
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM node:24-alpine3.24 AS frontend-build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
COPY frontend/.npmrc ./
RUN npm install --global npm@12.2.2 && npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="Debrid Media Downloader"

RUN apt-get update \
    && apt-get install -y --no-install-recommends aria2 coreutils nginx tini wget \
    && rm -rf /var/lib/apt/lists/* /etc/nginx/sites-enabled/* \
    && ln -sf /dev/stdout /var/log/nginx/access.log \
    && ln -sf /dev/stderr /var/log/nginx/error.log \
    && mkdir -p /data /media \
    && ln -s /media /downloads

COPY docker/aria2/start-aria2.sh /usr/local/bin/start-aria2
COPY docker/aria2/aria2-healthcheck.sh /usr/local/bin/aria2-healthcheck
COPY docker/aria2/allocation-readout.awk /usr/local/bin/allocation-readout.awk
COPY docker/app/entrypoint.sh /usr/local/bin/dmdl-entrypoint
COPY docker/app/healthcheck.sh /usr/local/bin/dmdl-healthcheck
COPY docker/app/nginx.conf /etc/nginx/conf.d/dmdl.conf
RUN sed -i 's/\r$//' /usr/local/bin/start-aria2 /usr/local/bin/aria2-healthcheck /usr/local/bin/allocation-readout.awk \
        /usr/local/bin/dmdl-entrypoint /usr/local/bin/dmdl-healthcheck /etc/nginx/conf.d/dmdl.conf \
    && chmod 0755 /usr/local/bin/start-aria2 /usr/local/bin/aria2-healthcheck \
        /usr/local/bin/dmdl-entrypoint /usr/local/bin/dmdl-healthcheck

COPY --from=backend-build /app/build/libs/*.jar /app/app.jar
COPY --from=frontend-build /app/dist/dmdl/browser /usr/share/nginx/html

# The backend listens only on loopback; nginx (port 8080) is the single entry point.
# /downloads is a symlink to /media so aria2 and the backend see the same staging files.
ENV SERVER_ADDRESS=127.0.0.1 \
    SERVER_PORT=8081 \
    ARIA2_RPC_PORT=6800 \
    ARIA2_RPC_LISTEN_ALL=false \
    ARIA2_RPC_URL=http://127.0.0.1:6800/jsonrpc \
    ARIA2_CONFIG_DIR=/data/aria2 \
    ARIA2_DEFAULT_DIR=/tmp \
    APP_DB_PATH=/data/dmdl.db \
    APP_MEDIA_PATH=/media \
    ARIA2_APPLICATION_STAGING_ROOT=/media/.dmdl \
    ARIA2_DAEMON_STAGING_ROOT=/downloads/.dmdl

WORKDIR /app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=60s CMD ["/usr/local/bin/dmdl-healthcheck"]
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/dmdl-entrypoint"]
