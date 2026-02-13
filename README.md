# AILogAnalyzer

AI-powered log analyzer for reading multiple log files, detecting exception patterns, and suggesting resolutions (including web-backed solutions).

## Java 11 Version (Recommended)

This repository now includes a Java 11 Spring Boot implementation in `AILogAnalyzerJava`.

Run it with:

```bash
cd AILogAnalyzerJava
mvn spring-boot:run
```

Then open:

- `http://localhost:8080`

Detailed Java setup is in `AILogAnalyzerJava/README.md`.

## Node/Vite Version (Legacy)

## Features

- Read and analyze up to 30 log files.
- Continuous path-based log reading via local backend endpoint.
- Exception-based grouping with similar-text clustering.
- Date/time/problem-type filters.
- Full source path + compact path display in findings.
- Cross-file text search.
- Web solution lookup with ChatGPT web search + fallback.
- Feedback loop: mark finding as problem/not-problem and permanently exclude noise.

## Prerequisites

- Node.js 20+ (Node 24 is currently used in this project).
- npm available in PATH.

## Setup

1. Install dependencies:

```bash
npm install
```

2. Create your environment file:

```bash
cp .env.example .env
```

On Windows PowerShell, if `cp` does not work:

```powershell
Copy-Item .env.example .env
```

3. Update `.env` values as needed:

```env
# Optional AI summary endpoint
VITE_LOG_ANALYZER_ENDPOINT=

# Optional browser-side key (prefer server-side OPENAI_API_KEY)
VITE_OPENAI_API_KEY=

# Server-side key for /api/web-solutions ChatGPT web search
OPENAI_API_KEY=your_openai_api_key

# Model used for ChatGPT web search
LOG_CHATGPT_WEB_SEARCH_MODEL=gpt-4.1-mini

# Log read endpoint used by frontend
VITE_LOG_WATCH_ENDPOINT=/api/logs

# Allowed root paths for reading logs (semicolon/comma/newline separated)
LOG_ALLOWED_ROOTS=//server/share/logs;//another/share/logs

# If true, allows any filesystem path
LOG_ALLOW_ANY_PATH=true

# Enable cloud object storage path reading
LOG_ENABLE_CLOUD_PATHS=true

# Allowed providers: aws,gcp,azure
LOG_CLOUD_PROVIDERS=aws,gcp,azure

# AWS S3
LOG_AWS_REGION=us-east-1

# GCP Cloud Storage
GCP_PROJECT_ID=
GOOGLE_APPLICATION_CREDENTIALS_JSON=

# Azure Blob
AZURE_STORAGE_CONNECTION_STRING=
AZURE_STORAGE_ACCOUNT=
AZURE_STORAGE_KEY=
AZURE_STORAGE_SAS_TOKEN=

# Limits
LOG_MAX_FILES=30
LOG_MAX_BYTES=2097152
LOG_PATH_HISTORY_LIMIT=30
LOG_WEB_SOLUTION_LIMIT=5
```

4. Install optional cloud provider SDKs (required for S3/GCS/Azure paths):

```bash
npm install @aws-sdk/client-s3 @google-cloud/storage @azure/storage-blob
```

## Supported Path Formats

- Local Windows file/folder: `C:\logs\app.log`, `\\server\share\logs\`
- Local Unix file/folder: `/var/log/app.log`, `/opt/logs/`
- AWS S3 object/prefix: `s3://my-bucket/app/SystemOut.log`, `s3://my-bucket/app/logs/`
- GCP GCS object/prefix: `gs://my-bucket/app/SystemOut.log`, `gs://my-bucket/app/logs/`
- Azure Blob object/prefix:
  - `az://<account>/<container>/path/to/log.log`
  - `az://<account>/<container>/path/to/logs/`
  - `https://<account>.blob.core.windows.net/<container>/path/to/log.log?<sas-token>`

## Cloud Auth Notes

- AWS:
  - Uses standard AWS SDK credential chain (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, IAM role, etc.).
  - Region comes from `LOG_AWS_REGION` (or `AWS_REGION`).
- GCP:
  - Uses default application credentials.
  - You can pass inline service account JSON using `GOOGLE_APPLICATION_CREDENTIALS_JSON`.
- Azure:
  - Priority: `AZURE_STORAGE_CONNECTION_STRING`, then `AZURE_STORAGE_ACCOUNT` + `AZURE_STORAGE_KEY`, then `AZURE_STORAGE_SAS_TOKEN`.
  - For `az://<container>/...` format, set `AZURE_STORAGE_ACCOUNT`.

## Run

Start development server:

```bash
npm run dev
```

Build production bundle:

```bash
npm run build
```

Preview production build:

```bash
npm run preview
```

## How To Use

1. Enter one or more file/directory paths in **Continuous read paths** (max 30).
2. Enable **Auto Read** for polling, or click **Analyze Logs** for manual analysis.
3. Use top filters for date, time, and problem type.
4. Open source log using **Open log** links in results.
5. Click **Find Web Solutions** for multi-source resolution suggestions.
6. Mark findings as **Problem** or **Not Problem**; excluded items are persisted.

## Troubleshooting

- `npm` not recognized:
  - Add `C:\Program Files\nodejs` to your system/user `PATH`.
  - Restart terminal after PATH update.
- Path read fails:
  - Ensure the path exists and is accessible from the machine running Vite.
  - Add parent directory in `LOG_ALLOWED_ROOTS` or set `LOG_ALLOW_ANY_PATH=true`.
  - Restart dev server after `.env` changes.
- ChatGPT web solutions not appearing:
  - Set a valid `OPENAI_API_KEY` in `.env`.
  - Restart dev server so config changes are loaded.

## Security Notes

- `.env` is ignored in Git; keep secrets only there.
- Rotate keys if exposed in chats, screenshots, or logs.
