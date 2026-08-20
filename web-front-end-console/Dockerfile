# Two stages: build the Angular app with node, then run it on the cloud-sdk image.
#
# The runtime base is google/cloud-sdk rather than node/nginx BECAUSE OF THE FOUR BRIDGES.
# server.mjs shells out to `gcloud storage` (EOD archive) and `kubectl exec` (kdb tap, extract
# bridge) exactly as the dev proxy does on a laptop. Reimplementing those against the GCS and
# Kubernetes REST APIs would be a second implementation of logic that already exists and is proven
# — and two implementations that drift are worse than one that carries a heavier base image.
FROM node:20-bookworm AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM google/cloud-sdk:slim
# kubectl comes from the cloud-sdk component, not apt, so it matches the gcloud that authenticates
# it. nodejs is the only thing added.
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs kubectl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/dist ./dist
COPY server.mjs ./server.mjs
ENV PORT=8080 STATIC_ROOT=/app/dist/web-front-end-console/browser
EXPOSE 8080
CMD ["node", "server.mjs"]
