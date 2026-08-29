# syntax=docker.m.daocloud.io/docker/dockerfile:1.7

ARG BASE_REGISTRY=docker.m.daocloud.io

# Frontend compilation is architecture-independent. Run it on the native
# builder platform so multi-architecture builds do not execute npm under QEMU.
FROM --platform=$BUILDPLATFORM ${BASE_REGISTRY}/library/node:20-alpine AS web-build
WORKDIR /workspace
COPY h5/package.json h5/package-lock.json ./h5/
RUN cd h5 \
    && npm ci \
      --registry=https://registry.npmmirror.com \
      --no-audit \
      --no-fund \
      --fetch-timeout=120000 \
      --fetch-retries=5 \
      --fetch-retry-factor=2 \
      --fetch-retry-mintimeout=1000 \
      --fetch-retry-maxtimeout=30000
COPY h5 ./h5
RUN mkdir -p backend/src/main/resources/static/m \
    && cd h5 \
    && npm run build

# The JAR is also architecture-independent; keep Maven on the native builder.
FROM --platform=$BUILDPLATFORM ${BASE_REGISTRY}/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src ./src
COPY dist/tv-apk ./src/main/resources/static/tv-apk
COPY --from=web-build /workspace/backend/src/main/resources/static/m ./src/main/resources/static/m
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

FROM ${BASE_REGISTRY}/library/eclipse-temurin:21-jre-alpine
ARG KTV_RELEASE_VERSION=0.1.0-dev
ARG KTV_RELEASE_VERSION_CODE=1
ARG KTV_GIT_SHA=unknown
ARG KTV_BUILD_TIME=unknown
WORKDIR /app

ENV KTV_RELEASE_VERSION=${KTV_RELEASE_VERSION} \
    KTV_RELEASE_VERSION_CODE=${KTV_RELEASE_VERSION_CODE} \
    KTV_BUILD_GIT_SHA=${KTV_GIT_SHA} \
    KTV_BUILD_TIME=${KTV_BUILD_TIME} \
    KTV_RELEASE_ARMEABI_V7A_APK=classpath:/static/tv-apk/home-ktv-tv-${KTV_RELEASE_VERSION}-armeabi-v7a.apk \
    KTV_RELEASE_ARM64_V8A_APK=classpath:/static/tv-apk/home-ktv-tv-${KTV_RELEASE_VERSION}-arm64-v8a.apk

LABEL org.opencontainers.image.version="${KTV_RELEASE_VERSION}" \
      org.opencontainers.image.revision="${KTV_GIT_SHA}" \
      org.opencontainers.image.created="${KTV_BUILD_TIME}"

# 打包 FFmpeg（含 ffprobe），供曲库扫描解析媒体。
# Intel VAAPI requires the userspace iHD driver in addition to FFmpeg's VAAPI encoders.
# intel-media-driver is x86_64-only, so keep the multi-architecture ARM64 image unchanged.
RUN sed -i 's#https://dl-cdn.alpinelinux.org#https://mirrors.aliyun.com#g' /etc/apk/repositories \
    && for attempt in 1 2 3; do \
      apk add --no-cache ffmpeg su-exec && break; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      sleep 5; \
    done \
    && if [ "$(apk --print-arch)" = "x86_64" ]; then \
      apk add --no-cache intel-media-driver libva-utils; \
    fi \
    && addgroup -S ktv \
    && adduser -S -D -H -G ktv ktv \
    && mkdir -p /data /music \
    && chown ktv:ktv /app /data /music

COPY --from=build /build/target/home-ktv-server.jar app.jar
COPY backend/docker-entrypoint.sh /usr/local/bin/home-ktv-entrypoint
RUN chmod 0555 /usr/local/bin/home-ktv-entrypoint

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/home-ktv-entrypoint"]
