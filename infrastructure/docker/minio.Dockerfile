# MinIO server and client rebuilt from pinned upstream source.
#
# Upstream stopped publishing images and binaries (quay.io/minio/* requires authentication and
# dl.min.io returns 410), and github.com/minio/minio is archived. These images reproduce the
# releases this repository already pinned, built with a supported Go toolchain. They are test and
# staging infrastructure only; replacing MinIO with a maintained S3-compatible server is tracked
# separately. Source is AGPL-3.0 and unmodified.

ARG GO_IMAGE=golang:1.26-alpine3.23@sha256:a8fa79c5bd40d880b52bd3b6d7669ecdcfd00e85facdd427d279efb5ddd79cb1
ARG RUNTIME_IMAGE=alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0

FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS source
ARG TARGETOS=linux
ARG TARGETARCH
RUN apk add --no-cache git
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-trimpath GOOS=${TARGETOS} GOARCH=${TARGETARCH}

FROM source AS minio-build
ARG MINIO_RELEASE=RELEASE.2025-04-22T22-12-26Z
ARG MINIO_COMMIT=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e
WORKDIR /src
RUN git init -q . \
 && git remote add origin https://github.com/minio/minio.git \
 && git fetch -q --depth 1 origin "${MINIO_COMMIT}" \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${MINIO_COMMIT}"
RUN version="$(echo "${MINIO_RELEASE#RELEASE.}" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z/T\1:\2:\3Z/')" \
 && go build -tags kqueue -ldflags "$(GOOS= GOARCH= go run buildscripts/gen-ldflags.go "${version}")" -o /out/minio .

FROM source AS mc-build
ARG MC_RELEASE=RELEASE.2025-05-21T01-59-54Z
ARG MC_COMMIT=f71ad84bcf0fd4369691952af5d925347837dcec
WORKDIR /src
RUN git init -q . \
 && git remote add origin https://github.com/minio/mc.git \
 && git fetch -q --depth 1 origin "${MC_COMMIT}" \
 && git checkout -q FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${MC_COMMIT}"
RUN version="$(echo "${MC_RELEASE#RELEASE.}" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z/T\1:\2:\3Z/')" \
 && go build -tags kqueue -ldflags "$(GOOS= GOARCH= go run buildscripts/gen-ldflags.go "${version}")" -o /out/mc .

FROM ${RUNTIME_IMAGE} AS runtime
RUN apk add --no-cache ca-certificates curl \
 && addgroup -S -g 1000 minio \
 && adduser -S -D -H -u 1000 -G minio minio

# Compose healthchecks call curl; the server otherwise needs only its binary.
FROM runtime AS minio
COPY --from=minio-build /out/minio /usr/bin/minio
COPY --from=minio-build /src/LICENSE /licenses/minio/LICENSE
EXPOSE 9000 9001
ENTRYPOINT ["/usr/bin/minio"]
CMD ["server", "/data"]

# Init containers and drills run mc through /bin/sh.
FROM runtime AS mc
COPY --from=mc-build /out/mc /usr/bin/mc
COPY --from=mc-build /src/LICENSE /licenses/mc/LICENSE
ENTRYPOINT ["/usr/bin/mc"]
