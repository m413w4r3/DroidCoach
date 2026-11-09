FROM busybox:1.36.1-musl@sha256:3c6ae8008e2c2eedd141725c30b20d9c36b026eb796688f88205845ef17aa213 AS busybox
FROM atlas-paseo-relay:r3a

COPY --from=busybox /bin/busybox /bin/busybox
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD ["/bin/busybox", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:4000/health"]
