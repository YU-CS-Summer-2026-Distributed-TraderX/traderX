FROM python:3.12-slim

# unzip: streams a TAQ CSV entry straight out of its zip archive (research.md Decision 5) --
# never used to extract to disk.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY capture.py ingest_taq_quotes.py gcs.py duckdb_query_examples.sql ./

CMD ["python3", "capture.py"]
