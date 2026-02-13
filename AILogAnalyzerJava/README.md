# AILogAnalyzer (Java 11)

Spring Boot 2.7 / Java 11 implementation of AILogAnalyzer.

## What this Java app includes

- Local log reading from file or directory:
  - `GET /api/logs?path=...`
  - `GET /api/logs/raw?path=...`
- Cloud log reading:
  - AWS S3: `s3://bucket/path`
  - GCP GCS: `gs://bucket/path`
  - Azure Blob: `az://account/container/path` or Azure blob HTTPS URL
- Saved path history config (last 30 by default):
  - `GET /api/path-history`
  - `POST /api/path-history`
- Web solution lookup (ChatGPT web search -> Stack Overflow -> local fallback):
  - `POST /api/web-solutions`
- Frontend static files served from `src/main/resources/static`

## Prerequisites

- Java 11
- Maven 3.8+

## Run

```bash
cd AILogAnalyzerJava
mvn spring-boot:run
```

Environment loading order for Java app:

- system properties / OS environment variables
- `.env` files discovered from current directory and parent directories (nearest file wins)

Default URL:

- `http://localhost:8080`

## Build JAR

```bash
cd AILogAnalyzerJava
mvn clean package
java -jar target/ailoganalyzer-java11-1.0.0.jar
```

## Important environment variables

- `LOG_ALLOWED_ROOTS`
- `LOG_ALLOW_ANY_PATH=true|false`
- `LOG_MAX_FILES` (max 30)
- `LOG_MAX_BYTES` (64KB to 50MB)
- `LOG_PATH_HISTORY_LIMIT` (max 30)
- `LOG_ANALYZER_CONFIG_FILE` (default `.log-analyzer.config.json`)

- `LOG_ENABLE_CLOUD_PATHS=true|false`
- `LOG_CLOUD_PROVIDERS=aws,gcp,azure`

- AWS:
  - `LOG_AWS_REGION` or `AWS_REGION`
- GCP:
  - `GCP_PROJECT_ID`
  - `GOOGLE_APPLICATION_CREDENTIALS_JSON`
- Azure:
  - `AZURE_STORAGE_CONNECTION_STRING`
  - or `AZURE_STORAGE_ACCOUNT` + `AZURE_STORAGE_KEY`
  - optional `AZURE_STORAGE_SAS_TOKEN`

- Web solutions:
  - `OPENAI_API_KEY`
  - `LOG_CHATGPT_WEB_SEARCH_MODEL` (default `gpt-4.1-mini`)
  - `LOG_WEB_SOLUTION_LIMIT` (max 10)

## Notes

- For unrestricted local paths, set `LOG_ALLOW_ANY_PATH=true`.
- If `OPENAI_API_KEY` is missing, the app still returns Stack Overflow results and local fallback guidance.
