import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./log-analyzer.css";

const MAX_FILES = 30;
const SEARCH_MAX_RESULTS = 500;
const PATH_HISTORY_LIMIT = 30;
const WEB_SOLUTION_LIMIT = 5;
const WEB_SOURCE_OPTIONS = [
  { id: "localai", label: "Local AI Engine" },
  { id: "google", label: "Google Links" },
  { id: "gemini", label: "Gemini (Free Tier)" },
  { id: "huggingface", label: "Hugging Face" },
  { id: "groq", label: "Groq (Free Tier)" },
  { id: "openrouter", label: "OpenRouter Free" },
  { id: "stackoverflow", label: "Stack Overflow" },
  { id: "github", label: "GitHub Issues" },
  { id: "chatgpt", label: "ChatGPT Web (Paid)" },
  { id: "local", label: "Local Fallback" },
];
const DEFAULT_WEB_SOURCE_PRIORITY = WEB_SOURCE_OPTIONS.map((item) => item.id);
const STORAGE_KEYS = {
  excluded: "log_analyzer_excluded_fingerprints",
  excludedRecords: "log_analyzer_excluded_records",
  reviews: "log_analyzer_review_records",
  learned: "log_analyzer_learned_scenarios",
  history: "log_analyzer_feedback_history",
  pathHistory: "log_analyzer_path_history",
  pathAliases: "log_analyzer_path_aliases",
  searchPreferences: "log_analyzer_search_preferences",
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

const ISSUE_CLASSIFICATION_BUCKETS = [
  {
    id: "db2-jdbc-connectivity",
    label: "DB2 / JDBC connectivity",
    severity: "high",
    resolution:
      "Validate DB2 host/port and credentials, confirm driver compatibility, and review pool/socket timeout settings.",
    signals: [
      { regex: /sqlstate\s*=?\s*08001/i, label: "SQLSTATE 08001" },
      { regex: /communications link failure/i, label: "Communications link failure" },
      { regex: /com\.ibm\.db2\.jcc/i, label: "DB2 JDBC driver stack" },
      { regex: /sql30081n|sql1224n/i, label: "DB2 network/connectivity SQL code" },
      { regex: /jdbc.*(connect|connection|timeout|refused|authentication)/i, label: "JDBC connect/auth failure" },
      { regex: /db2.*(connect|connection|timeout|refused|authentication)/i, label: "DB2 connect/auth failure" },
      {
        regex: /could not connect to server|connection refused|connection reset|login timeout/i,
        label: "Connection failure",
      },
    ],
  },
  {
    id: "db2-sql-syntax-query",
    label: "DB2 SQL syntax / query errors",
    severity: "medium",
    resolution:
      "Check generated SQL and bind variables, fix syntax/token errors, and verify schema/object names against target DB2 version.",
    signals: [
      { regex: /sqlcode\s*=?\s*-104/i, label: "SQLCODE -104" },
      { regex: /sqlcode\s*=?\s*-727/i, label: "SQLCODE -727" },
      { regex: /sqlstate\s*=?\s*42601/i, label: "SQLSTATE 42601" },
      { regex: /sql syntax|syntax error|unexpected token/i, label: "SQL syntax/token error" },
      { regex: /db2 sql error|sql error/i, label: "DB2 SQL error" },
    ],
  },
  {
    id: "jvm-memory",
    label: "JVM memory",
    severity: "high",
    resolution:
      "Review heap usage and allocation rate, tune JVM memory settings, and investigate leaks or unbounded object retention.",
    signals: [
      { regex: /outofmemoryerror|out of memory|java heap space/i, label: "Heap OOM" },
      { regex: /gc overhead limit exceeded|gc thrash|full gc/i, label: "GC pressure" },
      { regex: /metaspace|direct buffer memory/i, label: "Metaspace/direct memory pressure" },
      { regex: /unable to create new native thread/i, label: "Native thread/resource exhaustion" },
    ],
  },
  {
    id: "threads-deadlock-hung",
    label: "Threads / deadlock / hung requests",
    severity: "high",
    resolution:
      "Inspect thread dumps, identify lock contention and blocking I/O, and add timeout/circuit-breaker protections on stuck paths.",
    signals: [
      { regex: /deadlock|found one java-level deadlock/i, label: "Deadlock detected" },
      { regex: /stuck thread|hung thread|thread hung/i, label: "Stuck/hung thread" },
      { regex: /servlet.*timeout|request.*timeout/i, label: "Servlet/request timeout" },
      { regex: /blocked for \d+ ms|waiting to lock/i, label: "Lock/block wait" },
    ],
  },
  {
    id: "ssl-tls-certs",
    label: "SSL/TLS / certs",
    severity: "high",
    resolution:
      "Validate truststore/keystore contents, certificate chain and expiry, and align TLS protocol/cipher configuration between peers.",
    signals: [
      { regex: /sslhandshakeexception|tls handshake/i, label: "Handshake failure" },
      {
        regex: /pkix path building failed|unable to find valid certification path/i,
        label: "Trust chain validation failure",
      },
      { regex: /certificate expired|unable to parse certificate/i, label: "Certificate validity issue" },
      { regex: /truststore|keystore|sun\.security\.validator/i, label: "Truststore/keystore issue" },
    ],
  },
  {
    id: "search-solr-indexing",
    label: "Search (Solr) / indexing",
    severity: "medium",
    resolution:
      "Check Solr core/collection health, replica status, and indexing pipeline consistency; then replay failed indexing work.",
    signals: [
      { regex: /\bsolr\b|solrserverexception/i, label: "Solr error" },
      { regex: /index(?:ing)? failed|index out of bounds|index corruption/i, label: "Indexing failure" },
      {
        regex: /core.*unavailable|collection.*down|replica.*down/i,
        label: "Solr core/collection availability issue",
      },
      { regex: /search query failed|queryparser/i, label: "Search query failure" },
    ],
  },
  {
    id: "cache-session",
    label: "Cache/session (dynacache, session invalidation)",
    severity: "medium",
    resolution:
      "Review cache key lifecycle and invalidation strategy, and verify session affinity/replication behavior across nodes.",
    signals: [
      { regex: /dynacache|cache miss|cache invalidation|evict/i, label: "Cache lifecycle issue" },
      { regex: /session invalid|session expired|invalid session/i, label: "Session invalidation/expiry" },
      { regex: /session replication|sticky session|affinity/i, label: "Session affinity/replication issue" },
    ],
  },
  {
    id: "auth-security",
    label: "Auth/security (SSO, tokens, 401/403)",
    severity: "medium",
    resolution:
      "Validate SSO and token claims/expiry, verify role-to-resource mappings, and confirm authorization policy behavior end-to-end.",
    signals: [
      { regex: /\b401\b|\b403\b|unauthorized|forbidden/i, label: "401/403 authz failure" },
      { regex: /sso|oauth|openid|jwt|token expired|invalid token|signature/i, label: "SSO/token validation issue" },
      { regex: /access denied|permission denied|authorization failed/i, label: "Authorization policy failure" },
    ],
  },
  {
    id: "integration-outbound-timeout",
    label: "Integration / outbound timeout (payment, tax, OMS)",
    severity: "high",
    resolution:
      "Trace downstream dependency latency and retries, tune client timeouts, and protect callers with fallback/circuit-breaker behavior.",
    signals: [
      { regex: /payment|tax service|oms|order management/i, label: "Named downstream dependency" },
      { regex: /connect timed out|read timed out|sockettimeout/i, label: "Outbound timeout" },
      {
        regex: /httpclienterror|resourceaccessexception|upstream timeout|gateway timeout/i,
        label: "Outbound HTTP timeout/error",
      },
      { regex: /failed to call|downstream|remote service/i, label: "Dependency call failure" },
    ],
  },
  {
    id: "app-bug-runtime-config",
    label: "App bug / NullPointer / ClassNotFound / config",
    severity: "medium",
    resolution:
      "Fix null-handling and defensive guards, verify classpath/dependency alignment, and validate runtime configuration values.",
    signals: [
      { regex: /nullpointerexception|illegalstateexception|illegalargumentexception/i, label: "Runtime exception" },
      { regex: /classnotfoundexception|noclassdeffounderror|nosuchmethoderror/i, label: "Classpath/dependency mismatch" },
      {
        regex: /beancreationexception|failed to bind properties|configuration error|missing required property/i,
        label: "Application configuration failure",
      },
      { regex: /unhandled exception|traceback/i, label: "Unhandled app exception" },
    ],
  },
  {
    id: "filesystem-permissions-io",
    label: "File system / permissions / I/O",
    severity: "medium",
    resolution:
      "Validate file path correctness and access permissions, ensure mount/share availability, and check disk capacity and I/O errors.",
    signals: [
      { regex: /accessdeniedexception|permission denied|eacces/i, label: "Permission denied" },
      { regex: /nosuchfileexception|file not found|path not found/i, label: "Missing path/file" },
      { regex: /disk full|no space left on device|i\/o error|input\/output error/i, label: "Disk/I-O failure" },
      { regex: /failed reading all configured paths|failed to read/i, label: "Path read failure" },
    ],
  },
  {
    id: "network-dns-routing",
    label: "Network / DNS / routing",
    severity: "high",
    resolution:
      "Verify DNS resolution, route/firewall rules, and service endpoint reachability from the application host network.",
    signals: [
      { regex: /unknownhostexception|name or service not known|dns/i, label: "DNS resolution issue" },
      { regex: /no route to host|network is unreachable|host unreachable/i, label: "Routing/reachability issue" },
      { regex: /connection reset by peer|broken pipe/i, label: "Network connection drop" },
    ],
  },
];

const DEFAULT_ISSUE_CLASSIFICATION = {
  id: "unclassified-runtime",
  label: "Unclassified runtime issue",
  severity: "low",
  resolution:
    "Capture a wider log window around first failure and include correlated upstream/downstream request IDs for reclassification.",
  matchedSignals: [],
};

const ISSUE_CLASSIFICATION_ORDER = new Map(
  ISSUE_CLASSIFICATION_BUCKETS.map((bucket, index) => [bucket.id, index])
);
const AGENT_ACTIVE_STATUSES = new Set([
  "QUEUED",
  "PLANNING",
  "RUNNING",
  "AWAITING_APPROVAL",
  "VERIFYING",
]);
const DEFAULT_AGENT_CAPABILITIES = {
  privilegedActionsEnabled: false,
  confirmationPhrase: "",
  timeoutSeconds: 0,
  actions: {
    restart_server: false,
    deploy: false,
    code_change: false,
  },
  policyNotice: "Privileged action mode is disabled in backend configuration.",
};

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

function formatRelativeTime(timestampMs) {
  if (!timestampMs || Number.isNaN(Number(timestampMs))) return "No reads yet";
  const diffMs = Math.max(0, Date.now() - Number(timestampMs));
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return "just now";
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin} minute${diffMin === 1 ? "" : "s"} ago`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours} hour${diffHours === 1 ? "" : "s"} ago`;
  const diffDays = Math.floor(diffHours / 24);
  return `${diffDays} day${diffDays === 1 ? "" : "s"} ago`;
}

async function extractApiErrorMessage(response, fallbackMessage) {
  let message = fallbackMessage;
  try {
    const payload = await response.json();
    if (typeof payload?.error === "string" && payload.error.trim()) {
      message = payload.error.trim();
    }
  } catch {
    // ignore parse errors and use fallback
  }
  return message;
}

function isAgentRunActive(status) {
  const normalized = String(status || "").trim().toUpperCase();
  return AGENT_ACTIVE_STATUSES.has(normalized);
}

function normalizeAgentStringList(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => String(item || "").trim())
    .filter(Boolean);
}

function normalizeAgentObjectList(value) {
  if (!Array.isArray(value)) return [];
  return value.filter((item) => item && typeof item === "object");
}

function buildAgentStepDetailModel(step) {
  if (!step || !step.output || typeof step.output !== "object") return null;
  const output = step.output;
  const primaryCategory = String(output.primaryCategory || "").trim();
  const targetHost = String(output.targetHost || "").trim();
  const evidenceSummary = String(output.evidenceSummary || "").trim();
  const note = String(output.note || "").trim();
  const decision = String(output.decision || "").trim();
  const decisionNote = String(output.decisionNote || output.note || "").trim();
  const approvalChecklist = normalizeAgentStringList(output.approvalChecklist);
  const blockedActions = normalizeAgentStringList(output.blockedActions);
  const unreadablePathHints = normalizeAgentStringList(output.unreadablePathHints);
  const executedActions = normalizeAgentObjectList(output.executedActions).map((action, index) => {
    const actionType = String(action.actionType || "").trim() || `action_${index + 1}`;
    return {
      key: `${actionType}-${index}`,
      actionLabel: String(action.actionLabel || actionType).trim(),
      success: Boolean(action.success),
      note: String(action.note || "").trim(),
      timestamp: String(action.timestamp || "").trim(),
      outputSnippet: String(action.outputSnippet || "").trim(),
      error: String(action.error || "").trim(),
      exitCode: Number.isFinite(Number(action.exitCode)) ? Number(action.exitCode) : null,
    };
  });
  const options = normalizeAgentObjectList(output.options).map((option, index) => {
    const title = String(option.title || "").trim() || `Option ${index + 1}`;
    return {
      key: String(option.id || "").trim() || `${title}-${index}`,
      title,
      risk: String(option.risk || "").trim() || "SAFE",
      why: String(option.why || "").trim(),
      rollback: String(option.rollback || "").trim(),
      requiresApproval: Boolean(option.requiresApproval),
      actions: normalizeAgentStringList(option.actions),
      successSignals: normalizeAgentStringList(option.successSignals),
    };
  });
  const hasDetail =
    Boolean(primaryCategory) ||
    Boolean(targetHost) ||
    Boolean(evidenceSummary) ||
    Boolean(note) ||
    Boolean(decision) ||
    options.length > 0 ||
    approvalChecklist.length > 0 ||
    blockedActions.length > 0 ||
    unreadablePathHints.length > 0 ||
    executedActions.length > 0;
  if (!hasDetail) return null;
  return {
    primaryCategory,
    targetHost,
    evidenceSummary,
    note,
    decision,
    decisionNote,
    approvalChecklist,
    blockedActions,
    unreadablePathHints,
    executedActions,
    options,
  };
}

function normalizeAgentCapabilities(payload) {
  const actionsPayload = payload && typeof payload === "object" && payload.actions ? payload.actions : {};
  return {
    privilegedActionsEnabled: Boolean(payload?.privilegedActionsEnabled),
    confirmationPhrase: String(payload?.confirmationPhrase || ""),
    timeoutSeconds: Number(payload?.timeoutSeconds || 0),
    actions: {
      restart_server: Boolean(actionsPayload?.restart_server),
      deploy: Boolean(actionsPayload?.deploy),
      code_change: Boolean(actionsPayload?.code_change),
    },
    policyNotice: String(payload?.policyNotice || DEFAULT_AGENT_CAPABILITIES.policyNotice),
  };
}

function normalizeBooleanFlag(value) {
  if (typeof value === "boolean") return value;
  const normalized = String(value ?? "")
    .trim()
    .toLowerCase();
  return normalized === "true" || normalized === "1" || normalized === "yes";
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

function isSamePathValue(left, right) {
  const leftValue = normalizePathInput(left).toLowerCase();
  const rightValue = normalizePathInput(right).toLowerCase();
  return Boolean(leftValue) && leftValue === rightValue;
}

function normalizePathAlias(rawAlias) {
  return String(rawAlias || "").trim();
}

function normalizePathForCompare(rawPath) {
  return normalizePathInput(rawPath).replace(/\//g, "\\").toLowerCase();
}

function isRootedOrCloudPath(rawPath) {
  const normalized = normalizePathInput(rawPath);
  if (!normalized) return false;
  if (normalized.startsWith("\\\\")) return true;
  if (normalized.startsWith("/")) return true;
  if (/^[A-Za-z]:[\\/]/.test(normalized)) return true;
  return /^(s3|gs|az|https?):\/\//i.test(normalized);
}

function resolvePathAgainstCatalog(rawValue, catalogPaths, aliasByPathKey = {}) {
  const normalized = normalizePathInput(rawValue);
  if (!normalized) return "";
  const catalog = Array.isArray(catalogPaths) ? catalogPaths : [];

  const aliasLookup = normalized.toLowerCase();
  const aliasMatchKey = Object.keys(aliasByPathKey || {}).find(
    (pathKey) => String(aliasByPathKey[pathKey] || "").toLowerCase() === aliasLookup
  );
  if (aliasMatchKey) {
    const aliasedPath = catalog.find((item) => normalizePathInput(item).toLowerCase() === aliasMatchKey);
    if (aliasedPath) return aliasedPath;
  }

  const target = normalizePathForCompare(normalized);
  const targetWithBoundary = target.startsWith("\\") ? target : `\\${target}`;
  const suffixMatches = catalog.filter((item) => {
    const candidate = normalizePathForCompare(item);
    return candidate === target || candidate.endsWith(targetWithBoundary);
  });

  const rootedSuffixMatches = suffixMatches.filter((item) => isRootedOrCloudPath(item));
  if (rootedSuffixMatches.length === 1) {
    return rootedSuffixMatches[0];
  }
  if (suffixMatches.length === 1) {
    return suffixMatches[0];
  }

  const exactPath = catalog.find((item) => isSamePathValue(item, normalized));
  if (exactPath) {
    return exactPath;
  }

  return normalized;
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
  const safeExisting = Array.isArray(existingPaths) ? existingPaths : [];
  const safeIncoming = Array.isArray(incomingPaths) ? incomingPaths : [];
  const merged = [];
  const seen = new Set();
  const source = [...safeIncoming, ...safeExisting];

  for (const item of source) {
    const pathValue = normalizePathInput(item);
    if (!pathValue) continue;
    const dedupeKey = pathValue.toLowerCase();
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    merged.push(pathValue);
    if (merged.length >= limit) break;
  }

  const isUnchanged =
    merged.length === safeExisting.length &&
    merged.every((item, index) => isSamePathValue(item, safeExisting[index]));
  if (isUnchanged) {
    return safeExisting;
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

function normalizeBoolPreference(value, fallback = false) {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") {
    const normalized = value.trim().toLowerCase();
    if (normalized === "true") return true;
    if (normalized === "false") return false;
  }
  return fallback;
}

function normalizePollSecondsPreference(value) {
  const parsed = Number.parseInt(String(value ?? "30"), 10);
  if (Number.isNaN(parsed)) return "30";
  return String(Math.min(Math.max(parsed, 1), 3600));
}

function normalizeSearchPreferences(input) {
  const source = input && typeof input === "object" ? input : {};
  return {
    dateFrom: String(source.dateFrom || "").trim().slice(0, 16),
    dateTo: String(source.dateTo || "").trim().slice(0, 16),
    timeFrom: String(source.timeFrom || "").trim().slice(0, 16),
    timeTo: String(source.timeTo || "").trim().slice(0, 16),
    problemType: String(source.problemType || "all").trim() || "all",
    searchCaseSensitive: normalizeBoolPreference(source.searchCaseSensitive, false),
    autoRead: normalizeBoolPreference(source.autoRead, false),
    pollSeconds: normalizePollSecondsPreference(source.pollSeconds),
    webSourcePriority: normalizeWebSourcePriority(source.webSourcePriority),
  };
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

async function loadSearchPreferencesFromConfig() {
  try {
    const response = await fetch("/api/preferences", { method: "GET" });
    if (!response.ok) return null;
    const json = await response.json();
    if (!json || typeof json.preferences !== "object" || !json.preferences) return null;
    return json.preferences;
  } catch {
    return null;
  }
}

async function saveSearchPreferencesToConfig(preferences) {
  try {
    const response = await fetch("/api/preferences", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ preferences }),
    });
    if (!response.ok) return null;
    const json = await response.json();
    return json && typeof json.preferences === "object" ? json.preferences : null;
  } catch {
    return null;
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

function strongerSeverity(left = "low", right = "low") {
  return severityScore(left) >= severityScore(right) ? left : right;
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

function classifyFindingByTaxonomy(item) {
  const evidenceLines = Array.isArray(item.evidence) ? item.evidence : [];
  const context = [item.title, item.categoryLabel, item.sourceName, ...evidenceLines.slice(0, 12)]
    .filter(Boolean)
    .join("\n");

  let best = null;
  for (const bucket of ISSUE_CLASSIFICATION_BUCKETS) {
    const matchedSignals = [];
    let score = 0;
    for (const signal of bucket.signals) {
      if (signal.regex.test(context)) {
        score += 1;
        matchedSignals.push(signal.label);
      }
    }
    if (!score) continue;
    if (!best || score > best.score) {
      best = { bucket, score, matchedSignals };
    }
  }

  if (!best) {
    return { ...DEFAULT_ISSUE_CLASSIFICATION };
  }

  return {
    id: best.bucket.id,
    label: best.bucket.label,
    severity: best.bucket.severity,
    resolution: best.bucket.resolution,
    matchedSignals: best.matchedSignals.slice(0, 3),
  };
}

function findingTypeKey(item) {
  const classificationKey = item.classificationKey;
  if (classificationKey && classificationKey !== DEFAULT_ISSUE_CLASSIFICATION.id) {
    return classificationKey;
  }
  return item.categoryKey || item.id;
}

function findingTypeLabel(item) {
  const classificationKey = item.classificationKey;
  if (classificationKey && classificationKey !== DEFAULT_ISSUE_CLASSIFICATION.id) {
    return item.classificationLabel || item.categoryLabel || item.title;
  }
  return item.categoryLabel || item.title;
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
  const classified = dedupeFindings([...findings, ...learnedFindings]).map((item) => {
    const classification = classifyFindingByTaxonomy(item);
    return {
      ...item,
      classificationKey: classification.id,
      classificationLabel: classification.label,
      classificationSignals: classification.matchedSignals || [],
      severity: strongerSeverity(item.severity, classification.severity),
    };
  });

  return classified.sort((a, b) => severityScore(b.severity) - severityScore(a.severity));
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
  const initialSearchPreferencesRef = useRef(
    normalizeSearchPreferences(safeReadStorage(STORAGE_KEYS.searchPreferences, {}))
  );
  const initialSearchPreferences = initialSearchPreferencesRef.current;

  const [logs, setLogs] = useState("");
  const [findings, setFindings] = useState([]);
  const [aiResult, setAiResult] = useState(null);
  const [webSolutionsByFinding, setWebSolutionsByFinding] = useState({});
  const [webSolutionBusyByFinding, setWebSolutionBusyByFinding] = useState({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [dateFrom, setDateFrom] = useState(initialSearchPreferences.dateFrom);
  const [dateTo, setDateTo] = useState(initialSearchPreferences.dateTo);
  const [timeFrom, setTimeFrom] = useState(initialSearchPreferences.timeFrom);
  const [timeTo, setTimeTo] = useState(initialSearchPreferences.timeTo);
  const [problemType, setProblemType] = useState(initialSearchPreferences.problemType);
  const [agentMode, setAgentMode] = useState(false);
  const [agentGoal, setAgentGoal] = useState("");
  const [agentRun, setAgentRun] = useState(null);
  const [agentEvents, setAgentEvents] = useState([]);
  const [agentBusy, setAgentBusy] = useState(false);
  const [agentError, setAgentError] = useState("");
  const [agentDecisionBusyByStep, setAgentDecisionBusyByStep] = useState({});
  const [agentCapabilities, setAgentCapabilities] = useState(DEFAULT_AGENT_CAPABILITIES);
  const [agentActionMode, setAgentActionMode] = useState(false);
  const [agentActionConfirmation, setAgentActionConfirmation] = useState("");
  const [agentActionBusyType, setAgentActionBusyType] = useState("");
  const [agentActionFeedback, setAgentActionFeedback] = useState("");
  const [allowAgentRestart, setAllowAgentRestart] = useState(false);
  const [allowAgentDeploy, setAllowAgentDeploy] = useState(false);
  const [allowAgentCodeChange, setAllowAgentCodeChange] = useState(false);
  const [showTopFilters, setShowTopFilters] = useState(false);
  const [pathSuggestionInput, setPathSuggestionInput] = useState("");
  const [showSavedPathHistory, setShowSavedPathHistory] = useState(false);
  const [isLeftPanelVisible, setIsLeftPanelVisible] = useState(true);
  const [editingPathOriginal, setEditingPathOriginal] = useState("");
  const [editingPathValue, setEditingPathValue] = useState("");
  const [searchText, setSearchText] = useState("");
  const [searchCaseSensitive, setSearchCaseSensitive] = useState(
    initialSearchPreferences.searchCaseSensitive
  );
  const [pathListInput, setPathListInput] = useState("");
  const [autoRead, setAutoRead] = useState(initialSearchPreferences.autoRead);
  const [pollSeconds, setPollSeconds] = useState(initialSearchPreferences.pollSeconds);
  const [lastReadAt, setLastReadAt] = useState("");
  const [lastReadTime, setLastReadTime] = useState(null);
  const [selectedFindingKey, setSelectedFindingKey] = useState("");
  const [isProblemTypeDropdownOpen, setIsProblemTypeDropdownOpen] = useState(false);
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
  const [pathAliases, setPathAliases] = useState(() =>
    safeReadStorage(STORAGE_KEYS.pathAliases, {})
  );
  const [uploadedFileLinks, setUploadedFileLinks] = useState({});
  const [webSourcePriority, setWebSourcePriority] = useState(() =>
    normalizeWebSourcePriority(initialSearchPreferences.webSourcePriority)
  );
  const [preferencesReady, setPreferencesReady] = useState(false);
  const problemTypeDropdownRef = useRef(null);
  const exclusionDropdownRef = useRef(null);

  const hasConfig = useMemo(
    () =>
      Boolean(
        import.meta.env.VITE_LOG_ANALYZER_ENDPOINT || import.meta.env.VITE_OPENAI_API_KEY
      ),
    []
  );

  const normalizedPathAliases = useMemo(() => {
    const output = {};
    if (!pathAliases || typeof pathAliases !== "object") return output;
    for (const [rawPath, rawAlias] of Object.entries(pathAliases)) {
      const key = normalizePathInput(rawPath).toLowerCase();
      const alias = normalizePathAlias(rawAlias);
      if (!key || !alias) continue;
      output[key] = alias;
    }
    return output;
  }, [pathAliases]);

  function displayNameForSavedPath(path) {
    const key = normalizePathInput(path).toLowerCase();
    return normalizedPathAliases[key] || sourceNameFromPath(path);
  }

  function resolvePathFromSuggestion(rawValue) {
    return resolvePathAgainstCatalog(rawValue, pathHistory, normalizedPathAliases);
  }

  const parsedPaths = useMemo(() => {
    const unique = new Set(
      pathListInput
        .split(/\r?\n|,/)
        .map((item) => resolvePathFromSuggestion(item))
        .filter(Boolean)
    );
    return [...unique].slice(0, MAX_FILES);
  }, [pathListInput, pathHistory, normalizedPathAliases]);

  const pathSuggestions = useMemo(
    () => pathHistory.filter((path) => !parsedPaths.some((used) => used.toLowerCase() === path.toLowerCase())),
    [pathHistory, parsedPaths]
  );
  const envSourcePath = useMemo(() => normalizePathInput(parsedPaths[0] || pathHistory[0] || ""), [parsedPaths, pathHistory]);
  const envLabel = useMemo(() => {
    if (!envSourcePath) return "PREPROD";
    return envSourcePath.slice(0, 15);
  }, [envSourcePath]);

  const allEntries = useMemo(() => parseLogEntries(logs), [logs]);
  const trimmedSearchText = searchText.trim();
  const loadedFileCount = useMemo(
    () => new Set(allEntries.map((entry) => displaySourcePath(entry.sourcePath))).size,
    [allEntries]
  );

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
  const activeAgentRunId = String(agentRun?.id || "");
  const activeAgentSteps = useMemo(
    () => (Array.isArray(agentRun?.steps) ? agentRun.steps : []),
    [agentRun]
  );
  const recentAgentEvents = useMemo(
    () => (Array.isArray(agentEvents) ? [...agentEvents].slice(-40).reverse() : []),
    [agentEvents]
  );
  const agentConfirmationPhrase = agentCapabilities.confirmationPhrase || "";
  const isAgentConfirmationSatisfied =
    agentCapabilities.privilegedActionsEnabled &&
    agentConfirmationPhrase &&
    agentActionConfirmation.trim() === agentConfirmationPhrase;
  const activeRunConstraints =
    agentRun && typeof agentRun.constraints === "object" && agentRun.constraints ? agentRun.constraints : {};
  const activeRunAllowsRestart = normalizeBooleanFlag(activeRunConstraints.allow_start);
  const activeRunAllowsDeploy = normalizeBooleanFlag(activeRunConstraints.allow_deploy);
  const activeRunAllowsCodeChange = normalizeBooleanFlag(activeRunConstraints.allow_code_changes);

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
    safeWriteStorage(STORAGE_KEYS.pathAliases, normalizedPathAliases);
  }, [normalizedPathAliases]);

  useEffect(() => {
    setPathAliases((prev) => {
      if (!prev || typeof prev !== "object") return {};
      const availableKeys = new Set(pathHistory.map((path) => normalizePathInput(path).toLowerCase()));
      const next = {};
      let changed = false;
      for (const [rawKey, rawAlias] of Object.entries(prev)) {
        const key = normalizePathInput(rawKey).toLowerCase();
        const alias = normalizePathAlias(rawAlias);
        if (!key || !alias || !availableKeys.has(key)) {
          changed = true;
          continue;
        }
        next[key] = alias;
      }
      if (!changed && Object.keys(next).length === Object.keys(prev).length) {
        return prev;
      }
      return next;
    });
  }, [pathHistory]);

  useEffect(() => {
    const preferences = normalizeSearchPreferences({
      dateFrom,
      dateTo,
      timeFrom,
      timeTo,
      problemType,
      searchCaseSensitive,
      autoRead,
      pollSeconds,
      webSourcePriority,
    });
    safeWriteStorage(STORAGE_KEYS.searchPreferences, preferences);
    if (!preferencesReady) return;
    void saveSearchPreferencesToConfig(preferences);
  }, [
    preferencesReady,
    dateFrom,
    dateTo,
    timeFrom,
    timeTo,
    problemType,
    searchCaseSensitive,
    autoRead,
    pollSeconds,
    webSourcePriority,
  ]);

  useEffect(() => {
    if (!clearExclusionTargets.length) return;
    setClearExclusionTargets((prev) =>
      prev.filter((item) => item === "__all__" || excludedFingerprints.has(item))
    );
  }, [clearExclusionTargets, excludedFingerprints]);

  useEffect(() => {
    if (!isProblemTypeDropdownOpen) return;

    function onDocumentMouseDown(event) {
      if (!problemTypeDropdownRef.current) return;
      if (!problemTypeDropdownRef.current.contains(event.target)) {
        setIsProblemTypeDropdownOpen(false);
      }
    }

    function onDocumentKeyDown(event) {
      if (event.key === "Escape") {
        setIsProblemTypeDropdownOpen(false);
      }
    }

    document.addEventListener("mousedown", onDocumentMouseDown);
    document.addEventListener("keydown", onDocumentKeyDown);
    return () => {
      document.removeEventListener("mousedown", onDocumentMouseDown);
      document.removeEventListener("keydown", onDocumentKeyDown);
    };
  }, [isProblemTypeDropdownOpen]);

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
    let cancelled = false;
    void loadSearchPreferencesFromConfig().then((preferences) => {
      if (cancelled) return;
      const hasRemote =
        preferences && typeof preferences === "object" && Object.keys(preferences).length > 0;
      if (hasRemote) {
        const merged = normalizeSearchPreferences({
          ...initialSearchPreferencesRef.current,
          ...preferences,
        });
        setDateFrom(merged.dateFrom);
        setDateTo(merged.dateTo);
        setTimeFrom(merged.timeFrom);
        setTimeTo(merged.timeTo);
        setProblemType(merged.problemType);
        setSearchCaseSensitive(merged.searchCaseSensitive);
        setAutoRead(merged.autoRead);
        setPollSeconds(merged.pollSeconds);
        setWebSourcePriority(merged.webSourcePriority);
      }
      setPreferencesReady(true);
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
      try {
        const readResult = await readConfiguredPathsOnce(parsedPaths);
        if (cancelled) return;

        setUploadedFileLinks({});
        setLogs(readResult.mergedLogs);
        setLastReadAt(new Date().toLocaleString());
        setLastReadTime(Date.now());
        setError(readResult.failures ? `Read ${readResult.successes}/${parsedPaths.length} paths.` : "");

        try {
          await analyzeLogs(readResult.mergedLogs, false);
        } catch (e) {
          setError(e instanceof Error ? e.message : "Auto analyze failed.");
        }
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "Failed reading all configured paths.");
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
    return findings.filter((item) => findingTypeKey(item) === problemType);
  }, [findings, problemType]);

  const problemTypeOptions = useMemo(() => {
    const map = new Map();
    findings.forEach((item) => {
      const key = findingTypeKey(item);
      const label = findingTypeLabel(item);
      if (!map.has(key)) {
        map.set(key, label);
      }
    });
    return [...map.entries()]
      .map(([id, title]) => ({ id, title }))
      .sort((a, b) => {
        const leftOrder = ISSUE_CLASSIFICATION_ORDER.has(a.id)
          ? ISSUE_CLASSIFICATION_ORDER.get(a.id)
          : Number.MAX_SAFE_INTEGER;
        const rightOrder = ISSUE_CLASSIFICATION_ORDER.has(b.id)
          ? ISSUE_CLASSIFICATION_ORDER.get(b.id)
          : Number.MAX_SAFE_INTEGER;
        if (leftOrder !== rightOrder) {
          return leftOrder - rightOrder;
        }
        return a.title.localeCompare(b.title);
      });
  }, [findings]);

  const problemTypeLabel = useMemo(() => {
    if (problemType === "all") return "All problems";
    return problemTypeOptions.find((item) => item.id === problemType)?.title || "All problems";
  }, [problemType, problemTypeOptions]);

  const classificationSummary = useMemo(() => {
    const map = new Map();
    findings.forEach((item) => {
      const key = findingTypeKey(item);
      const label = findingTypeLabel(item);
      const count = Number(item.count || 0);
      if (!map.has(key)) {
        map.set(key, { id: key, label, findings: 0, occurrences: 0, severity: "low" });
      }
      const current = map.get(key);
      current.findings += 1;
      current.occurrences += count > 0 ? count : 1;
      current.severity = strongerSeverity(current.severity, item.severity || "low");
    });
    return [...map.values()].sort((a, b) => {
      const leftOrder = ISSUE_CLASSIFICATION_ORDER.has(a.id)
        ? ISSUE_CLASSIFICATION_ORDER.get(a.id)
        : Number.MAX_SAFE_INTEGER;
      const rightOrder = ISSUE_CLASSIFICATION_ORDER.has(b.id)
        ? ISSUE_CLASSIFICATION_ORDER.get(b.id)
        : Number.MAX_SAFE_INTEGER;
      if (leftOrder !== rightOrder) {
        return leftOrder - rightOrder;
      }
      if (b.findings !== a.findings) return b.findings - a.findings;
      return b.occurrences - a.occurrences;
    });
  }, [findings]);

  const lastReadRelativeLabel = useMemo(() => formatRelativeTime(lastReadTime), [lastReadTime]);

  const findingRows = useMemo(
    () =>
      visibleFindings.map((item) => ({
        key: findingFingerprint(item),
        item,
      })),
    [visibleFindings]
  );

  const selectedFinding = useMemo(() => {
    if (!findingRows.length) return null;
    const matched = findingRows.find((entry) => entry.key === selectedFindingKey);
    return matched ? matched.item : findingRows[0].item;
  }, [findingRows, selectedFindingKey]);

  const selectedFindingFingerprint = useMemo(
    () => (selectedFinding ? findingFingerprint(selectedFinding) : ""),
    [selectedFinding]
  );

  const selectedFindingReview = selectedFindingFingerprint
    ? reviewRecords[selectedFindingFingerprint]
    : null;
  const selectedFindingWebData = selectedFindingFingerprint
    ? webSolutionsByFinding[selectedFindingFingerprint]
    : null;
  const selectedFindingWebBusy = selectedFindingFingerprint
    ? Boolean(webSolutionBusyByFinding[selectedFindingFingerprint])
    : false;

  useEffect(() => {
    if (!findingRows.length) {
      if (selectedFindingKey) {
        setSelectedFindingKey("");
      }
      return;
    }
    if (!selectedFindingKey || !findingRows.some((entry) => entry.key === selectedFindingKey)) {
      setSelectedFindingKey(findingRows[0].key);
    }
  }, [findingRows, selectedFindingKey]);

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
    const nextPath = resolvePathFromSuggestion(rawPath);
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

    setEditingPathOriginal("");
    setEditingPathValue("");
    setPathSuggestionInput("");
    setPathAliases({});
    setPathHistory([]);
    await savePathHistoryToConfig([], { replace: true });
  }

  function startRenamingSavedPath(path) {
    const normalized = normalizePathInput(path);
    if (!normalized) return;
    setEditingPathOriginal(normalized);
    setEditingPathValue(displayNameForSavedPath(normalized));
  }

  function cancelRenamingSavedPath() {
    setEditingPathOriginal("");
    setEditingPathValue("");
  }

  function saveRenamedSavedPath() {
    const fromPath = normalizePathInput(editingPathOriginal);
    const alias = normalizePathAlias(editingPathValue);
    if (!fromPath) return;
    if (/[\r\n]/.test(alias)) {
      window.alert("Display name cannot contain new lines.");
      return;
    }

    const key = fromPath.toLowerCase();
    const fallback = sourceNameFromPath(fromPath).toLowerCase();
    setPathAliases((prev) => {
      const next = { ...(prev && typeof prev === "object" ? prev : {}) };
      if (!alias || alias.toLowerCase() === fallback) {
        delete next[key];
      } else {
        next[key] = alias;
      }
      return next;
    });
    cancelRenamingSavedPath();
  }

  function removeSavedPath(path) {
    const normalized = normalizePathInput(path);
    if (!normalized) return;
    setPathHistory((prev) => prev.filter((item) => !isSamePathValue(item, normalized)));
    setPathAliases((prev) => {
      const next = { ...(prev && typeof prev === "object" ? prev : {}) };
      delete next[normalized.toLowerCase()];
      return next;
    });
    setPathSuggestionInput((prev) => (isSamePathValue(prev, normalized) ? "" : prev));
    if (isSamePathValue(editingPathOriginal, normalized)) {
      cancelRenamingSavedPath();
    }
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

  const refreshAgentCapabilities = useCallback(async () => {
    try {
      const response = await fetch("/api/agent/capabilities", { method: "GET" });
      if (!response.ok) {
        throw new Error(await extractApiErrorMessage(response, "Failed loading agent capabilities."));
      }
      const payload = await response.json();
      setAgentCapabilities(normalizeAgentCapabilities(payload));
    } catch {
      setAgentCapabilities(DEFAULT_AGENT_CAPABILITIES);
    }
  }, []);

  const refreshAgentRun = useCallback(async (runId, options = {}) => {
    const normalizedRunId = String(runId || "").trim();
    if (!normalizedRunId) return;

    const [runResponse, eventsResponse] = await Promise.all([
      fetch(`/api/agent/runs/${encodeURIComponent(normalizedRunId)}`, { method: "GET" }),
      fetch(`/api/agent/runs/${encodeURIComponent(normalizedRunId)}/events`, { method: "GET" }),
    ]);

    if (!runResponse.ok) {
      throw new Error(await extractApiErrorMessage(runResponse, "Failed loading agent run."));
    }
    if (!eventsResponse.ok) {
      throw new Error(await extractApiErrorMessage(eventsResponse, "Failed loading agent run events."));
    }

    const runPayload = await runResponse.json();
    const eventsPayload = await eventsResponse.json();
    setAgentRun(runPayload?.run || null);
    setAgentEvents(Array.isArray(eventsPayload?.events) ? eventsPayload.events : []);

    if (!options.silent) {
      setAgentError("");
    }
  }, []);

  useEffect(() => {
    if (!agentMode) return;
    void refreshAgentCapabilities();
  }, [agentMode, refreshAgentCapabilities]);

  async function startAgentRun() {
    const goalValue = agentGoal.trim();
    if (!goalValue) {
      setAgentError("Enter an agent goal before starting a run.");
      return;
    }

    setAgentBusy(true);
    setAgentError("");
    try {
      const response = await fetch("/api/agent/runs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          goal: goalValue,
          paths: parsedPaths,
          constraints: {
            allow_start: Boolean(agentActionMode && allowAgentRestart),
            allow_deploy: Boolean(agentActionMode && allowAgentDeploy),
            allow_code_changes: Boolean(agentActionMode && allowAgentCodeChange),
            allow_destructive_actions: false,
            requested_mode: agentActionMode ? "privileged_supervised" : "supervised_preview",
          },
        }),
      });
      if (!response.ok) {
        throw new Error(await extractApiErrorMessage(response, "Failed starting agent run."));
      }
      const payload = await response.json();
      setAgentRun(payload?.run || null);
      setAgentEvents(Array.isArray(payload?.events) ? payload.events : []);
      setAgentDecisionBusyByStep({});
      setAgentActionFeedback("");
    } catch (e) {
      setAgentError(e instanceof Error ? e.message : "Failed starting agent run.");
    } finally {
      setAgentBusy(false);
    }
  }

  async function refreshActiveAgentRun() {
    if (!activeAgentRunId) return;
    setAgentBusy(true);
    try {
      await refreshAgentRun(activeAgentRunId, { silent: false });
    } catch (e) {
      setAgentError(e instanceof Error ? e.message : "Failed refreshing agent run.");
    } finally {
      setAgentBusy(false);
    }
  }

  async function submitAgentDecision(stepId, action) {
    const normalizedStepId = String(stepId || "").trim();
    if (!activeAgentRunId || !normalizedStepId) return;
    if (!["approve", "reject"].includes(action)) return;

    setAgentDecisionBusyByStep((prev) => ({ ...prev, [normalizedStepId]: true }));
    setAgentError("");
    try {
      const response = await fetch(
        `/api/agent/runs/${encodeURIComponent(activeAgentRunId)}/steps/${encodeURIComponent(
          normalizedStepId
        )}/${action}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            note:
              action === "approve"
                ? "Approved by user in supervised preview mode."
                : "Rejected by user in supervised preview mode.",
          }),
        }
      );
      if (!response.ok) {
        throw new Error(await extractApiErrorMessage(response, "Failed submitting decision."));
      }
      const payload = await response.json();
      setAgentRun(payload?.run || null);
      setAgentEvents(Array.isArray(payload?.events) ? payload.events : []);
    } catch (e) {
      setAgentError(e instanceof Error ? e.message : "Failed submitting decision.");
    } finally {
      setAgentDecisionBusyByStep((prev) => ({ ...prev, [normalizedStepId]: false }));
    }
  }

  async function executeAgentPrivilegedAction(actionType) {
    const normalizedActionType = String(actionType || "").trim().toLowerCase();
    if (!activeAgentRunId) {
      setAgentError("Start an agent run before executing privileged actions.");
      return;
    }
    if (!agentCapabilities.privilegedActionsEnabled) {
      setAgentError("Privileged action mode is disabled in backend configuration.");
      return;
    }
    if (!agentActionMode) {
      setAgentError("Enable action mode before running privileged actions.");
      return;
    }
    if (!isAgentConfirmationSatisfied) {
      setAgentError("Enter the exact confirmation phrase before running privileged actions.");
      return;
    }
    setAgentActionBusyType(normalizedActionType);
    setAgentError("");
    setAgentActionFeedback("");
    try {
      const response = await fetch(
        `/api/agent/runs/${encodeURIComponent(activeAgentRunId)}/actions/${encodeURIComponent(normalizedActionType)}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            confirmationPhrase: agentActionConfirmation.trim(),
            note: `Action requested from UI: ${normalizedActionType}`,
          }),
        }
      );
      if (!response.ok) {
        throw new Error(await extractApiErrorMessage(response, "Failed executing privileged action."));
      }
      const payload = await response.json();
      setAgentRun(payload?.run || null);
      setAgentEvents(Array.isArray(payload?.events) ? payload.events : []);
      const actionPayload = payload?.action && typeof payload.action === "object" ? payload.action : {};
      const success = Boolean(actionPayload?.success);
      const exitCode =
        actionPayload?.exitCode === undefined || actionPayload?.exitCode === null
          ? "n/a"
          : String(actionPayload.exitCode);
      setAgentActionFeedback(
        `${normalizedActionType} ${success ? "completed" : "failed"} (exit code: ${exitCode}).`
      );
    } catch (e) {
      setAgentError(e instanceof Error ? e.message : "Failed executing privileged action.");
    } finally {
      setAgentActionBusyType("");
    }
  }

  useEffect(() => {
    if (!activeAgentRunId) return;
    if (!isAgentRunActive(agentRun?.status)) return;

    let cancelled = false;
    const tick = async () => {
      try {
        await refreshAgentRun(activeAgentRunId, { silent: true });
      } catch (e) {
        if (cancelled) return;
        setAgentError(e instanceof Error ? e.message : "Failed refreshing active agent run.");
      }
    };

    void tick();
    const timer = setInterval(() => {
      void tick();
    }, 3000);

    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [activeAgentRunId, agentRun?.status, refreshAgentRun]);

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
            classificationLabel: item.classificationLabel,
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

  async function readConfiguredPathsOnce(paths) {
    async function readMany(targetPaths) {
      const settled = await Promise.allSettled(
        targetPaths.map(async (path) => ({ path, content: await fetchLogsFromPath(path) }))
      );
      const successes = settled
        .filter((result) => result.status === "fulfilled")
        .map((result) => result.value);
      const failures = settled.filter((result) => result.status === "rejected").length;
      const firstError = settled.find((result) => result.status === "rejected")?.reason;
      return { successes, failures, firstError };
    }

    const first = await readMany(paths);
    if (first.successes.length) {
      const mergedLogs = first.successes
        .map((item) => `--- PATH: ${item.path} ---\n${item.content}`)
        .join("\n\n");
      return { mergedLogs, successes: first.successes.length, failures: first.failures };
    }

    const remoteHistory = await loadPathHistoryFromConfig();
    const catalog = mergePathHistory(pathHistory, remoteHistory, PATH_HISTORY_LIMIT);
    const remapped = [...new Set(paths.map((item) => resolvePathAgainstCatalog(item, catalog, normalizedPathAliases)))];
    const hasChanged =
      remapped.length !== paths.length ||
      remapped.some((item, index) => !isSamePathValue(item, paths[index]));

    if (hasChanged) {
      const retry = await readMany(remapped);
      if (retry.successes.length) {
        const mergedLogs = retry.successes
          .map((item) => `--- PATH: ${item.path} ---\n${item.content}`)
          .join("\n\n");
        return { mergedLogs, successes: retry.successes.length, failures: retry.failures };
      }
      const retryError =
        retry.firstError instanceof Error ? retry.firstError.message : "Failed reading all configured paths.";
      throw new Error(`Failed reading all configured paths. ${retryError}`);
    }

    const firstError =
      first.firstError instanceof Error ? first.firstError.message : "Failed reading all configured paths.";
    throw new Error(`Failed reading all configured paths. ${firstError}`);
  }

  async function onAnalyze() {
    setBusy(true);
    setError("");
    setAiResult(null);

    try {
      if (parsedPaths.length) {
        setPathHistory((prev) => mergePathHistory(prev, parsedPaths));
        const readResult = await readConfiguredPathsOnce(parsedPaths);
        setUploadedFileLinks({});
        setLogs(readResult.mergedLogs);
        setLastReadAt(new Date().toLocaleString());
        setLastReadTime(Date.now());
        setError(readResult.failures ? `Read ${readResult.successes}/${parsedPaths.length} paths.` : "");
        await analyzeLogs(readResult.mergedLogs, true);
        return;
      }

      if (!logs.trim()) {
        setError("Add logs first.");
        return;
      }

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

  function toggleTopFilters() {
    setShowTopFilters((prev) => {
      const next = !prev;
      if (!next) {
        setIsProblemTypeDropdownOpen(false);
      }
      return next;
    });
  }

  function exportCurrentLogs() {
    const content = String(logs || "");
    if (!content.trim()) {
      setError("No logs available to export.");
      return;
    }
    try {
      const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      const link = document.createElement("a");
      link.href = url;
      link.download = `ai-log-analyzer-${stamp}.log`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
    } catch {
      setError("Export failed.");
    }
  }

  async function createJiraDraft() {
    const target = selectedFinding;
    const title = target ? target.title : "Log issue from AI Log Analyzer";
    const description = target
      ? [
          `Severity: ${target.severity}`,
          `Occurrences: ${target.count}`,
          `Source: ${displaySourcePath(target.sourcePath)}`,
          `Classification: ${target.classificationLabel || target.categoryLabel}`,
          "",
          `Resolution Hint: ${target.resolution}`,
          "",
          "Evidence:",
          ...(target.evidence || []).slice(0, 8),
        ].join("\n")
      : "No specific finding selected.";

    const draft = `Jira Summary: ${title}\n\n${description}`;
    try {
      if (navigator?.clipboard?.writeText) {
        await navigator.clipboard.writeText(draft);
        window.alert("Jira draft copied to clipboard. Paste it into Jira.");
        return;
      }
    } catch {
      // fallback to alert below
    }
    window.alert(`Jira draft prepared:\n\n${draft}`);
  }

  return (
    <main className="log-analyzer">
      <section className="hero">
        <div className="hero-title-row">
          <h1>AI Log Analyzer</h1>
          <span className="env-pill" title={envSourcePath || "No path configured"}>
            {envLabel}
          </span>
        </div>
        <p>Read up to 30 files, detect problems, and learn from your feedback.</p>
      </section>

      <section className="command-bar">
        <button
          type="button"
          className="action-button action-ghost"
          onClick={() => setIsLeftPanelVisible((prev) => !prev)}
          aria-expanded={isLeftPanelVisible}
          aria-controls="sources-panel"
        >
          {isLeftPanelVisible ? "Hide left panel" : "Show left panel"}
        </button>
        <button type="button" className="action-button action-primary" disabled={busy} onClick={onAnalyze}>
          {busy ? "Analyzing..." : "Analyze"}
        </button>
        <button type="button" className="action-button action-ghost" onClick={() => void createJiraDraft()}>
          Create Jira
        </button>
        <button type="button" className="action-button action-ghost" onClick={exportCurrentLogs}>
          Export
        </button>
        <button
          type="button"
          className="action-button action-ghost command-refresh"
          onClick={() => {
            if (activeAgentRunId) {
              void refreshActiveAgentRun();
              return;
            }
            void onAnalyze();
          }}
          title="Refresh"
        >
          Refresh
        </button>
      </section>

      <section className={`workspace-grid ${isLeftPanelVisible ? "" : "is-left-hidden"}`}>
        {isLeftPanelVisible ? (
          <section id="sources-panel" className="card">
        <div className="stats-row">
          <article className="stat-card">
            <span>Detected problems</span>
            <strong>{findings.length}</strong>
          </article>
          <article className="stat-card">
            <span>Files</span>
            <strong>{loadedFileCount}</strong>
          </article>
          <article className="stat-card">
            <span>Configured paths</span>
            <strong>{parsedPaths.length}</strong>
          </article>
          <article className="stat-card">
            <span>Last run</span>
            <strong>{lastReadRelativeLabel}</strong>
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

        <section className="agent-console-panel">
          <div className="agent-console-header">
            <div>
              <h3>Agent Console (Supervised Preview)</h3>
              <p className="muted">
                Supervised flow with approval gates. Privileged actions require explicit mode enablement.
              </p>
            </div>
            <button
              type="button"
              className={`action-button action-toggle ${agentMode ? "is-active" : ""}`}
              onClick={() => setAgentMode((prev) => !prev)}
            >
              {agentMode ? "Agent mode: On" : "Agent mode: Off"}
            </button>
          </div>

          {agentMode ? (
            <>
              <div className="agent-console-controls">
                <div className="filter-field agent-goal-field">
                  <label htmlFor="agent-goal">Agent goal</label>
                  <input
                    id="agent-goal"
                    type="text"
                    placeholder="Example: Reduce checkout 500 errors in 15 minutes."
                    value={agentGoal}
                    onChange={(event) => setAgentGoal(event.target.value)}
                  />
                </div>
                <div className="agent-console-actions">
                  <button
                    type="button"
                    className="action-button action-primary"
                    onClick={startAgentRun}
                    disabled={agentBusy || !agentGoal.trim()}
                  >
                    {agentBusy ? "Starting..." : "Start agent run"}
                  </button>
                  <button
                    type="button"
                    className="action-button action-ghost"
                    onClick={refreshActiveAgentRun}
                    disabled={agentBusy || !activeAgentRunId}
                  >
                    Refresh
                  </button>
                </div>
              </div>

              <section className="agent-action-mode-panel">
                <div className="agent-action-mode-header">
                  <strong>Privileged Action Mode</strong>
                  <button
                    type="button"
                    className={`action-button action-toggle ${agentActionMode ? "is-active" : ""}`}
                    onClick={() => {
                      setAgentActionMode((prev) => !prev);
                      setAgentActionFeedback("");
                      setAgentError("");
                    }}
                    disabled={!agentCapabilities.privilegedActionsEnabled}
                  >
                    {agentActionMode ? "Action mode: On" : "Action mode: Off"}
                  </button>
                </div>
                <p className="muted">{agentCapabilities.policyNotice}</p>
                {agentCapabilities.privilegedActionsEnabled ? (
                  <>
                    <div className="agent-action-permissions">
                      <label className="watch-toggle" htmlFor="allow-agent-restart">
                        <input
                          id="allow-agent-restart"
                          type="checkbox"
                          checked={allowAgentRestart}
                          onChange={(event) => setAllowAgentRestart(event.target.checked)}
                        />
                        Allow restart in next run
                      </label>
                      <label className="watch-toggle" htmlFor="allow-agent-deploy">
                        <input
                          id="allow-agent-deploy"
                          type="checkbox"
                          checked={allowAgentDeploy}
                          onChange={(event) => setAllowAgentDeploy(event.target.checked)}
                        />
                        Allow deploy in next run
                      </label>
                      <label className="watch-toggle" htmlFor="allow-agent-code-change">
                        <input
                          id="allow-agent-code-change"
                          type="checkbox"
                          checked={allowAgentCodeChange}
                          onChange={(event) => setAllowAgentCodeChange(event.target.checked)}
                        />
                        Allow code change in next run
                      </label>
                    </div>
                    <small className="muted">
                      These permissions are attached to each newly started run. Existing runs keep prior constraints.
                    </small>
                  </>
                ) : null}
                {agentActionMode && agentCapabilities.privilegedActionsEnabled ? (
                  <div className="agent-action-controls">
                    <div className="filter-field agent-confirmation-field">
                      <label htmlFor="agent-action-confirmation">Confirmation phrase</label>
                      <input
                        id="agent-action-confirmation"
                        type="text"
                        value={agentActionConfirmation}
                        placeholder={agentConfirmationPhrase || "Not configured"}
                        onChange={(event) => setAgentActionConfirmation(event.target.value)}
                      />
                    </div>
                    <div className="agent-action-buttons">
                      <button
                        type="button"
                        className="action-button action-danger"
                        disabled={
                          !activeAgentRunId ||
                          !isAgentConfirmationSatisfied ||
                          !agentCapabilities.actions.restart_server ||
                          !activeRunAllowsRestart ||
                          Boolean(agentActionBusyType)
                        }
                        onClick={() => executeAgentPrivilegedAction("restart_server")}
                      >
                        {agentActionBusyType === "restart_server" ? "Running..." : "Restart Server"}
                      </button>
                      <button
                        type="button"
                        className="action-button action-danger"
                        disabled={
                          !activeAgentRunId ||
                          !isAgentConfirmationSatisfied ||
                          !agentCapabilities.actions.deploy ||
                          !activeRunAllowsDeploy ||
                          Boolean(agentActionBusyType)
                        }
                        onClick={() => executeAgentPrivilegedAction("deploy")}
                      >
                        {agentActionBusyType === "deploy" ? "Running..." : "Run Deployment"}
                      </button>
                      <button
                        type="button"
                        className="action-button action-danger"
                        disabled={
                          !activeAgentRunId ||
                          !isAgentConfirmationSatisfied ||
                          !agentCapabilities.actions.code_change ||
                          !activeRunAllowsCodeChange ||
                          Boolean(agentActionBusyType)
                        }
                        onClick={() => executeAgentPrivilegedAction("code_change")}
                      >
                        {agentActionBusyType === "code_change" ? "Running..." : "Apply Code Change"}
                      </button>
                    </div>
                    {activeAgentRunId ? (
                      <small className="muted">
                        Active run permissions: restart={activeRunAllowsRestart ? "yes" : "no"}, deploy=
                        {activeRunAllowsDeploy ? "yes" : "no"}, code change=
                        {activeRunAllowsCodeChange ? "yes" : "no"}.
                      </small>
                    ) : null}
                    {activeAgentRunId && activeRunConstraints.extracted_target ? (
                      <small className="muted">
                        Detected target from prompt: {String(activeRunConstraints.extracted_target)}
                      </small>
                    ) : null}
                    {!isAgentConfirmationSatisfied ? (
                      <small className="muted">
                        Enter exact confirmation phrase and ensure run constraints allow the selected action.
                      </small>
                    ) : null}
                  </div>
                ) : null}
                {agentActionFeedback ? <p className="muted">{agentActionFeedback}</p> : null}
              </section>

              <p className="muted agent-console-paths">
                Planned paths: {parsedPaths.length ? `${parsedPaths.length} selected` : "none selected"}.
              </p>
              {agentError ? <p className="error">{agentError}</p> : null}

              {agentRun ? (
                <section className="agent-run-surface">
                  <p className="agent-run-meta">
                    <strong>Run:</strong>
                    <code>{agentRun.id}</code>
                    <span className="classification-tag">{agentRun.status || "UNKNOWN"}</span>
                  </p>
                  {agentRun.summary ? <p className="agent-run-summary">{agentRun.summary}</p> : null}
                  <p className="muted agent-run-summary">Confidence: {agentRun.confidence ?? "n/a"}</p>

                  <div className="agent-grid">
                    <section className="agent-steps">
                      <h4>Plan Steps</h4>
                      {!activeAgentSteps.length ? <p className="muted">No plan steps available.</p> : null}
                      {activeAgentSteps.map((step) => {
                        const waitingApproval = step.status === "AWAITING_APPROVAL";
                        const decisionBusy = Boolean(agentDecisionBusyByStep[step.id]);
                        const stepDetails = buildAgentStepDetailModel(step);
                        return (
                          <article key={step.id} className="agent-step-card">
                            <header>
                              <h5>{step.title || "Untitled step"}</h5>
                              <span className="classification-tag">{step.status || "PENDING"}</span>
                            </header>
                            <p className="muted">
                              Tool: {step.toolName || "unknown"} | Risk: {step.riskLevel || "SAFE"}
                            </p>
                            {step.summary ? <p>{step.summary}</p> : null}
                            {stepDetails ? (
                              <div className="agent-step-details">
                                {stepDetails.primaryCategory ? (
                                  <p>
                                    <strong>Primary category:</strong> {stepDetails.primaryCategory}
                                  </p>
                                ) : null}
                                {stepDetails.targetHost ? (
                                  <p>
                                    <strong>Target host:</strong> {stepDetails.targetHost}
                                  </p>
                                ) : null}
                                {stepDetails.evidenceSummary ? (
                                  <p>
                                    <strong>Evidence:</strong> {stepDetails.evidenceSummary}
                                  </p>
                                ) : null}
                                {stepDetails.unreadablePathHints.length ? (
                                  <p>
                                    <strong>Unreadable paths:</strong> {stepDetails.unreadablePathHints.join(" | ")}
                                  </p>
                                ) : null}
                                {stepDetails.options.length ? (
                                  <div className="agent-step-options">
                                    {stepDetails.options.map((option, index) => (
                                      <article key={`${step.id}-${option.key}`} className="agent-step-option">
                                        <p className="agent-step-option-title">
                                          <strong>
                                            Option {index + 1}: {option.title}
                                          </strong>
                                        </p>
                                        <p className="muted">
                                          Risk: {option.risk}
                                          {option.requiresApproval ? " | User approval required" : ""}
                                        </p>
                                        {option.why ? <p>{option.why}</p> : null}
                                        {option.actions.length ? (
                                          <ul className="agent-step-list">
                                            {option.actions.map((action, actionIndex) => (
                                              <li key={`${step.id}-${option.key}-action-${actionIndex}`}>
                                                {action}
                                              </li>
                                            ))}
                                          </ul>
                                        ) : null}
                                        {option.successSignals.length ? (
                                          <p>
                                            <strong>Success signals:</strong>{" "}
                                            {option.successSignals.join(" | ")}
                                          </p>
                                        ) : null}
                                        {option.rollback ? (
                                          <p>
                                            <strong>Rollback:</strong> {option.rollback}
                                          </p>
                                        ) : null}
                                      </article>
                                    ))}
                                  </div>
                                ) : null}
                                {stepDetails.approvalChecklist.length ? (
                                  <p>
                                    <strong>Approval checklist:</strong>{" "}
                                    {stepDetails.approvalChecklist.join(" | ")}
                                  </p>
                                ) : null}
                                {stepDetails.blockedActions.length ? (
                                  <p>
                                    <strong>Blocked without user input:</strong>{" "}
                                    {stepDetails.blockedActions.join(" | ")}
                                  </p>
                                ) : null}
                                {stepDetails.executedActions.length ? (
                                  <div className="agent-executed-actions">
                                    {stepDetails.executedActions.map((action) => (
                                      <p key={`${step.id}-executed-${action.key}`}>
                                        <strong>{action.actionLabel}:</strong>{" "}
                                        {action.success ? "success" : "failed"}
                                        {action.exitCode !== null ? ` (exit ${action.exitCode})` : ""}
                                        {action.timestamp
                                          ? ` at ${new Date(action.timestamp).toLocaleString()}`
                                          : ""}
                                        {action.note ? ` | ${action.note}` : ""}
                                        {action.error ? ` | ${action.error}` : ""}
                                      </p>
                                    ))}
                                  </div>
                                ) : null}
                                {stepDetails.decision ? (
                                  <p>
                                    <strong>Decision:</strong> {stepDetails.decision}
                                    {stepDetails.decisionNote ? ` (${stepDetails.decisionNote})` : ""}
                                  </p>
                                ) : stepDetails.note ? (
                                  <p>{stepDetails.note}</p>
                                ) : null}
                              </div>
                            ) : null}
                            {step.error ? <p className="error">{step.error}</p> : null}
                            {waitingApproval ? (
                              <div className="agent-step-actions">
                                <button
                                  type="button"
                                  className="action-button action-accent"
                                  onClick={() => submitAgentDecision(step.id, "approve")}
                                  disabled={decisionBusy}
                                >
                                  {decisionBusy ? "Submitting..." : "Approve"}
                                </button>
                                <button
                                  type="button"
                                  className="action-button action-danger"
                                  onClick={() => submitAgentDecision(step.id, "reject")}
                                  disabled={decisionBusy}
                                >
                                  {decisionBusy ? "Submitting..." : "Reject"}
                                </button>
                              </div>
                            ) : null}
                          </article>
                        );
                      })}
                    </section>

                    <section className="agent-events">
                      <h4>Timeline</h4>
                      {!recentAgentEvents.length ? <p className="muted">No timeline events yet.</p> : null}
                      {recentAgentEvents.length ? (
                        <div className="agent-event-list">
                          {recentAgentEvents.map((event) => (
                            <article key={event.id} className="agent-event-item">
                              <p>
                                <strong>{event.type || "EVENT"}</strong>: {event.message || "No details"}
                              </p>
                              <small className="muted">
                                {event.timestamp ? new Date(event.timestamp).toLocaleString() : "time unavailable"}
                              </small>
                            </article>
                          ))}
                        </div>
                      ) : null}
                    </section>
                  </div>
                </section>
              ) : null}
            </>
          ) : (
            <p className="muted agent-console-collapsed-note">
              Enable Agent mode to run a supervised plan-execute-verify workflow with user approvals.
            </p>
          )}
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
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  addPathToInput(pathSuggestionInput);
                }
              }}
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
                {displayNameForSavedPath(path)}
              </button>
            ))}
          </div>
        ) : null}
        <div className="path-history-row">
          <small>Saved path history: {pathHistory.length} (max {PATH_HISTORY_LIMIT}).</small>
          <div className="path-history-actions">
            <button
              type="button"
              className="action-button action-ghost"
              onClick={() => setShowSavedPathHistory((prev) => !prev)}
              disabled={!pathHistory.length}
            >
              {showSavedPathHistory ? "Hide path history" : "Show path history"}
            </button>
            {showSavedPathHistory ? (
              <button
                type="button"
                className="action-button action-danger"
                onClick={clearSavedPaths}
                disabled={!pathHistory.length}
              >
                Clear saved paths
              </button>
            ) : null}
          </div>
        </div>
        {showSavedPathHistory && pathHistory.length ? (
          <section className="saved-path-list">
            {pathHistory.map((path) => {
              const isEditing = isSamePathValue(editingPathOriginal, path);
              return (
                <article key={path} className="saved-path-item">
                  {isEditing ? (
                    <input
                      type="text"
                      value={editingPathValue}
                      onChange={(event) => setEditingPathValue(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") {
                          event.preventDefault();
                          saveRenamedSavedPath();
                        }
                        if (event.key === "Escape") {
                          event.preventDefault();
                          cancelRenamingSavedPath();
                        }
                      }}
                    />
                  ) : (
                    <div className="saved-path-display">
                      <p className="saved-path-label" title={displayNameForSavedPath(path)}>
                        {displayNameForSavedPath(path)}
                      </p>
                      <code title={path}>{path}</code>
                    </div>
                  )}
                  <div className="saved-path-actions">
                    <button
                      type="button"
                      className="action-button action-ghost action-compact"
                      onClick={() => addPathToInput(path)}
                    >
                      Use
                    </button>
                    {isEditing ? (
                      <>
                        <button
                          type="button"
                          className="action-button action-ghost action-compact"
                          onClick={saveRenamedSavedPath}
                          disabled={!normalizePathInput(editingPathValue)}
                        >
                          Save
                        </button>
                        <button
                          type="button"
                          className="action-button action-ghost action-compact"
                          onClick={cancelRenamingSavedPath}
                        >
                          Cancel
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          className="action-button action-ghost action-compact"
                          onClick={() => startRenamingSavedPath(path)}
                        >
                          Rename
                        </button>
                        <button
                          type="button"
                          className="action-button action-danger action-compact"
                          onClick={() => removeSavedPath(path)}
                        >
                          Remove
                        </button>
                      </>
                    )}
                  </div>
                </article>
              );
            })}
          </section>
        ) : null}
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

        {error ? <p className="error">{error}</p> : null}
          </section>
        ) : null}

        <section className="results">
        <section className="filters-shell">
          <div className={`filter-toggle-row ${showTopFilters ? "is-open" : ""}`}>
            <small>Filters And Priority</small>
            <button
              type="button"
              className="action-button action-ghost"
              onClick={toggleTopFilters}
              aria-expanded={showTopFilters}
            >
              {showTopFilters ? "Hide filters" : "Show filters"}
            </button>
          </div>
          {showTopFilters ? (
            <>
              <div className="filters top-filters">
                <div className="filter-field">
                  <label htmlFor="problem-type">Problem type</label>
                  <div className="single-select-dropdown" ref={problemTypeDropdownRef}>
                    <button
                      id="problem-type"
                      type="button"
                      className={`single-select-trigger ${isProblemTypeDropdownOpen ? "is-open" : ""}`}
                      onClick={() => setIsProblemTypeDropdownOpen((prev) => !prev)}
                      aria-expanded={isProblemTypeDropdownOpen}
                      aria-controls="problem-type-menu"
                    >
                      <span className="single-select-trigger-text">{problemTypeLabel}</span>
                    </button>
                    {isProblemTypeDropdownOpen ? (
                      <div id="problem-type-menu" className="single-select-menu" role="listbox">
                        <button
                          type="button"
                          className={`single-select-option ${problemType === "all" ? "is-selected" : ""}`}
                          onClick={() => {
                            setProblemType("all");
                            setIsProblemTypeDropdownOpen(false);
                          }}
                        >
                          <span>All problems</span>
                        </button>
                        {problemTypeOptions.map((item) => (
                          <button
                            key={item.id}
                            type="button"
                            className={`single-select-option ${
                              problemType === item.id ? "is-selected" : ""
                            }`}
                            onClick={() => {
                              setProblemType(item.id);
                              setIsProblemTypeDropdownOpen(false);
                            }}
                            title={item.title}
                          >
                            <span>{item.title}</span>
                          </button>
                        ))}
                      </div>
                    ) : null}
                  </div>
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
                  <div className="web-source-priority-actions">
                    <button
                      type="button"
                      className="action-button action-ghost"
                      onClick={resetWebSourcePriority}
                    >
                      Reset order
                    </button>
                  </div>
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
                <p className="muted source-priority-summary">
                  Search preferences are saved automatically and restored on next launch.
                </p>
              </section>
            </>
          ) : (
            <p className="muted filter-shell-collapsed-note">
              Filters are hidden. Click "Show filters" to update problem/date/time or web source priority.
            </p>
          )}
        </section>

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

        <div className="results-topline">
          <h2>Detected Problems</h2>
          <small className="muted">
            Showing {visibleFindings.length} of {findings.length} findings
          </small>
        </div>
        {classificationSummary.length ? (
          <div className="classification-summary" role="group" aria-label="Issue classification summary">
            {classificationSummary.map((item) => (
              <button
                key={`classification-${item.id}`}
                type="button"
                className={`classification-chip ${problemType === item.id ? "is-active" : ""}`}
                onClick={() => setProblemType(item.id)}
                title={`${item.label} (${item.findings} findings, ${item.occurrences} occurrences)`}
              >
                <span>{item.label}</span>
                <strong>{item.findings}</strong>
              </button>
            ))}
          </div>
        ) : null}

        <div className="findings-workspace">
          <section className="findings-list-panel">
            <header className="findings-list-header">
              <span>Problem</span>
              <span>Type</span>
              <span>File</span>
              <span>Occurrences</span>
            </header>
            {!findingRows.length ? <p className="muted">No findings yet.</p> : null}
            {findingRows.length ? (
              <div className="findings-row-list">
                {findingRows.map((entry) => {
                  const item = entry.item;
                  return (
                    <button
                      key={entry.key}
                      type="button"
                      className={`finding-row ${selectedFindingKey === entry.key ? "is-selected" : ""}`}
                      onClick={() => setSelectedFindingKey(entry.key)}
                    >
                      <div className="finding-row-problem">
                        <span className={`severity-pill severity-${item.severity}`}>{item.severity}</span>
                        <strong>{item.title}</strong>
                      </div>
                      <span className="finding-row-type">
                        {item.classificationLabel || item.categoryLabel || "Unknown"}
                      </span>
                      <span className="finding-row-file">{item.sourceName || "unknown"}</span>
                      <span className="finding-row-count">{item.count}</span>
                    </button>
                  );
                })}
              </div>
            ) : null}
          </section>

          <section className="finding-detail-panel">
            {!selectedFinding ? <p className="muted">Select a finding to inspect details.</p> : null}
            {selectedFinding ? (
              <>
                <header className="finding-detail-header">
                  <div>
                    <h3>{selectedFinding.title}</h3>
                    <p className="source-path">
                      <span className="source-name">{selectedFinding.sourceName || "unknown"}</span>
                      <code title={displaySourcePath(selectedFinding.sourcePath)}>
                        {displayCompactPath(selectedFinding.sourcePath)}
                      </code>
                      {buildLogViewUrl(selectedFinding.sourcePath, uploadedFileLinks) ? (
                        <a
                          className="log-link"
                          href={buildLogViewUrl(selectedFinding.sourcePath, uploadedFileLinks)}
                          target="_blank"
                          rel="noreferrer"
                        >
                          Open file
                        </a>
                      ) : null}
                    </p>
                  </div>
                  <span className={`severity severity-${selectedFinding.severity}`}>{selectedFinding.severity}</span>
                </header>

                <p>Occurrences: {selectedFinding.count}</p>
                <p className="classification-line">
                  <strong>Classification:</strong>{" "}
                  <span className="classification-tag">
                    {selectedFinding.classificationLabel || "Unclassified runtime issue"}
                  </span>
                  {Array.isArray(selectedFinding.classificationSignals) &&
                  selectedFinding.classificationSignals.length ? (
                    <span className="classification-signals muted">
                      Matched: {selectedFinding.classificationSignals.join(", ")}
                    </span>
                  ) : null}
                </p>
                <p>Resolution: {selectedFinding.resolution}</p>

                <div className="feedback-actions">
                  <button
                    type="button"
                    className="action-button action-accent"
                    disabled={selectedFindingWebBusy}
                    onClick={() => findWebSolutionsForFinding(selectedFinding)}
                  >
                    {selectedFindingWebBusy ? "Searching Web..." : "Find Web Solutions"}
                  </button>
                  <button
                    type="button"
                    className="action-button action-ghost"
                    onClick={() => markAsNotProblem(selectedFinding)}
                  >
                    Mark as /Not Problem
                  </button>
                  <button
                    type="button"
                    className="action-button action-ghost"
                    onClick={() => markAsProblem(selectedFinding)}
                  >
                    Mark as Problem
                  </button>
                  {selectedFindingReview ? (
                    <small className="muted">Marked: {selectedFindingReview.status}</small>
                  ) : null}
                </div>

                {selectedFindingWebData ? (
                  <div className="web-solution-panel">
                    <p className="web-query">
                      <strong>Web query:</strong> {selectedFindingWebData.query || selectedFinding.title}
                    </p>
                    {selectedFindingWebData.warning ? (
                      <p className="muted">{selectedFindingWebData.warning}</p>
                    ) : null}
                    {Array.isArray(selectedFindingWebData.solutions) && selectedFindingWebData.solutions.length ? (
                      <div className="web-solution-list">
                        {selectedFindingWebData.solutions.map((solution, index) => (
                          <article key={`${selectedFindingFingerprint}-web-${index}`} className="web-solution-item">
                            <p className="web-solution-title">
                              <strong>{solution.title || `Solution ${index + 1}`}</strong>
                            </p>
                            <p className="web-solution-source">
                              <strong>Source:</strong> {solution.source || "Web"}
                            </p>
                            <p className="web-solution-text">{solution.solution}</p>
                            {solution.url ? (
                              <a className="log-link" href={solution.url} target="_blank" rel="noreferrer">
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
                  {(selectedFinding.evidence || []).map((line, index) => (
                    <p key={`${selectedFindingFingerprint}-${index}`} className="evidence-line">
                      {line}
                    </p>
                  ))}
                </div>
              </>
            ) : null}
          </section>
        </div>
        </section>
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
