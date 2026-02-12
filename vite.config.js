import { open, readdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

const projectRoot = fileURLToPath(new URL(".", import.meta.url));
const DEFAULT_PATH_HISTORY_LIMIT = 30;
const DEFAULT_WEB_SOLUTION_LIMIT = 5;
const OPENAI_RESPONSES_API_URL = "https://api.openai.com/v1/responses";
const DEFAULT_CHATGPT_WEB_SEARCH_MODEL = "gpt-4.1-mini";

function parseAllowedRoots(raw) {
  if (!raw) return [projectRoot];
  return raw
    .split(/[;,\n]/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => path.resolve(item));
}

function normalizeInputPath(rawPath) {
  let value = String(rawPath || "").trim();
  if (!value) return "";
  const quotedWithDouble = value.startsWith('"') && value.endsWith('"');
  const quotedWithSingle = value.startsWith("'") && value.endsWith("'");
  if (quotedWithDouble || quotedWithSingle) {
    value = value.slice(1, -1).trim();
  }
  return value;
}

function isWithinRoot(targetPath, rootPath) {
  const relative = path.relative(rootPath, targetPath);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function isLogLikeFile(name) {
  return /\.(log|txt|json|out|err|trc)$/i.test(name);
}

function normalizePathList(paths, limit = DEFAULT_PATH_HISTORY_LIMIT) {
  if (!Array.isArray(paths)) return [];
  const out = [];
  const seen = new Set();

  for (const item of paths) {
    const normalized = normalizeInputPath(item);
    if (!normalized) continue;
    const dedupeKey = normalized.toLowerCase();
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    out.push(normalized);
    if (out.length >= limit) break;
  }

  return out;
}

function decodeHtmlEntities(value) {
  return String(value || "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

function htmlToPlainText(html) {
  return decodeHtmlEntities(
    String(html || "")
      .replace(/<pre><code>[\s\S]*?<\/code><\/pre>/gi, " ")
      .replace(/<code>[\s\S]*?<\/code>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/\s+/g, " ")
      .trim()
  );
}

function buildWebQueryFromFinding(finding) {
  if (!finding || typeof finding !== "object") return "";
  const pieces = [
    finding.categoryLabel,
    finding.title,
    finding.sourceName,
    Array.isArray(finding.evidence) ? finding.evidence[0] : "",
  ];
  return pieces
    .map((item) => String(item || "").trim())
    .filter(Boolean)
    .join(" ")
    .slice(0, 300);
}

function parseJsonFromText(rawText) {
  const text = String(rawText || "").trim();
  if (!text) return null;

  const candidates = [text];
  const fencedMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fencedMatch?.[1]) {
    candidates.unshift(fencedMatch[1].trim());
  }

  const objectStart = text.indexOf("{");
  const objectEnd = text.lastIndexOf("}");
  if (objectStart >= 0 && objectEnd > objectStart) {
    candidates.push(text.slice(objectStart, objectEnd + 1));
  }

  for (const candidate of candidates) {
    try {
      return JSON.parse(candidate);
    } catch {
      // continue trying alternative slices
    }
  }

  return null;
}

function extractResponseText(responseJson) {
  if (typeof responseJson?.output_text === "string") {
    return responseJson.output_text.trim();
  }

  const output = Array.isArray(responseJson?.output) ? responseJson.output : [];
  const textParts = [];

  for (const item of output) {
    const contentParts = Array.isArray(item?.content) ? item.content : [];
    for (const part of contentParts) {
      if (typeof part?.text === "string") {
        textParts.push(part.text);
      } else if (typeof part?.output_text === "string") {
        textParts.push(part.output_text);
      } else if (typeof part?.value === "string") {
        textParts.push(part.value);
      } else if (typeof part?.text?.value === "string") {
        textParts.push(part.text.value);
      }
    }
  }

  return textParts.join("\n").trim();
}

function normalizeWebSolutionItems(items, defaultSource) {
  if (!Array.isArray(items)) return [];

  return items
    .map((item, index) => {
      const title = String(item?.title || item?.name || `Solution ${index + 1}`).trim();
      const solution = String(
        item?.solution || item?.resolution || item?.steps || item?.summary || ""
      ).trim();
      const source = String(item?.source || defaultSource || "Web").trim() || "Web";
      const url = String(item?.url || item?.link || item?.reference || "").trim();
      return {
        title,
        source,
        solution,
        url,
      };
    })
    .filter((item) => item.title && item.solution);
}

function mergeUniqueSolutions(baseSolutions, incomingSolutions, limit) {
  const merged = [];
  const seen = new Set();

  for (const item of [...baseSolutions, ...incomingSolutions]) {
    if (merged.length >= limit) break;
    const title = String(item?.title || "").trim();
    const solution = String(item?.solution || "").trim();
    const url = String(item?.url || "").trim();
    if (!title || !solution) continue;

    const key = `${title.toLowerCase()}::${url.toLowerCase()}::${solution
      .slice(0, 120)
      .toLowerCase()}`;
    if (seen.has(key)) continue;
    seen.add(key);

    merged.push({
      title,
      source: String(item?.source || "Web").trim() || "Web",
      solution,
      url,
    });
  }

  return merged;
}

function buildChatgptWebPrompt({ query, finding, maxSolutions }) {
  const evidence = Array.isArray(finding?.evidence) ? finding.evidence.slice(0, 5) : [];
  return [
    "Find practical, working resolutions for this production log issue using web search.",
    `Issue query: ${query}`,
    finding?.title ? `Issue title: ${finding.title}` : "",
    finding?.categoryLabel ? `Category: ${finding.categoryLabel}` : "",
    finding?.sourceName ? `Source file: ${finding.sourceName}` : "",
    finding?.resolution ? `Current local resolution: ${finding.resolution}` : "",
    evidence.length ? `Evidence lines:\n- ${evidence.join("\n- ")}` : "",
    `Return strictly valid JSON with this shape: {"solutions":[{"title":"string","solution":"string","source":"string","url":"string"}]}`,
    `Give up to ${maxSolutions} unique solutions. Keep each solution concise and actionable.`,
    "If a URL is unavailable, use an empty string for url.",
  ]
    .filter(Boolean)
    .join("\n");
}

async function searchWithChatgptWeb({ query, finding, maxSolutions, apiKey, model }) {
  const response = await fetch(OPENAI_RESPONSES_API_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      tools: [{ type: "web_search" }],
      temperature: 0.1,
      max_output_tokens: 1400,
      input: [
        {
          role: "system",
          content:
            "You are an SRE assistant. Use web search and return only valid JSON with practical fixes.",
        },
        {
          role: "user",
          content: buildChatgptWebPrompt({ query, finding, maxSolutions }),
        },
      ],
    }),
  });

  if (!response.ok) {
    const errorText = await response.text().catch(() => "");
    const suffix = errorText ? ` ${errorText.slice(0, 160)}` : "";
    throw new Error(`ChatGPT web search failed (${response.status}).${suffix}`);
  }

  const responseJson = await response.json();
  const outputText = extractResponseText(responseJson);
  const parsed = parseJsonFromText(outputText);

  if (Array.isArray(parsed)) {
    return normalizeWebSolutionItems(parsed, "ChatGPT Web Search").slice(0, maxSolutions);
  }

  const solutionCandidates = parsed?.solutions || parsed?.items || [];
  return normalizeWebSolutionItems(solutionCandidates, "ChatGPT Web Search").slice(0, maxSolutions);
}

function extractActionableSnippet(text) {
  const normalized = String(text || "").trim();
  if (!normalized) {
    return "Review the accepted answer and adapt the fix for your runtime configuration.";
  }
  const sentences = normalized
    .split(/(?<=[.!?])\s+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 24);
  const snippet = sentences.slice(0, 2).join(" ");
  if (snippet) return snippet;
  return normalized.slice(0, 260);
}

function buildFallbackSolutions(finding) {
  const baseResolution = String(finding?.resolution || "").trim();
  return [
    {
      title: "Validate root cause",
      source: "Local analyzer",
      solution:
        baseResolution ||
        "Inspect the first exception occurrence and confirm which upstream/downstream dependency is failing.",
      url: "",
    },
    {
      title: "Correlate by request and timestamp",
      source: "Local analyzer",
      solution:
        "Match the error timestamp with request IDs, dependency logs, and infrastructure events to isolate the trigger.",
      url: "",
    },
    {
      title: "Patch and verify safely",
      source: "Local analyzer",
      solution:
        "Apply fix in lower environments, add regression tests for the signature, and monitor error rates after rollout.",
      url: "",
    },
  ];
}

async function searchStackOverflowSolutions(query, maxSolutions) {
  const searchUrl = new URL("https://api.stackexchange.com/2.3/search/advanced");
  searchUrl.searchParams.set("order", "desc");
  searchUrl.searchParams.set("sort", "relevance");
  searchUrl.searchParams.set("site", "stackoverflow");
  searchUrl.searchParams.set("accepted", "True");
  searchUrl.searchParams.set("answers", "1");
  searchUrl.searchParams.set("pagesize", String(Math.min(Math.max(maxSolutions * 2, 5), 15)));
  searchUrl.searchParams.set("q", query);

  const searchResponse = await fetch(searchUrl.toString());
  if (!searchResponse.ok) {
    throw new Error("Web search request failed.");
  }

  const searchJson = await searchResponse.json();
  const questions = Array.isArray(searchJson?.items) ? searchJson.items : [];
  if (!questions.length) return [];

  const acceptedAnswerIds = questions
    .map((question) => question.accepted_answer_id)
    .filter((value) => Number.isInteger(value));

  const answerById = new Map();
  if (acceptedAnswerIds.length) {
    const answersUrl = new URL(
      `https://api.stackexchange.com/2.3/answers/${acceptedAnswerIds.join(";")}`
    );
    answersUrl.searchParams.set("order", "desc");
    answersUrl.searchParams.set("sort", "votes");
    answersUrl.searchParams.set("site", "stackoverflow");
    answersUrl.searchParams.set("filter", "withbody");

    const answersResponse = await fetch(answersUrl.toString());
    if (answersResponse.ok) {
      const answersJson = await answersResponse.json();
      const answers = Array.isArray(answersJson?.items) ? answersJson.items : [];
      for (const answer of answers) {
        answerById.set(answer.answer_id, htmlToPlainText(answer.body || ""));
      }
    }
  }

  const solutions = [];
  for (const question of questions) {
    const title = decodeHtmlEntities(question.title || "Stack Overflow solution");
    const answerText = answerById.get(question.accepted_answer_id) || "";
    solutions.push({
      title,
      source: "Stack Overflow",
      url: question.link || "",
      votes: Number.isInteger(question.score) ? question.score : null,
      solution: extractActionableSnippet(answerText),
    });
    if (solutions.length >= maxSolutions) break;
  }

  return solutions;
}

async function readPathHistoryConfig(configFilePath, maxPaths) {
  try {
    const content = await readFile(configFilePath, "utf8");
    const json = JSON.parse(content);
    return normalizePathList(json?.recentPaths || [], maxPaths);
  } catch {
    return [];
  }
}

async function writePathHistoryConfig(configFilePath, paths, maxPaths) {
  const safePaths = normalizePathList(paths, maxPaths);
  const payload = {
    recentPaths: safePaths,
    updatedAt: new Date().toISOString(),
  };
  await writeFile(configFilePath, JSON.stringify(payload, null, 2), "utf8");
  return safePaths;
}

async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const body = Buffer.concat(chunks).toString("utf8");
  if (!body.trim()) return {};
  return JSON.parse(body);
}

async function readDirectoryLogs(directoryPath, maxFiles) {
  const entries = await readdir(directoryPath, { withFileTypes: true });
  const candidateFiles = entries.filter((entry) => entry.isFile() && isLogLikeFile(entry.name));

  const withStats = await Promise.all(
    candidateFiles.map(async (entry) => {
      const fullPath = path.join(directoryPath, entry.name);
      const fileStat = await stat(fullPath);
      return {
        fullPath,
        name: entry.name,
        modifiedAt: fileStat.mtimeMs || 0,
      };
    })
  );

  const selected = withStats
    .sort((a, b) => b.modifiedAt - a.modifiedAt)
    .slice(0, Math.max(1, maxFiles));

  return selected.map((item) => item.fullPath);
}

async function readFileTailUtf8(filePath, maxBytes) {
  const fileStat = await stat(filePath);
  const totalBytes = fileStat.size || 0;
  const safeMaxBytes = Math.max(1, maxBytes);
  const bytesToRead = Math.min(totalBytes, safeMaxBytes);
  const offset = Math.max(0, totalBytes - bytesToRead);
  const handle = await open(filePath, "r");

  try {
    const buffer = Buffer.alloc(bytesToRead);
    await handle.read(buffer, 0, bytesToRead, offset);
    const truncated = totalBytes > safeMaxBytes;
    const prefix = truncated
      ? `[truncated] showing last ${bytesToRead} bytes of ${totalBytes} bytes\n`
      : "";
    return {
      content: prefix + buffer.toString("utf8"),
      totalBytes,
      truncated,
    };
  } finally {
    await handle.close();
  }
}

async function buildLogPayload(resolvedPath, maxFiles, maxBytesPerFile) {
  const fileStat = await stat(resolvedPath);

  if (fileStat.isDirectory()) {
    const directoryLogs = await readDirectoryLogs(resolvedPath, maxFiles);
    const filesWithTail = await Promise.all(
      directoryLogs.map(async (filePath) => {
        const tail = await readFileTailUtf8(filePath, maxBytesPerFile);
        return {
          path: filePath,
          content: tail.content,
          totalBytes: tail.totalBytes,
          truncated: tail.truncated,
        };
      })
    );

    return {
      path: resolvedPath,
      mode: "directory",
      files: filesWithTail.map((file) => file.path),
      content: filesWithTail.map((file) => `--- FILE: ${file.path} ---\n${file.content}`).join("\n\n"),
      meta: filesWithTail.map((file) => ({
        path: file.path,
        totalBytes: file.totalBytes,
        truncated: file.truncated,
      })),
    };
  }

  if (fileStat.isFile()) {
    const tail = await readFileTailUtf8(resolvedPath, maxBytesPerFile);
    return {
      path: resolvedPath,
      mode: "file",
      files: [resolvedPath],
      content: tail.content,
      meta: [
        {
          path: resolvedPath,
          totalBytes: tail.totalBytes,
          truncated: tail.truncated,
        },
      ],
    };
  }

  throw new Error("Path must be a file or directory.");
}

function createLogsApiHandler({ allowedRoots, allowAnyPath, maxFiles, maxBytesPerFile }) {
  return async function logsApiHandler(req, res, next) {
    if (req.method !== "GET") {
      res.statusCode = 405;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Method not allowed" }));
      return;
    }

    const url = new URL(req.url || "/", "http://localhost");
    const inputPath = normalizeInputPath(url.searchParams.get("path"));

    if (!inputPath) {
      res.statusCode = 400;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Missing query param: path" }));
      return;
    }

    const resolvedPath = path.resolve(inputPath);
    const allowed = allowAnyPath || allowedRoots.some((root) => isWithinRoot(resolvedPath, root));

    if (!allowed) {
      res.statusCode = 403;
      res.setHeader("Content-Type", "application/json");
      res.end(
        JSON.stringify({
          error: "Path not allowed. Add parent directory to LOG_ALLOWED_ROOTS or set LOG_ALLOW_ANY_PATH=true.",
        })
      );
      return;
    }

    try {
      const payload = await buildLogPayload(resolvedPath, maxFiles, maxBytesPerFile);

      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify(payload));
    } catch (error) {
      res.statusCode = 500;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: error instanceof Error ? error.message : "Read failed" }));
    }
  };
}

function createRawLogsApiHandler({ allowedRoots, allowAnyPath, maxFiles, maxBytesPerFile }) {
  return async function rawLogsApiHandler(req, res) {
    if (req.method !== "GET") {
      res.statusCode = 405;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      res.end("Method not allowed");
      return;
    }

    const url = new URL(req.url || "/", "http://localhost");
    const inputPath = normalizeInputPath(url.searchParams.get("path"));

    if (!inputPath) {
      res.statusCode = 400;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      res.end("Missing query param: path");
      return;
    }

    const resolvedPath = path.resolve(inputPath);
    const allowed = allowAnyPath || allowedRoots.some((root) => isWithinRoot(resolvedPath, root));
    if (!allowed) {
      res.statusCode = 403;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      res.end("Path not allowed.");
      return;
    }

    try {
      const payload = await buildLogPayload(resolvedPath, maxFiles, maxBytesPerFile);
      res.statusCode = 200;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      res.end(payload.content);
    } catch (error) {
      res.statusCode = 500;
      res.setHeader("Content-Type", "text/plain; charset=utf-8");
      res.end(error instanceof Error ? error.message : "Read failed");
    }
  };
}

function createPathHistoryApiHandler({ configFilePath, maxPaths }) {
  return async function pathHistoryApiHandler(req, res) {
    if (req.method === "GET") {
      const paths = await readPathHistoryConfig(configFilePath, maxPaths);
      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ paths }));
      return;
    }

    if (req.method === "POST") {
      try {
        const body = await readJsonBody(req);
        const incomingPaths = normalizePathList(body?.paths || [], maxPaths);
        const replace = Boolean(body?.replace);
        let nextPaths;

        if (replace) {
          nextPaths = incomingPaths;
        } else {
          const existingPaths = await readPathHistoryConfig(configFilePath, maxPaths);
          nextPaths = normalizePathList([...incomingPaths, ...existingPaths], maxPaths);
        }

        const saved = await writePathHistoryConfig(configFilePath, nextPaths, maxPaths);

        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ paths: saved }));
      } catch (error) {
        res.statusCode = 400;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ error: error instanceof Error ? error.message : "Invalid payload" }));
      }
      return;
    }

    res.statusCode = 405;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Method not allowed" }));
  };
}

function createWebSolutionsApiHandler({ maxSolutions, openAiApiKey, chatgptModel }) {
  return async function webSolutionsApiHandler(req, res) {
    if (req.method !== "POST") {
      res.statusCode = 405;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Method not allowed" }));
      return;
    }

    try {
      const body = await readJsonBody(req);
      const finding = body?.finding || {};
      const requestedLimit = Number.parseInt(String(body?.limit || ""), 10);
      const limit = Number.isNaN(requestedLimit)
        ? maxSolutions
        : Math.min(Math.max(requestedLimit, 1), maxSolutions);
      const query = buildWebQueryFromFinding(finding);

      if (!query) {
        res.statusCode = 400;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ error: "Missing finding data for web search." }));
        return;
      }

      const warnings = [];
      let solutions = [];

      if (openAiApiKey) {
        try {
          const chatgptSolutions = await searchWithChatgptWeb({
            query,
            finding,
            maxSolutions: limit,
            apiKey: openAiApiKey,
            model: chatgptModel,
          });
          solutions = mergeUniqueSolutions(solutions, chatgptSolutions, limit);
        } catch (error) {
          warnings.push(error instanceof Error ? error.message : "ChatGPT web search failed.");
        }
      } else {
        warnings.push("Set OPENAI_API_KEY to enable ChatGPT web search results.");
      }

      if (solutions.length < limit) {
        try {
          const stackOverflowSolutions = await searchStackOverflowSolutions(query, limit);
          solutions = mergeUniqueSolutions(solutions, stackOverflowSolutions, limit);
        } catch (error) {
          warnings.push(error instanceof Error ? error.message : "Stack Overflow search failed.");
        }
      }

      if (!solutions.length) {
        warnings.push("No web matches found for this issue. Showing local fallback guidance.");
      }

      solutions = mergeUniqueSolutions(solutions, buildFallbackSolutions(finding), limit);
      const warning = warnings.filter(Boolean).join(" ");

      res.statusCode = 200;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ query, warning, solutions }));
    } catch (error) {
      res.statusCode = 400;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: error instanceof Error ? error.message : "Invalid payload" }));
    }
  };
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const allowedRoots = parseAllowedRoots(env.LOG_ALLOWED_ROOTS);
  const allowAnyPath = env.LOG_ALLOW_ANY_PATH === "true";
  const maxFilesFromEnv = Number.parseInt(env.LOG_MAX_FILES || "", 10);
  const maxFiles = Number.isNaN(maxFilesFromEnv) ? 30 : Math.min(Math.max(maxFilesFromEnv, 1), 30);
  const maxBytesFromEnv = Number.parseInt(env.LOG_MAX_BYTES || "", 10);
  const maxBytesPerFile = Number.isNaN(maxBytesFromEnv)
    ? 2 * 1024 * 1024
    : Math.min(Math.max(maxBytesFromEnv, 64 * 1024), 50 * 1024 * 1024);
  const pathHistoryLimitFromEnv = Number.parseInt(env.LOG_PATH_HISTORY_LIMIT || "", 10);
  const pathHistoryLimit = Number.isNaN(pathHistoryLimitFromEnv)
    ? DEFAULT_PATH_HISTORY_LIMIT
    : Math.min(Math.max(pathHistoryLimitFromEnv, 1), DEFAULT_PATH_HISTORY_LIMIT);
  const configFilePath = path.resolve(
    env.LOG_ANALYZER_CONFIG_FILE || path.join(projectRoot, ".log-analyzer.config.json")
  );
  const logsApiHandler = createLogsApiHandler({
    allowedRoots,
    allowAnyPath,
    maxFiles,
    maxBytesPerFile,
  });
  const rawLogsApiHandler = createRawLogsApiHandler({
    allowedRoots,
    allowAnyPath,
    maxFiles,
    maxBytesPerFile,
  });
  const pathHistoryApiHandler = createPathHistoryApiHandler({
    configFilePath,
    maxPaths: pathHistoryLimit,
  });
  const webSolutionLimitFromEnv = Number.parseInt(env.LOG_WEB_SOLUTION_LIMIT || "", 10);
  const webSolutionLimit = Number.isNaN(webSolutionLimitFromEnv)
    ? DEFAULT_WEB_SOLUTION_LIMIT
    : Math.min(Math.max(webSolutionLimitFromEnv, 1), 10);
  const openAiApiKey = String(env.OPENAI_API_KEY || env.VITE_OPENAI_API_KEY || "").trim();
  const chatgptWebSearchModel =
    String(env.LOG_CHATGPT_WEB_SEARCH_MODEL || DEFAULT_CHATGPT_WEB_SEARCH_MODEL).trim() ||
    DEFAULT_CHATGPT_WEB_SEARCH_MODEL;
  const webSolutionsApiHandler = createWebSolutionsApiHandler({
    maxSolutions: webSolutionLimit,
    openAiApiKey,
    chatgptModel: chatgptWebSearchModel,
  });

  return {
    plugins: [
      react({
        babel: {
          plugins: [["babel-plugin-react-compiler"]],
        },
      }),
      {
        name: "local-logs-api",
        configureServer(server) {
          server.middlewares.use("/api/logs/raw", rawLogsApiHandler);
          server.middlewares.use("/api/logs", logsApiHandler);
          server.middlewares.use("/api/path-history", pathHistoryApiHandler);
          server.middlewares.use("/api/web-solutions", webSolutionsApiHandler);
        },
        configurePreviewServer(server) {
          server.middlewares.use("/api/logs/raw", rawLogsApiHandler);
          server.middlewares.use("/api/logs", logsApiHandler);
          server.middlewares.use("/api/path-history", pathHistoryApiHandler);
          server.middlewares.use("/api/web-solutions", webSolutionsApiHandler);
        },
      },
    ],
  };
});
