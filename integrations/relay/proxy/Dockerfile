FROM node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9

WORKDIR /app
COPY --chown=node:node src/server.mjs ./server.mjs

ENV ATLAS_PROXY_DOCKER_NETWORK=1 \
    LISTEN_HOST=0.0.0.0 \
    LISTEN_PORT=4400 \
    UPSTREAM_HOST=atlas-relay \
    UPSTREAM_PORT=4000

USER node
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:4400/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "server.mjs"]
