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
- Web solution lookup with free-first routing:
  - `local -> Google links/CSE -> Gemini free tier -> Groq free tier -> OpenRouter free model -> Stack Overflow -> GitHub Issues -> optional ChatGPT paid fallback`
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
  - `LOG_ENABLE_GOOGLE_SEARCH` (default `true`)
  - `GOOGLE_CSE_API_KEY` + `GOOGLE_CSE_CX` (optional; if set, app fetches direct Google Custom Search result links)
  - `GEMINI_API_KEY` (optional; enables Gemini free-tier source)
  - `GROQ_API_KEY` (optional; enables Groq free-tier source)
  - `OPENROUTER_API_KEY` (optional; enables OpenRouter free model source)
  - `LOG_ENABLE_GEMINI_FREE_SEARCH` (default `true`)
  - `LOG_ENABLE_GROQ_FREE_SEARCH` (default `true`)
  - `LOG_ENABLE_OPENROUTER_FREE_SEARCH` (default `true`)
  - `LOG_GEMINI_FREE_MODEL` (default `gemini-2.0-flash-lite`)
  - `LOG_GROQ_FREE_MODEL` (default `llama-3.1-8b-instant`)
  - `LOG_OPENROUTER_FREE_MODEL` (default `meta-llama/llama-3.2-3b-instruct:free`)
  - `LOG_ENABLE_CHATGPT_WEB_SEARCH` (default `false`)
  - `LOG_ENABLE_GITHUB_ISSUE_SEARCH` (default `true`)
  - `GITHUB_TOKEN` (optional, raises GitHub API limits)
  - `OPENAI_API_KEY` (required only when ChatGPT search is enabled)
  - `LOG_CHATGPT_WEB_SEARCH_MODEL` (default `gpt-4.1-mini`)
  - `LOG_WEB_SOLUTION_LIMIT` (max 10)

## Notes

- For unrestricted local paths, set `LOG_ALLOW_ANY_PATH=true`.
- Without ChatGPT, the app still returns Google links, Stack Overflow, and GitHub issue guidance.
