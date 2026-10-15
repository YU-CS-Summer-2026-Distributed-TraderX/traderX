# Two stages: build the Angular app with node, then run it on the official Cloud CLI image.
#
# The runtime includes the Google Cloud CLI for the four server bridges.
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

# Official stable index provides native arm64 and amd64 runtime bases. The Docker Hub
# slim image is amd64-only; --platform alone can otherwise produce a mislabeled image.
FROM gcr.io/google.com/cloudsdktool/google-cloud-cli:stable@sha256:c631b05e8121048b29c9917bdc0a9fadd76d012f04f180a4409ec2ee534e832e
# Install native Node and kubectl from Debian and Google's official apt repository.
RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs ca-certificates curl gnupg \
 && curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg -o /tmp/cloud-google.asc \
 && gpg --dearmor -o /usr/share/keyrings/cloud-google.gpg /tmp/cloud-google.asc \
 && rm /tmp/cloud-google.asc \
 && echo "deb [signed-by=/usr/share/keyrings/cloud-google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" > /etc/apt/sources.list.d/cloud-google.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends kubectl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/dist ./dist
COPY batch-result.mjs server.mjs eod-jobs.mjs risk-demo.mjs operator-control.mjs desk-session.mjs desk-orders.mjs ./
ENV PORT=8080 STATIC_ROOT=/app/dist/web-front-end-console/browser
EXPOSE 8080
CMD ["node", "server.mjs"]

COPY treasury-demo.mjs request-path.mjs internal-token.mjs eod-pnl-status.mjs eod-extract-status.mjs cli-runner.mjs ./
