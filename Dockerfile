# Static musl binary with the web app embedded, shipped on scratch. On fastpi build only under the rust-build lock:
# flock ~/.cache/claude-work/rust-build.lock docker build -t wg-chores-v2:local .
# `--target test` stops after the web and server test suites.
FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY docs/test-vectors /src/docs/test-vectors
COPY docs/privacy.md docs/account-deletion.md /src/docs/
COPY web/ ./
RUN npm test && npm run build

FROM rust:1-alpine@sha256:2cccd8b212b37dad15a555589c1b6787da4ab913fe24d8a9b2d99d3498869409 AS build
RUN apk add --no-cache musl-dev ca-certificates
ARG CARGO_BUILD_JOBS=2
ENV CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS} CARGO_NET_RETRY=5 CARGO_TERM_COLOR=never
WORKDIR /src/server
COPY docs/test-vectors /src/docs/test-vectors
COPY server/ ./
COPY --from=web /src/web/dist /src/web/dist
RUN --mount=type=cache,id=wg-chores-cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=wg-chores-cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=wg-chores-target,target=/src/server/target \
    cargo test --locked \
    && cargo build --release --locked \
    && mkdir -p /out/data /out/root/tmp /out/etc/ssl/certs \
    && chmod 1777 /out/root/tmp \
    && cp target/release/wg-chores /out/wg-chores \
    && cp /etc/ssl/certs/ca-certificates.crt /out/etc/ssl/certs/ \
    && echo 'wgchores:x:65035:65037::/data:/sbin/nologin' > /out/etc/passwd \
    && echo 'wgchores:x:65037:' > /out/etc/group \
    && ! readelf -lW /out/wg-chores | grep -q INTERP

FROM build AS test
RUN /out/wg-chores --help >/dev/null

FROM scratch
COPY --from=build /out/wg-chores /wg-chores
COPY --from=build /out/etc/ /etc/
COPY --from=build --chown=65035:65037 /out/data /data
COPY --from=build /out/root/ /
USER 65035:65037
ENV DATA_DIR=/data LISTEN=0.0.0.0:8080
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 CMD ["/wg-chores", "healthcheck"]
ENTRYPOINT ["/wg-chores"]
CMD ["serve"]
