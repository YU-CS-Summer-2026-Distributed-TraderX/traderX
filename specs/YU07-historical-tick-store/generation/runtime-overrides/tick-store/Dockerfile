FROM python:3.12-slim

# unzip (+ funzip, same package): streams a TAQ CSV entry straight out of its zip archive
# (research.md Decision 5) -- never used to extract to disk. curl/gnupg/apt-transport-https: needed
# to add Google's apt repo for the Cloud SDK below. google-cloud-cli: `gcloud storage cat` streams
# a GCS object without downloading it to a local file -- the Stage 2 GCS-native ingestion path
# (research.md Decision 6 extended) reads raw TAQ zips already landed in GCS the same way capture.py
# and ingest_taq_quotes.py stream from OneDrive/local paths, just with GCS as the source.
RUN apt-get update && apt-get install -y --no-install-recommends \
      unzip curl gnupg apt-transport-https ca-certificates \
    && echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
      | tee /etc/apt/sources.list.d/google-cloud-sdk.list \
    && curl -s https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg \
    && apt-get update && apt-get install -y --no-install-recommends google-cloud-cli \
    && apt-get purge -y gnupg apt-transport-https \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY capture.py ingest_taq_quotes.py ingest_taq_trades.py gcs.py duckdb_query_examples.sql stage2_ingest.sh ./
RUN chmod +x stage2_ingest.sh

CMD ["python3", "capture.py"]
