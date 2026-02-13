import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./log-analyzer.css";

const MAX_FILES = 30;
const SEARCH_MAX_RESULTS = 500;
const PATH_HISTORY_LIMIT = 30;
const WEB_SOLUTION_LIMIT = 5;
const WEB_SOURCE_OPTIONS = [
  { id: "local", label: "Local Analyzer" },
  { id: "google", label: "Google Links" },
  { id: "gemini", label: "Gemini (Free Tier)" },
  { id: "groq", label: "Groq (Free Tier)" },
  { id: "openrouter", label: "OpenRouter Free" },
  { id: "stackoverflow", label: "Stack Overflow" },
  { id: "github", label: "GitHub Issues" },
  { id: "chatgpt", label: "ChatGPT Web (Paid)" },
];
const DEFAULT_WEB_SOURCE_PRIORITY = WEB_SOURCE_OPTIONS.map((item) => item.id);
const STORAGE_KEYS = {
  excluded: "log_analyzer_excluded_fingerprints",
  excludedRecords: "log_analyzer_excluded_records",
  reviews: "log_analyzer_review_records",
  learned: "log_analyzer_learned_scenarios",
  history: "log_analyzer_feedback_history",
  pathHistory: "log_analyzer_path_history",
};

const RULES = [
  {
    id: "conn-refused",
    title: "Service is unreachable",
    severity: "high",
    regex: /(ECONNREFUSED|connection refused|unable to connect)/i,
    resolution:
      "Verify host/port, confirm the target service is running, and check firewall/security-group rules.",
  },
  {
    id: "timeout",
    title: "Request timeout",
    severity: "medium",
    regex: /(ETIMEDOUT|timeout|timed out)/i,
    resolution:
      "Check upstream latency, increase client timeout only if needed, and investigate network or dependency bottlenecks.",
  },
  {
    id: "oom",
    title: "Out of memory",
    severity: "high",
    regex: /(OutOfMemory|Java heap space|heap out of memory|oom-killer)/i,
    resolution:
      "Increase memory limits, reduce in-memory batch size, and profile for memory leaks.",
  },
  {
    id: "auth-failure",
    title: "Authentication/Authorization failure",
    severity: "medium",
    regex: /(401|403|unauthorized|forbidden|invalid token|token expired)/i,
    resolution:
      "Validate token generation and expiry, verify credentials, and confirm role/permission mappings.",
  },
  {
    id: "server-5xx",
    title: "Server-side exception",
    severity: "high",
    regex: /( 500 |HTTP 500|5\d\d|NullPointerException|Unhandled exception|Traceback)/i,
    resolution:
      "Inspect stack trace around the failing request, add defensive null checks, and patch the failing code path.",
  },
  {
    id: "db-connection",
    title: "Database connectivity issue",
    severity: "high",
    regex: /(database.*(down|unreachable|refused)|SQLSTATE|could not connect to server)/i,
    resolution:
      "Confirm DB health, credentials, connection string, and max connection pool settings.",
  },
];

function safeReadStorage(key, fallback) {
  if (typeof window === "undefined") return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function safeWriteStorage(key, value) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(key, JSON.stringify(value));
}

function severityScore(level) {
  if (level === "high") return 3;
  if (level === "medium") return 2;
  return 1;
}

function normalizeLine(line) {
  return line.toLowerCase().replace(/\s+/g, " ").trim();
}

function normalizePathInput(rawPath) {
  let value = String(rawPath || "").trim();
  if (!value) return "";
  const quotedWithDouble = value.startsWith('"') && value.endsWith('"');
  const quotedWithSingle = value.startsWith("'") && value.endsWith("'");
  if (quotedWithDouble || quotedWithSingle) {
    value = value.slice(1, -1).trim();
  }
  return value;
}

function sourceNameFromPath(sourcePath) {
  const normalized = normalizePathInput(sourcePath);
  if (!normalized) return "unknown";
  const parts = normalized.split(/[\\/]/).filter(Boolean);
  return parts[parts.length - 1] || normalized;
}

function displaySourcePath(sourcePath) {
  const normalized = normalizePathInput(sourcePath);
  return normalized === "manual-input" || !normalized ? "manual-input" : normalized;
}

function displayCompactPath(sourcePath) {
  const fullPath = displaySourcePath(sourcePath);
  if (fullPath === "manual-input") return fullPath;
  const fileName = sourceNameFromPath(fullPath);
  const prefix = fullPath.slice(0, 15);
  return `${prefix}...${fileName}`;
}

function isLikelyFileSystemPath(sourcePath) {
  const normalized = normalizePathInput(sourcePath);
  if (!normalized) return false;
  if (normalized.startsWith("\\\\")) return true;
  if (normalized.startsWith("/")) return true;
  if (/^[A-Za-z]:[\\/]/.test(normalized)) return true;
  return /[\\/]/.test(normalized);
}

function buildLogViewUrl(sourcePath, uploadedFileLinks = {}) {
  const normalized = normalizePathInput(sourcePath);
  if (!normalized || normalized === "manual-input") return "";
  if (uploadedFileLinks[normalized]) {
    return uploadedFileLinks[normalized];
  }
  if (!isLikelyFileSystemPath(normalized)) {
    return "";
  }
  return `/api/logs/raw?path=${encodeURIComponent(normalized)}`;
}

function parseLogEntries(rawLogs) {
  const lines = String(rawLogs || "").split(/\r?\n/);
  const entries = [];
  let currentSourcePath = "manual-input";
  let currentSourceName = "manual-input";
  const lineCounters = new Map();

  for (const line of lines) {
    if (!line.trim()) continue;

    const markerMatch = line.match(/^---\s*(FILE|PATH)\s*:\s*(.+?)\s*---$/i);
    if (markerMatch) {
      currentSourcePath = normalizePathInput(markerMatch[2]);
      currentSourceName = sourceNameFromPath(currentSourcePath);
      if (!lineCounters.has(currentSourcePath)) {
        lineCounters.set(currentSourcePath, 0);
      }
      continue;
    }

    const lineNumber = (lineCounters.get(currentSourcePath) || 0) + 1;
    lineCounters.set(currentSourcePath, lineNumber);

    entries.push({
      line,
      lineNumber,
      sourcePath: currentSourcePath,
      sourceName: currentSourceName,
    });
  }

  return entries;
}

function searchInEntries(entries, query, caseSensitive, maxResults = SEARCH_MAX_RESULTS) {
  const needle = caseSensitive ? query : query.toLowerCase();
  const results = [];

  for (const entry of entries) {
    const haystack = caseSensitive ? entry.line : entry.line.toLowerCase();
    if (!haystack.includes(needle)) continue;

    results.push({
      sourceName: entry.sourceName,
      sourcePath: entry.sourcePath,
      lineNumber: entry.lineNumber || 0,
      line: entry.line,
    });

    if (results.length >= maxResults) break;
  }

  return results;
}

function mergePathHistory(existingPaths, incomingPaths, limit = PATH_HISTORY_LIMIT) {
  const merged = [];
  const seen = new Set();
  const source = [...incomingPaths, ...existingPaths];

  for (const item of source) {
    const pathValue = normalizePathInput(item);
    if (!pathValue) continue;
    const dedupeKey = pathValue.toLowerCase();
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    merged.push(pathValue);
    if (merged.length >= limit) break;
  }

  return merged;
}

function normalizeWebSourcePriority(inputPriority) {
  if (!Array.isArray(inputPriority)) {
    return [...DEFAULT_WEB_SOURCE_PRIORITY];
  }

  const normalized = [];
  for (const item of inputPriority) {
    const value = String(item || "").trim().toLowerCase();
    if (!value) continue;
    if (!DEFAULT_WEB_SOURCE_PRIORITY.includes(value)) continue;
    if (normalized.includes(value)) continue;
    normalized.push(value);
  }

  for (const value of DEFAULT_WEB_SOURCE_PRIORITY) {
    if (!normalized.includes(value)) {
      normalized.push(value);
    }
  }

  return normalized.slice(0, DEFAULT_WEB_SOURCE_PRIORITY.length);
}

async function loadPathHistoryFromConfig() {
  try {
    const response = await fetch("/api/path-history", { method: "GET" });
    if (!response.ok) return [];
    const json = await response.json();
    return Array.isArray(json.paths) ? json.paths : [];
  } catch {
    return [];
  }
}

async function savePathHistoryToConfig(paths, options = {}) {
  try {
    const response = await fetch("/api/path-history", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ paths, replace: Boolean(options.replace) }),
    });
    if (!response.ok) return [];
    const json = await response.json();
    return Array.isArray(json.paths) ? json.paths : [];
  } catch {
    return [];
  }
}

function groupEntriesBySource(entries) {
  const grouped = new Map();

  for (const entry of entries) {
    const sourcePath = entry.sourcePath || "manual-input";
    if (!grouped.has(sourcePath)) {
      grouped.set(sourcePath, {
        sourcePath,
        sourceName: entry.sourceName || sourceNameFromPath(sourcePath),
        lines: [],
      });
    }
    grouped.get(sourcePath).lines.push(entry.line);
  }

  return [...grouped.values()];
}

function findingFingerprint(finding) {
  const sample = finding.evidence?.[0] || finding.title || finding.id;
  return `${finding.id}::${normalizeLine(sample).slice(0, 140)}`;
}

function extractKeywordFromEvidence(evidence) {
  const line = evidence?.[0] || "";
  const candidates = line.toLowerCase().match(/[a-z0-9._:-]{5,}/g) || [];
  const stop = new Set([
    "error",
    "exception",
    "failed",
    "request",
    "service",
    "server",
    "traceback",
    "warning",
  ]);
  return candidates.find((item) => !stop.has(item)) || candidates[0] || "";
}

function extractExceptionName(line) {
  const causedByMatch = line.match(
    /caused by:\s*([A-Za-z_$][A-Za-z0-9_$.]*(?:Exception|Error|Throwable))/i
  );
  if (causedByMatch) {
    return causedByMatch[1].split(".").pop() || causedByMatch[1];
  }

  const exceptionMatch = line.match(/\b([A-Za-z_$][A-Za-z0-9_$.]*(?:Exception|Error|Throwable))\b/);
  if (!exceptionMatch) return "";
  return exceptionMatch[1].split(".").pop() || exceptionMatch[1];
}

function normalizeForSimilarity(line) {
  return normalizeLine(
    line
      .replace(/\[[^\]]+\]/g, " ")
      .replace(
        /\b\d{4}[/-]\d{2}[/-]\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,:]\d+)?(?:\s*[A-Z]{2,5})?\b/g,
        " "
      )
      .replace(/\b\d{1,3}(?:\.\d{1,3}){3}\b/g, "<ip>")
      .replace(/\b0x[0-9a-f]+\b/gi, "<hex>")
      .replace(/\b[0-9a-f]{8,}\b/gi, "<id>")
      .replace(/\b\d+\b/g, "<n>")
      .replace(/"[^"]*"/g, "<str>")
      .replace(/'[^']*'/g, "<str>")
  ).slice(0, 180);
}

function isProblemLikeLine(line) {
  return /(exception|error|fatal|severe|failed|failure|panic|traceback|stack ?trace|caused by)/i.test(
    line
  );
}

function formatSimilarityLabel(signature) {
  if (!signature) return "Similar issue pattern";
  const compact = signature.replace(/<(n|id|ip|hex|str)>/g, "*");
  const clipped = compact.length > 85 ? `${compact.slice(0, 82)}...` : compact;
  return `Similar: ${clipped}`;
}

function inferSeverityFromGroup(exceptionName, evidenceLines) {
  const context = `${exceptionName || ""} ${evidenceLines.slice(0, 5).join(" ")}`.toLowerCase();
  if (
    /outofmemory|oom|fatal|panic|critical|segmentation|stack overflow|assertionerror|5\d\d/.test(
      context
    )
  ) {
    return "high";
  }
  if (/error|exception|failed|timeout|refused|unauthorized|forbidden|denied/.test(context)) {
    return "medium";
  }
  return "low";
}

function pickBestRuleForEvidence(evidenceLines) {
  let best = null;
  for (const rule of RULES) {
    const matches = evidenceLines.reduce((count, line) => count + (rule.regex.test(line) ? 1 : 0), 0);
    if (!matches) continue;
    if (!best || matches > best.matches) {
      best = { matches, rule };
    }
  }
  return best?.rule || null;
}

function buildLearnedFindings(entries, learnedScenarios) {
  const findings = [];

  for (const scenario of learnedScenarios) {
    if (!scenario.keyword) continue;
    const regex = new RegExp(scenario.keyword.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i");
    const matches = entries.filter((entry) => regex.test(entry.line));
    if (!matches.length) continue;

    const groupedMatches = groupEntriesBySource(matches);
    groupedMatches.forEach((group) => {
      const categoryLabel = `Learned: ${scenario.title}`;
      findings.push({
        id: `learned-${scenario.fingerprint}::${group.sourcePath}`,
        categoryKey: `learned-${scenario.fingerprint}`,
        categoryLabel,
        title: `${group.sourceName} - ${categoryLabel}`,
        sourcePath: group.sourcePath,
        sourceName: group.sourceName,
        severity: scenario.severity || "medium",
        count: group.lines.length,
        evidence: group.lines,
        resolution: scenario.resolution || "Previously confirmed issue pattern.",
      });
    });
  }

  return findings;
}

function dedupeFindings(items) {
  const map = new Map();
  for (const item of items) {
    const key = findingFingerprint(item);
    if (!map.has(key)) {
      map.set(key, item);
      continue;
    }
    const existing = map.get(key);
    if ((item.count || 0) > (existing.count || 0)) {
      map.set(key, item);
    }
  }
  return [...map.values()];
}

function analyzeWithRules(entries, learnedScenarios) {
  const grouped = new Map();

  for (const entry of entries) {
    const exceptionName = extractExceptionName(entry.line);
    const problemLike = isProblemLikeLine(entry.line);
    if (!exceptionName && !problemLike) continue;

    const similarity = normalizeForSimilarity(entry.line);
    if (!exceptionName && similarity.length < 14) continue;

    const categoryKey = exceptionName
      ? `exception:${exceptionName.toLowerCase()}`
      : `similar:${similarity}`;
    const categoryLabel = exceptionName ? exceptionName : formatSimilarityLabel(similarity);
    const sourcePath = entry.sourcePath || "manual-input";
    const groupKey = `${sourcePath}::${categoryKey}`;

    if (!grouped.has(groupKey)) {
      grouped.set(groupKey, {
        id: groupKey,
        categoryKey,
        categoryLabel,
        title: `${entry.sourceName} - ${categoryLabel}`,
        sourcePath,
        sourceName: entry.sourceName || sourceNameFromPath(sourcePath),
        severity: "low",
        count: 0,
        evidence: [],
        resolution:
          "Review stack traces and surrounding log lines to validate root cause and impacted dependencies.",
      });
    }

    const current = grouped.get(groupKey);
    current.evidence.push(entry.line);
    current.count += 1;
  }

  const findings = [...grouped.values()].map((item) => {
    const matchedRule = pickBestRuleForEvidence(item.evidence);
    return {
      ...item,
      severity: matchedRule?.severity || inferSeverityFromGroup(item.categoryLabel, item.evidence),
      resolution:
        matchedRule?.resolution ||
        `Investigate ${item.categoryLabel} by reviewing first occurrence and linked stack trace frames.`,
    };
  });

  if (!findings.length) {
    const fallbackBySource = groupEntriesBySource(entries);
    fallbackBySource.forEach((group) => {
      findings.push({
        id: `generic-error::${group.sourcePath}`,
        categoryKey: "generic-error",
        categoryLabel: "Generic runtime issue",
        title: `${group.sourceName} - Generic runtime issue`,
        sourcePath: group.sourcePath,
        sourceName: group.sourceName,
        severity: "low",
        count: group.lines.length,
        evidence: group.lines,
        resolution:
          "Capture larger error windows and include correlation IDs to improve exception-level grouping.",
      });
    });
  }

  const learnedFindings = buildLearnedFindings(entries, learnedScenarios);
  return dedupeFindings([...findings, ...learnedFindings]).sort(
    (a, b) => severityScore(b.severity) - severityScore(a.severity)
  );
}

function parseTimestamp(line) {
  const isoMatch = line.match(
    /(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})?/
  );
  if (isoMatch) {
    const isoDate = new Date(`${isoMatch[1]}T${isoMatch[2]}`);
    if (!Number.isNaN(isoDate.getTime())) return isoDate;
  }

  const slashMatch = line.match(/(\d{4}\/\d{2}\/\d{2})[ T](\d{2}:\d{2}:\d{2})/);
  if (slashMatch) {
    const normalized = `${slashMatch[1].replaceAll("/", "-")}T${slashMatch[2]}`;
    const slashDate = new Date(normalized);
    if (!Number.isNaN(slashDate.getTime())) return slashDate;
  }

  return null;
}

function applyDateTimeFilter(entries, filters) {
  const { dateFrom, dateTo, timeFrom, timeTo } = filters;
  const hasDateFilter = Boolean(dateFrom || dateTo);
  const hasTimeFilter = Boolean(timeFrom || timeTo);

  if (!hasDateFilter && !hasTimeFilter) return entries;

  return entries.filter((entry) => {
    const stamp = parseTimestamp(entry.line);
    if (!stamp) return false;

    if (dateFrom) {
      const from = new Date(`${dateFrom}T00:00:00`);
      if (stamp < from) return false;
    }

    if (dateTo) {
      const to = new Date(`${dateTo}T23:59:59`);
      if (stamp > to) return false;
    }

    const hh = String(stamp.getHours()).padStart(2, "0");
    const mm = String(stamp.getMinutes()).padStart(2, "0");
    const currentTime = `${hh}:${mm}`;

    if (timeFrom && currentTime < timeFrom) return false;
    if (timeTo && currentTime > timeTo) return false;

    return true;
  });
}

async function analyzeWithAI(rawLogs, findings) {
  const endpoint = import.meta.env.VITE_LOG_ANALYZER_ENDPOINT;
  if (endpoint) {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ logs: rawLogs, findings }),
    });
    if (!response.ok) {
      throw new Error("AI endpoint request failed.");
    }
    return response.json();
  }

  const openAiKey = import.meta.env.VITE_OPENAI_API_KEY;
  if (!openAiKey) {
    return null;
  }

  const prompt = [
    "You are an SRE incident assistant.",
    "Analyze logs and return concise JSON only.",
    'Return: {"summary":"...", "top_causes":[{"cause":"...","fix":"...","confidence":"high|medium|low"}]}',
    "Logs:",
    rawLogs.slice(0, 10000),
  ].join("\n");

  const response = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${openAiKey}`,
    },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      response_format: { type: "json_object" },
      messages: [{ role: "user", content: prompt }],
      temperature: 0.1,
    }),
  });

  if (!response.ok) {
    throw new Error("OpenAI request failed.");
  }

  const json = await response.json();
  const content = json.choices?.[0]?.message?.content;
  return content ? JSON.parse(content) : null;
}

async function fetchLogsFromPath(path) {
  const normalizedPath = normalizePathInput(path);
  const watchEndpoint = import.meta.env.VITE_LOG_WATCH_ENDPOINT || "/api/logs";
  const url = `${watchEndpoint}?path=${encodeURIComponent(normalizedPath)}`;
  const response = await fetch(url, { method: "GET" });

  if (!response.ok) {
    let message = `Read failed for path: ${normalizedPath}`;
    try {
      const payload = await response.json();
      if (payload?.error) {
        message = `${message}. ${payload.error}`;
      }
    } catch {
      // ignore parse failures and keep default message
    }
    throw new Error(message);
  }

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const json = await response.json();
    return String(json.logs || json.content || "");
  }

  return response.text();
}

function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (event) => resolve(String(event.target?.result || ""));
    reader.onerror = () => reject(new Error(`Failed to read file: ${file.name}`));
    reader.readAsText(file);
  });
}

export default function LogAnalyzer() {
  const [logs, setLogs] = useState("");
  const [findings, setFindings] = useState([]);
  const [aiResult, setAiResult] = useState(null);
  const [webSolutionsByFinding, setWebSolutionsByFinding] = useState({});
  const [webSolutionBusyByFinding, setWebSolutionBusyByFinding] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [timeFrom, setTimeFrom] = useState("");
  const [timeTo, setTimeTo] = useState("");
  const [problemType, setProblemType] = useState("all");
  const [pathSuggestionInput, setPathSuggestionInput] = useState("");
  const [searchText, setSearchText] = useState("");
  const [searchCaseSensitive, setSearchCaseSensitive] = useState(false);
  const [pathListInput, setPathListInput] = useState("");
  const [autoRead, setAutoRead] = useState(false);
  const [pollSeconds, setPollSeconds] = useState("30");
  const [lastReadAt, setLastReadAt] = useState("");
  const [clearExclusionTargets, setClearExclusionTargets] = useState([]);
  const [showExclusionManager, setShowExclusionManager] = useState(false);
  const [isExclusionDropdownOpen, setIsExclusionDropdownOpen] = useState(false);
  const [excludedFingerprints, setExcludedFingerprints] = useState(
    () => new Set(safeReadStorage(STORAGE_KEYS.excluded, []))
  );
  const [excludedRecords, setExcludedRecords] = useState(() =>
    safeReadStorage(STORAGE_KEYS.excludedRecords, {})
  );
  const [reviewRecords, setReviewRecords] = useState(() =>
    safeReadStorage(STORAGE_KEYS.reviews, {})
  );
  const [learnedScenarios, setLearnedScenarios] = useState(() =>
    safeReadStorage(STORAGE_KEYS.learned, [])
  );
  const [feedbackHistory, setFeedbackHistory] = useState(() =>
    safeReadStorage(STORAGE_KEYS.history, [])
  );
  const [pathHistory, setPathHistory] = useState(() =>
    safeReadStorage(STORAGE_KEYS.pathHistory, [])
  );
  const [uploadedFileLinks, setUploadedFileLinks] = useState({});
  const [webSourcePriority, setWebSourcePriority] = useState(() =>
    normalizeWebSourcePriority(DEFAULT_WEB_SOURCE_PRIORITY)
  );
  const exclusionDropdownRef = useRef(null);

  const hasConfig = useMemo(
    () =>
      Boolean(
        import.meta.env.VITE_LOG_ANALYZER_ENDPOINT || import.meta.env.VITE_OPENAI_API_KEY
      ),
    []
  );

  const parsedPaths = useMemo(() => {
    const unique = new Set(
      pathListInput
        .split(/\r?\n|,/)
        .map((item) => normalizePathInput(item))
        .filter(Boolean)
    );
    return [...unique].slice(0, MAX_FILES);
  }, [pathListInput]);

  const pathSuggestions = useMemo(
    () => pathHistory.filter((path) => !parsedPaths.some((used) => used.toLowerCase() === path.toLowerCase())),
    [pathHistory, parsedPaths]
  );

  const allEntries = useMemo(() => parseLogEntries(logs), [logs]);
  const trimmedSearchText = searchText.trim();

  const searchResults = useMemo(() => {
    if (!trimmedSearchText) return [];
    return searchInEntries(allEntries, trimmedSearchText, searchCaseSensitive, SEARCH_MAX_RESULTS);
  }, [allEntries, trimmedSearchText, searchCaseSensitive]);

  const searchSummaryBySource = useMemo(() => {
    const map = new Map();
    searchResults.forEach((result) => {
      const key = displaySourcePath(result.sourcePath);
      map.set(key, (map.get(key) || 0) + 1);
    });
    return [...map.entries()].map(([sourcePath, count]) => ({ sourcePath, count }));
  }, [searchResults]);

  const webSourceOrderLabel = useMemo(
    () =>
      normalizeWebSourcePriority(webSourcePriority)
        .map((id) => WEB_SOURCE_OPTIONS.find((option) => option.id === id)?.label || id)
        .join(" -> "),
    [webSourcePriority]
  );

  const exclusionList = useMemo(
    () =>
      [...excludedFingerprints]
        .map((fingerprint) => {
          const record = excludedRecords[fingerprint] || {};
          const review = reviewRecords[fingerprint] || {};
          return {
            fingerprint,
            title: record.title || review.title || "Excluded finding",
            sourceName: record.sourceName || "",
            updatedAt: record.updatedAt || review.updatedAt || "",
          };
        })
        .sort((a, b) => (b.updatedAt || "").localeCompare(a.updatedAt || "")),
    [excludedFingerprints, excludedRecords, reviewRecords]
  );

  const selectedClearAll = useMemo(
    () => clearExclusionTargets.includes("__all__"),
    [clearExclusionTargets]
  );

  const selectedExclusions = useMemo(() => {
    if (!clearExclusionTargets.length || selectedClearAll) return [];
    const selected = new Set(clearExclusionTargets);
    return exclusionList.filter((item) => selected.has(item.fingerprint));
  }, [clearExclusionTargets, exclusionList, selectedClearAll]);

  const clearExclusionSummaryLabel = useMemo(() => {
    if (!exclusionList.length) return "No exclusions available";
    if (selectedClearAll) return `All exclusions (${exclusionList.length}) selected`;
    if (!clearExclusionTargets.length) return "Select exclusions...";
    if (selectedExclusions.length === 1) {
      const item = selectedExclusions[0];
      return item?.sourceName ? `${item.title} [${item.sourceName}]` : item?.title || "1 selected";
    }
    return `${selectedExclusions.length} exclusions selected`;
  }, [clearExclusionTargets.length, exclusionList, selectedClearAll, selectedExclusions]);

  const analyzeLogs = useCallback(
    async (rawLogs, includeAi) => {
      const entries = parseLogEntries(rawLogs);
      const filteredEntries = applyDateTimeFilter(entries, { dateFrom, dateTo, timeFrom, timeTo });
      const filteredLogs = filteredEntries
        .map((entry) => `[${entry.sourceName}] ${entry.line}`)
        .join("\n");

      const results = analyzeWithRules(filteredEntries, learnedScenarios).filter(
        (item) => !excludedFingerprints.has(findingFingerprint(item))
      );

      setWebSolutionsByFinding({});
      setWebSolutionBusyByFinding({});
      setFindings(results);
      if (includeAi) {
        const ai = await analyzeWithAI(filteredLogs, results);
        setAiResult(ai);
      }
    },
    [dateFrom, dateTo, timeFrom, timeTo, learnedScenarios, excludedFingerprints]
  );

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.excluded, [...excludedFingerprints]);
  }, [excludedFingerprints]);

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.excludedRecords, excludedRecords);
  }, [excludedRecords]);

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.reviews, reviewRecords);
  }, [reviewRecords]);

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.learned, learnedScenarios);
  }, [learnedScenarios]);

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.history, feedbackHistory);
  }, [feedbackHistory]);

  useEffect(() => {
    safeWriteStorage(STORAGE_KEYS.pathHistory, pathHistory);
    void savePathHistoryToConfig(pathHistory, { replace: true });
  }, [pathHistory]);

  useEffect(() => {
    if (!clearExclusionTargets.length) return;
    setClearExclusionTargets((prev) =>
      prev.filter((item) => item === "__all__" || excludedFingerprints.has(item))
    );
  }, [clearExclusionTargets, excludedFingerprints]);

  useEffect(() => {
    if (!showExclusionManager) {
      setIsExclusionDropdownOpen(false);
      return;
    }

    function onDocumentMouseDown(event) {
      if (!exclusionDropdownRef.current) return;
      if (!exclusionDropdownRef.current.contains(event.target)) {
        setIsExclusionDropdownOpen(false);
      }
    }

    function onDocumentKeyDown(event) {
      if (event.key === "Escape") {
        setIsExclusionDropdownOpen(false);
      }
    }

    document.addEventListener("mousedown", onDocumentMouseDown);
    document.addEventListener("keydown", onDocumentKeyDown);
    return () => {
      document.removeEventListener("mousedown", onDocumentMouseDown);
      document.removeEventListener("keydown", onDocumentKeyDown);
    };
  }, [showExclusionManager]);

  useEffect(() => {
    return () => {
      Object.values(uploadedFileLinks).forEach((url) => {
        try {
          URL.revokeObjectURL(url);
        } catch {
          // ignore cleanup errors
        }
      });
    };
  }, [uploadedFileLinks]);

  useEffect(() => {
    let cancelled = false;
    void loadPathHistoryFromConfig().then((paths) => {
      if (cancelled || !paths.length) return;
      setPathHistory((prev) => mergePathHistory(prev, paths));
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!autoRead) return;
    if (!parsedPaths.length) return;
    setPathHistory((prev) => mergePathHistory(prev, parsedPaths));

    let cancelled = false;
    let inFlight = false;
    const parsedInterval = Number.parseInt(pollSeconds, 10);
    const intervalMs = Number.isNaN(parsedInterval)
      ? 5000
      : Math.min(Math.max(parsedInterval, 1), 3600) * 1000;

    const tick = async () => {
      if (inFlight) return;
      inFlight = true;
      const settled = await Promise.allSettled(
        parsedPaths.map(async (path) => ({ path, content: await fetchLogsFromPath(path) }))
      );
      try {
        if (cancelled) return;

        const successes = settled
          .filter((result) => result.status === "fulfilled")
          .map((result) => result.value);
        const failures = settled.filter((result) => result.status === "rejected").length;

        if (!successes.length) {
          setError("Failed reading all configured paths.");
          return;
        }

        const mergedLogs = successes
          .map((item) => `--- PATH: ${item.path} ---\n${item.content}`)
          .join("\n\n");

        setUploadedFileLinks({});
        setLogs(mergedLogs);
        setLastReadAt(new Date().toLocaleString());
        setError(failures ? `Read ${successes.length}/${parsedPaths.length} paths.` : "");

        try {
          await analyzeLogs(mergedLogs, false);
        } catch (e) {
          setError(e instanceof Error ? e.message : "Auto analyze failed.");
        }
      } finally {
        inFlight = false;
      }
    };

    tick();
    const timer = setInterval(tick, intervalMs);

    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [autoRead, parsedPaths, pollSeconds, analyzeLogs]);

  const visibleFindings = useMemo(() => {
    if (problemType === "all") return findings;
    return findings.filter((item) => (item.categoryKey || item.id) === problemType);
  }, [findings, problemType]);

  const problemTypeOptions = useMemo(() => {
    const map = new Map();
    findings.forEach((item) => {
      const key = item.categoryKey || item.id;
      const label = item.categoryLabel || item.title;
      if (!map.has(key)) {
        map.set(key, label);
      }
    });
    return [...map.entries()].map(([id, title]) => ({ id, title }));
  }, [findings]);

  useEffect(() => {
    if (problemType === "all") return;
    if (!problemTypeOptions.some((item) => item.id === problemType)) {
      setProblemType("all");
    }
  }, [problemType, problemTypeOptions]);

  function pushFeedbackHistory(payload) {
    setFeedbackHistory((prev) =>
      [{ ...payload, at: new Date().toISOString() }, ...prev].slice(0, 1000)
    );
  }

  function clearAllExclusions() {
    setExcludedFingerprints(new Set());
    setExcludedRecords({});
    pushFeedbackHistory({ action: "clear_all_exclusions" });
  }

  function toggleClearExclusionTarget(value) {
    const target = String(value || "").trim();
    if (!target) return;

    setClearExclusionTargets((prev) => {
      const hasTarget = prev.includes(target);

      if (target === "__all__") {
        return hasTarget ? [] : ["__all__"];
      }

      const base = prev.filter((item) => item !== "__all__");
      if (hasTarget) {
        return base.filter((item) => item !== target);
      }

      return [...base, target];
    });
  }

  function applyClearExclusionSelection() {
    if (!clearExclusionTargets.length) return;
    if (selectedClearAll) {
      clearAllExclusions();
      setClearExclusionTargets([]);
      setIsExclusionDropdownOpen(false);
      return;
    }
    removeExcludedFingerprints(clearExclusionTargets);
    setClearExclusionTargets([]);
    setIsExclusionDropdownOpen(false);
  }

  function addPathToInput(rawPath) {
    const nextPath = normalizePathInput(rawPath);
    if (!nextPath) return;

    setPathListInput((prev) => {
      const existing = prev
        .split(/\r?\n|,/)
        .map((item) => normalizePathInput(item))
        .filter(Boolean);

      const merged = mergePathHistory(existing, [nextPath], MAX_FILES);
      return merged.join("\n");
    });

    setPathHistory((prev) => mergePathHistory(prev, [nextPath]));
    setPathSuggestionInput("");
  }

  async function clearSavedPaths() {
    if (!pathHistory.length) return;
    const shouldClear = window.confirm("Clear all saved path suggestions?");
    if (!shouldClear) return;

    setPathSuggestionInput("");
    setPathHistory([]);
    await savePathHistoryToConfig([], { replace: true });
  }

  function updateWebSourcePriorityAt(position, sourceId) {
    const nextSource = String(sourceId || "").trim().toLowerCase();
    if (!DEFAULT_WEB_SOURCE_PRIORITY.includes(nextSource)) return;

    setWebSourcePriority((prev) => {
      const current = normalizeWebSourcePriority(prev);
      const targetIndex = Math.max(0, Math.min(position, current.length - 1));
      const existingIndex = current.indexOf(nextSource);

      if (existingIndex === targetIndex) return current;

      if (existingIndex >= 0) {
        const swap = current[targetIndex];
        current[targetIndex] = nextSource;
        current[existingIndex] = swap;
      } else {
        current[targetIndex] = nextSource;
      }

      return normalizeWebSourcePriority(current);
    });
  }

  function resetWebSourcePriority() {
    setWebSourcePriority([...DEFAULT_WEB_SOURCE_PRIORITY]);
  }

  async function findWebSolutionsForFinding(item) {
    const key = findingFingerprint(item);
    setWebSolutionBusyByFinding((prev) => ({ ...prev, [key]: true }));

    try {
      const response = await fetch("/api/web-solutions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          limit: WEB_SOLUTION_LIMIT,
          sourcePriority: normalizeWebSourcePriority(webSourcePriority),
          finding: {
            title: item.title,
            categoryLabel: item.categoryLabel,
            sourceName: item.sourceName,
            resolution: item.resolution,
            evidence: item.evidence?.slice(0, 10),
          },
        }),
      });

      if (!response.ok) {
        throw new Error("Web solution search failed.");
      }

      const payload = await response.json();
      setWebSolutionsByFinding((prev) => ({ ...prev, [key]: payload }));
    } catch (e) {
      setWebSolutionsByFinding((prev) => ({
        ...prev,
        [key]: {
          query: item.title,
          warning: e instanceof Error ? e.message : "Web search failed.",
          solutions: [],
        },
      }));
    } finally {
      setWebSolutionBusyByFinding((prev) => ({ ...prev, [key]: false }));
    }
  }

  function markAsProblem(item) {
    const fingerprint = findingFingerprint(item);
    const keyword = extractKeywordFromEvidence(item.evidence);

    setReviewRecords((prev) => ({
      ...prev,
      [fingerprint]: {
        title: item.title,
        status: "problem",
        updatedAt: new Date().toISOString(),
      },
    }));

    setLearnedScenarios((prev) => {
      if (prev.some((entry) => entry.fingerprint === fingerprint)) return prev;
      return [
        {
          fingerprint,
          title: item.title,
          severity: item.severity,
          keyword,
          resolution: item.resolution,
          createdAt: new Date().toISOString(),
        },
        ...prev,
      ].slice(0, 500);
    });

    pushFeedbackHistory({ action: "mark_problem", fingerprint, title: item.title });
  }

  function markAsNotProblem(item) {
    const fingerprint = findingFingerprint(item);

    setReviewRecords((prev) => ({
      ...prev,
      [fingerprint]: {
        title: item.title,
        status: "not_problem",
        updatedAt: new Date().toISOString(),
      },
    }));
    pushFeedbackHistory({ action: "mark_not_problem", fingerprint, title: item.title });

    const shouldExclude = window.confirm(
      "Do you want to exclude this finding permanently? It will not appear again."
    );
    if (!shouldExclude) return;

    setExcludedFingerprints((prev) => {
      const next = new Set(prev);
      next.add(fingerprint);
      return next;
    });
    setExcludedRecords((prev) => ({
      ...prev,
      [fingerprint]: {
        title: item.title,
        sourceName: item.sourceName || "",
        updatedAt: new Date().toISOString(),
      },
    }));
    pushFeedbackHistory({ action: "exclude_permanent", fingerprint, title: item.title });
  }

  function removeExcludedFingerprints(fingerprints) {
    const unique = [...new Set((fingerprints || []).filter((item) => item && item !== "__all__"))];
    if (!unique.length) return;

    const removalEvents = unique.map((fingerprint) => ({
      fingerprint,
      title:
        excludedRecords[fingerprint]?.title ||
        reviewRecords[fingerprint]?.title ||
        "Excluded finding",
    }));

    setExcludedFingerprints((prev) => {
      const next = new Set(prev);
      unique.forEach((fingerprint) => next.delete(fingerprint));
      return next;
    });
    setExcludedRecords((prev) => {
      const next = { ...prev };
      unique.forEach((fingerprint) => {
        delete next[fingerprint];
      });
      return next;
    });

    removalEvents.forEach((event) => {
      pushFeedbackHistory({
        action: "exclude_removed",
        fingerprint: event.fingerprint,
        title: event.title,
      });
    });
  }

  async function onAnalyze() {
    if (parsedPaths.length) {
      setPathHistory((prev) => mergePathHistory(prev, parsedPaths));
    }

    if (!logs.trim()) {
      setError("Add logs first.");
      return;
    }

    setBusy(true);
    setError("");
    setAiResult(null);

    try {
      await analyzeLogs(logs, true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Analysis failed.");
    } finally {
      setBusy(false);
    }
  }

  async function onFilesUpload(event) {
    const files = Array.from(event.target.files || []);
    if (!files.length) return;

    if (files.length > MAX_FILES) {
      setError(`Maximum ${MAX_FILES} files are allowed. First ${MAX_FILES} were used.`);
    } else {
      setError("");
    }

    const selected = files.slice(0, MAX_FILES);
    const contents = await Promise.all(selected.map((file) => readFileAsText(file)));
    const nextLinks = {};
    selected.forEach((file) => {
      nextLinks[file.name] = URL.createObjectURL(file);
    });
    const merged = selected
      .map((file, index) => `--- FILE: ${file.name} ---\n${contents[index]}`)
      .join("\n\n");

    setUploadedFileLinks(nextLinks);
    setLogs(merged);
    setAutoRead(false);
  }

  return (
    <main className="log-analyzer">
      <section className="hero">
        <h1>AI Log Problem Analyzer</h1>
        <p>Read up to 30 files, detect problems, and learn from your feedback.</p>
      </section>

      <section className="card">
        <div className="stats-row">
          <article className="stat-card">
            <span>Detected problems</span>
            <strong>{findings.length}</strong>
          </article>
          <article className="stat-card">
            <span>Configured paths</span>
            <strong>{parsedPaths.length}</strong>
          </article>
          <button
            type="button"
            className={`stat-card stat-card-button ${showExclusionManager ? "is-active" : ""}`}
            onClick={() => setShowExclusionManager((prev) => !prev)}
          >
            <span>Permanent exclusions</span>
            <strong>{excludedFingerprints.size}</strong>
          </button>
        </div>

        <div className="filters top-filters">
          <div className="filter-field">
            <label htmlFor="problem-type">Problem type</label>
            <select
              id="problem-type"
              value={problemType}
              onChange={(event) => setProblemType(event.target.value)}
            >
              <option value="all">All problems</option>
              {problemTypeOptions.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-field">
            <label htmlFor="date-from">Date from</label>
            <input
              id="date-from"
              type="date"
              value={dateFrom}
              onChange={(event) => setDateFrom(event.target.value)}
            />
          </div>
          <div className="filter-field">
            <label htmlFor="date-to">Date to</label>
            <input
              id="date-to"
              type="date"
              value={dateTo}
              onChange={(event) => setDateTo(event.target.value)}
            />
          </div>
          <div className="filter-field">
            <label htmlFor="time-from">Time from</label>
            <input
              id="time-from"
              type="time"
              value={timeFrom}
              onChange={(event) => setTimeFrom(event.target.value)}
            />
          </div>
          <div className="filter-field">
            <label htmlFor="time-to">Time to</label>
            <input
              id="time-to"
              type="time"
              value={timeTo}
              onChange={(event) => setTimeTo(event.target.value)}
            />
          </div>
        </div>

        <section className="web-source-priority-panel">
          <div className="web-source-priority-header">
            <div>
              <h3>Web Resolution Source Priority</h3>
              <p className="muted">Applied live when you click "Find Web Solutions".</p>
            </div>
            <button
              type="button"
              className="action-button action-ghost"
              onClick={resetWebSourcePriority}
            >
              Reset order
            </button>
          </div>
          <div className="web-source-priority-grid">
            {normalizeWebSourcePriority(webSourcePriority).map((sourceId, index) => (
              <div key={`priority-${index}`} className="filter-field">
                <label htmlFor={`source-priority-${index}`}>Priority {index + 1}</label>
                <select
                  id={`source-priority-${index}`}
                  value={sourceId}
                  onChange={(event) => updateWebSourcePriorityAt(index, event.target.value)}
                >
                  {WEB_SOURCE_OPTIONS.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            ))}
          </div>
          <p className="muted source-priority-summary">Current order: {webSourceOrderLabel}</p>
        </section>

        <div className="meta-row">
          <small>Source mode: {autoRead ? "continuous read" : "manual / upload"}</small>
        </div>
        {showExclusionManager ? (
          <>
            <div className="clear-exclusion-row">
              <div className="filter-field clear-exclusion-picker">
                <label>Clear exclusion</label>
                <div className="multi-select-dropdown" ref={exclusionDropdownRef}>
                  <button
                    type="button"
                    className={`multi-select-trigger ${isExclusionDropdownOpen ? "is-open" : ""}`}
                    onClick={() => setIsExclusionDropdownOpen((prev) => !prev)}
                    disabled={!exclusionList.length}
                  >
                    <span className="multi-select-trigger-text">{clearExclusionSummaryLabel}</span>
                    {clearExclusionTargets.length ? (
                      <span className="multi-select-count">
                        {selectedClearAll ? "ALL" : clearExclusionTargets.length}
                      </span>
                    ) : null}
                  </button>
                  {isExclusionDropdownOpen ? (
                    <div className="multi-select-menu">
                      <div className="multi-select-menu-header">
                        <button
                          type="button"
                          className="multi-select-mini"
                          onClick={() => setClearExclusionTargets(["__all__"])}
                          disabled={!exclusionList.length}
                        >
                          Select all
                        </button>
                        <button
                          type="button"
                          className="multi-select-mini"
                          onClick={() => setClearExclusionTargets([])}
                        >
                          Clear
                        </button>
                      </div>
                      <label className="multi-select-option">
                        <input
                          type="checkbox"
                          checked={selectedClearAll}
                          onChange={() => toggleClearExclusionTarget("__all__")}
                          disabled={!exclusionList.length}
                        />
                        <span>All exclusions</span>
                      </label>
                      {exclusionList.length ? (
                        exclusionList.map((item) => (
                          <label key={item.fingerprint} className="multi-select-option">
                            <input
                              type="checkbox"
                              checked={!selectedClearAll && clearExclusionTargets.includes(item.fingerprint)}
                              onChange={() => toggleClearExclusionTarget(item.fingerprint)}
                            />
                            <span>{item.sourceName ? `${item.title} [${item.sourceName}]` : item.title}</span>
                          </label>
                        ))
                      ) : (
                        <p className="multi-select-empty">No exclusions available.</p>
                      )}
                    </div>
                  ) : null}
                </div>
              </div>
              <div className="filter-field clear-exclusion-action">
                <label htmlFor="clear-exclusion-apply" className="clear-exclusion-action-label">
                  Action
                </label>
                <button
                  id="clear-exclusion-apply"
                  type="button"
                  className="action-button action-ghost"
                  onClick={applyClearExclusionSelection}
                  disabled={!clearExclusionTargets.length}
                >
                  Apply
                </button>
              </div>
            </div>
            {clearExclusionTargets.length ? (
              <section className="excluded-panel">
                {selectedClearAll ? (
                  <p className="muted">
                    All exclusions selected ({exclusionList.length}). Click <strong>Apply</strong> to clear all.
                  </p>
                ) : selectedExclusions.length ? (
                  <div className="excluded-list">
                    {selectedExclusions.map((item) => (
                      <article key={item.fingerprint} className="excluded-item">
                        <div className="excluded-item-body">
                          <p className="excluded-item-title">{item.title}</p>
                          <code title={item.fingerprint}>{item.fingerprint}</code>
                          {item.sourceName ? <small className="muted">Source: {item.sourceName}</small> : null}
                          {item.updatedAt ? (
                            <small className="muted">
                              Excluded at: {new Date(item.updatedAt).toLocaleString()}
                            </small>
                          ) : null}
                        </div>
                      </article>
                    ))}
                  </div>
                ) : (
                  <p className="muted">No matching exclusions selected.</p>
                )}
              </section>
            ) : null}
          </>
        ) : null}

        <label htmlFor="path-list">Continuous read paths (up to 30, one per line)</label>
        <textarea
          id="path-list"
          className="path-input"
          placeholder={
            "C:\\logs\\app.log\n/var/log/app.log\ns3://my-bucket/app/SystemOut.log\ngs://my-bucket/app/logs/\naz://myaccount/mycontainer/path/"
          }
          value={pathListInput}
          onChange={(event) => setPathListInput(event.target.value)}
        />
        <div className="path-suggest-row">
          <div className="filter-field search-input">
            <label htmlFor="path-suggestion">Saved path suggestions</label>
            <input
              id="path-suggestion"
              type="text"
              list="saved-paths"
              placeholder="Select or type a path..."
              value={pathSuggestionInput}
              onChange={(event) => setPathSuggestionInput(event.target.value)}
            />
            <datalist id="saved-paths">
              {pathHistory.map((path) => (
                <option key={path} value={path} />
              ))}
            </datalist>
          </div>
          <button
            type="button"
            className="action-button action-ghost"
            onClick={() => addPathToInput(pathSuggestionInput)}
          >
            Add path
          </button>
        </div>
        {pathSuggestions.length ? (
          <div className="path-suggestions">
            {pathSuggestions.slice(0, PATH_HISTORY_LIMIT).map((path) => (
              <button
                key={path}
                type="button"
                className="path-chip"
                onClick={() => addPathToInput(path)}
                title={path}
              >
                {sourceNameFromPath(path)}
              </button>
            ))}
          </div>
        ) : null}
        <div className="path-history-row">
          <small>Saved path history: {pathHistory.length} (max {PATH_HISTORY_LIMIT}).</small>
          <button
            type="button"
            className="action-button action-danger"
            onClick={clearSavedPaths}
            disabled={!pathHistory.length}
          >
            Clear saved paths
          </button>
        </div>
        <div className="watch-controls">
          <div className="filter-field">
            <label htmlFor="poll-seconds">Refresh every (seconds)</label>
            <input
              id="poll-seconds"
              type="number"
              min="1"
              max="3600"
              value={pollSeconds}
              onChange={(event) => setPollSeconds(event.target.value)}
            />
          </div>
          <button
            type="button"
            className={`action-button action-toggle ${autoRead ? "is-active" : ""}`}
            onClick={() => setAutoRead((prev) => !prev)}
          >
            {autoRead ? "Auto Read: On" : "Auto Read: Off"}
          </button>
          {lastReadAt ? <small>Last read: {lastReadAt}</small> : null}
        </div>
        <small>
          Continuous read requires `VITE_LOG_WATCH_ENDPOINT` (default `/api/logs`) and supports
          `?path=...` for local, `s3://`, `gs://`, and `az://` paths.
        </small>

        <label htmlFor="log-file">Upload logs (up to 30 files)</label>
        <input
          id="log-file"
          type="file"
          multiple
          accept=".log,.txt,.json"
          onChange={onFilesUpload}
        />

        <label htmlFor="log-input">Or paste merged log content</label>
        <textarea
          id="log-input"
          placeholder="Paste logs here..."
          value={logs}
          onChange={(event) => {
            setUploadedFileLinks({});
            setLogs(event.target.value);
          }}
        />

        <div className="actions">
          <button
            type="button"
            className="action-button action-primary"
            disabled={busy}
            onClick={onAnalyze}
          >
            {busy ? "Analyzing..." : "Analyze Logs"}
          </button>
          {!hasConfig ? (
            <small>
              AI optional: set `VITE_LOG_ANALYZER_ENDPOINT` or `VITE_OPENAI_API_KEY` in `.env`.
            </small>
          ) : null}
        </div>

        <section className="search-panel">
          <div className="search-row">
            <div className="filter-field search-input">
              <label htmlFor="search-text">Search string in all loaded files</label>
              <input
                id="search-text"
                type="text"
                placeholder="Example: NullPointerException"
                value={searchText}
                onChange={(event) => setSearchText(event.target.value)}
              />
            </div>
            <label className="watch-toggle" htmlFor="search-case-sensitive">
              <input
                id="search-case-sensitive"
                type="checkbox"
                checked={searchCaseSensitive}
                onChange={(event) => setSearchCaseSensitive(event.target.checked)}
              />
              Case sensitive
            </label>
          </div>
          {!trimmedSearchText ? (
            <small>Type a query to search across all currently loaded files.</small>
          ) : (
            <small>
              Matches: {searchResults.length}
              {searchResults.length >= SEARCH_MAX_RESULTS
                ? ` (showing first ${SEARCH_MAX_RESULTS})`
                : ""}
            </small>
          )}
          {trimmedSearchText && searchSummaryBySource.length ? (
            <div className="search-summary">
              {searchSummaryBySource.map(({ sourcePath, count }) => (
                <span key={sourcePath} className="search-chip" title={sourcePath}>
                  {displayCompactPath(sourcePath)}: {count}
                </span>
              ))}
            </div>
          ) : null}
          {trimmedSearchText && searchResults.length ? (
            <div className="search-results">
              {searchResults.map((result, index) => (
                <article
                  key={`${displaySourcePath(result.sourcePath)}-${result.lineNumber}-${index}`}
                  className="search-result-item"
                >
                  <p className="search-result-meta">
                    <strong>{result.sourceName || "unknown"}</strong>
                    <code title={displaySourcePath(result.sourcePath)}>
                      {displayCompactPath(result.sourcePath)}:{result.lineNumber}
                    </code>
                    {buildLogViewUrl(result.sourcePath, uploadedFileLinks) ? (
                      <a
                        className="log-link"
                        href={buildLogViewUrl(result.sourcePath, uploadedFileLinks)}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Open log
                      </a>
                    ) : null}
                  </p>
                  <p className="search-result-line">{result.line}</p>
                </article>
              ))}
            </div>
          ) : null}
          {trimmedSearchText && !searchResults.length ? (
            <p className="muted">No matches found for "{trimmedSearchText}".</p>
          ) : null}
        </section>

        {error ? <p className="error">{error}</p> : null}
      </section>

      <section className="results">
        <h2>Detected Problems</h2>
        {!visibleFindings.length ? <p>No findings yet.</p> : null}
        {visibleFindings.map((item) => {
          const key = findingFingerprint(item);
          const review = reviewRecords[key];
          const webData = webSolutionsByFinding[key];
          const webBusy = Boolean(webSolutionBusyByFinding[key]);
          return (
            <article key={key} className="result-card">
              <header>
                <h3>{item.title}</h3>
                <span className={`severity severity-${item.severity}`}>{item.severity}</span>
              </header>
              <p className="source-path">
                <span className="source-name">{item.sourceName || "unknown"}</span>
                <code title={displaySourcePath(item.sourcePath)}>
                  {displayCompactPath(item.sourcePath)}
                </code>
                {buildLogViewUrl(item.sourcePath, uploadedFileLinks) ? (
                  <a
                    className="log-link"
                    href={buildLogViewUrl(item.sourcePath, uploadedFileLinks)}
                    target="_blank"
                    rel="noreferrer"
                  >
                    Open log
                  </a>
                ) : null}
              </p>
              <p>Occurrences: {item.count}</p>
              <p>Resolution: {item.resolution}</p>
              <div className="feedback-actions">
                <button
                  type="button"
                  className="action-button action-accent"
                  disabled={webBusy}
                  onClick={() => findWebSolutionsForFinding(item)}
                >
                  {webBusy ? "Searching Web..." : "Find Web Solutions"}
                </button>
                <button
                  type="button"
                  className="action-button action-ghost"
                  onClick={() => markAsProblem(item)}
                >
                  Mark Problem
                </button>
                <button
                  type="button"
                  className="action-button action-ghost"
                  onClick={() => markAsNotProblem(item)}
                >
                  Mark Not Problem
                </button>
                {review ? <small className="muted">Marked: {review.status}</small> : null}
              </div>
              {webData ? (
                <div className="web-solution-panel">
                  <p className="web-query">
                    <strong>Web query:</strong> {webData.query || item.title}
                  </p>
                  {webData.warning ? <p className="muted">{webData.warning}</p> : null}
                  {Array.isArray(webData.solutions) && webData.solutions.length ? (
                    <div className="web-solution-list">
                      {webData.solutions.map((solution, index) => (
                        <article
                          key={`${key}-web-${index}`}
                          className="web-solution-item"
                        >
                          <p className="web-solution-title">
                            <strong>{solution.title || `Solution ${index + 1}`}</strong>
                            {solution.source ? (
                              <span className="muted"> ({solution.source})</span>
                            ) : null}
                          </p>
                          <p className="web-solution-text">{solution.solution}</p>
                          {solution.url ? (
                            <a
                              className="log-link"
                              href={solution.url}
                              target="_blank"
                              rel="noreferrer"
                            >
                              View source
                            </a>
                          ) : null}
                        </article>
                      ))}
                    </div>
                  ) : (
                    <p className="muted">No web solutions available for this issue yet.</p>
                  )}
                </div>
              ) : null}
              <div className="evidence-list">
                {item.evidence.map((line, index) => (
                  <p key={`${key}-${index}`} className="evidence-line">
                    {line}
                  </p>
                ))}
              </div>
            </article>
          );
        })}
      </section>

      {aiResult ? (
        <section className="results ai">
          <h2>AI Resolution Guidance</h2>
          <p>{aiResult.summary || "No summary returned."}</p>
          {Array.isArray(aiResult.top_causes)
            ? aiResult.top_causes.map((cause, index) => (
                <article key={`${cause.cause}-${index}`} className="result-card">
                  <header>
                    <h3>{cause.cause}</h3>
                    <span className="severity">{cause.confidence || "unknown"}</span>
                  </header>
                  <p>{cause.fix}</p>
                </article>
              ))
            : null}
        </section>
      ) : null}
    </main>
  );
}
