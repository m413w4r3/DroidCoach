FROM node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9 AS build

WORKDIR /app
COPY package.json package-lock.json ./
COPY scripts/verify-artifact.mjs ./scripts/verify-artifact.mjs
COPY vendor/relay-0.13.1.tgz vendor/relay-0.13.1.tgz.sha256 ./vendor/
ENV NODE_ENV=development
RUN npm ci --include=dev
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:24-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9 AS runtime
ARG NODE_ENV=production
ENV NODE_ENV=${NODE_ENV} \
    TMPDIR=/tmp

WORKDIR /app
COPY --chown=node:node package.json package-lock.json ./
COPY --chown=node:node scripts/verify-artifact.mjs ./scripts/verify-artifact.mjs
COPY --chown=node:node vendor/relay-0.13.1.tgz vendor/relay-0.13.1.tgz.sha256 ./vendor/
RUN npm ci --omit=dev && npm cache clean --force && chown -R node:node /app/node_modules
COPY --from=build --chown=node:node /app/dist ./dist

USER node
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:4401/metrics').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/main.js", "start"]
