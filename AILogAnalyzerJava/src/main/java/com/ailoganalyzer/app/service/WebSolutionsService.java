package com.ailoganalyzer.app.service;

import com.ailoganalyzer.app.config.AnalyzerSettings;
import com.ailoganalyzer.app.model.WebSolutionItem;
import com.ailoganalyzer.app.model.WebSolutionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class WebSolutionsService {
  private static final String OPENAI_RESPONSES_API_URL = "https://api.openai.com/v1/responses";
  private static final String GOOGLE_CSE_API_URL = "https://www.googleapis.com/customsearch/v1";
  private static final String HUGGINGFACE_CHAT_COMPLETIONS_API_URL =
      "https://router.huggingface.co/v1/chat/completions";
  private static final String GROQ_CHAT_COMPLETIONS_API_URL = "https://api.groq.com/openai/v1/chat/completions";
  private static final String OPENROUTER_CHAT_COMPLETIONS_API_URL =
      "https://openrouter.ai/api/v1/chat/completions";
  private static final List<String> ALL_WEB_SOURCE_KEYS =
      List.of(
          "localai",
          "google",
          "gemini",
          "huggingface",
          "groq",
          "openrouter",
          "stackoverflow",
          "github",
          "chatgpt",
          "local");
  private static final Pattern EXCEPTION_CLASS_PATTERN =
      Pattern.compile(
          "\\b([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:Exception|Error|Throwable))\\b");
  private static final Pattern EXCEPTION_WITH_MESSAGE_PATTERN =
      Pattern.compile(
          "\\b([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:Exception|Error|Throwable))\\b\\s*:\\s*([^\\r\\n]{3,180})");
  private static final Pattern ERROR_CODE_PATTERN =
      Pattern.compile(
          "\\b(?:ORA-\\d{3,8}|SQLSTATE[:\\s]*[0-9A-Z]{5}|HTTP\\s*\\d{3}|[A-Z]{2,12}-\\d{3,8})\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ERROR_KEYWORD_PATTERN =
      Pattern.compile(
          "\\b(exception|error|failed|failure|timeout|timed\\s*out|refused|denied|unauthorized|forbidden)\\b",
          Pattern.CASE_INSENSITIVE);

  private final AnalyzerSettings settings;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public WebSolutionsService(AnalyzerSettings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
  }

  public Map<String, Object> getAiCapabilities() {
    Map<String, Object> agentMode = new LinkedHashMap<>();
    boolean agentLlmEnabled = settings.isAgentLlmEnabled();
    boolean agentLlmActive = agentLlmEnabled && hasText(settings.getOpenAiApiKey());
    agentMode.put("engine", agentLlmEnabled ? "llm-supervised" : "supervised-heuristic-workflow");
    agentMode.put("usesLlm", agentLlmActive);
    agentMode.put("model", agentLlmEnabled ? settings.getAgentLlmModel() : "");
    if (agentLlmActive) {
      agentMode.put(
          "summary",
          "Agent Console uses LLM-supervised classification and remediation plan drafting; execution remains approval-gated and controlled.");
    } else if (agentLlmEnabled) {
      agentMode.put(
          "summary",
          "Agent Console is configured for LLM-supervised mode, but no backend OPENAI_API_KEY is available, so it will fall back to heuristic planning.");
    } else {
      agentMode.put(
          "summary",
          "Agent Console is a supervised workflow engine (log read + heuristic classification + approval-gated remediation planning).");
    }

    List<Map<String, Object>> providers = new ArrayList<>();
    providers.add(
        providerCapability(
            "localai",
            "Local AI Engine",
            true,
            true,
            false,
            "",
            false,
            "Heuristic local suggestions (no external LLM call)."));
    providers.add(
        providerCapability(
            "google",
            "Google Search",
            settings.isEnableGoogleSearch(),
            settings.isEnableGoogleSearch(),
            false,
            "",
            hasText(settings.getGoogleApiKey()) && hasText(settings.getGoogleCseCx()),
            hasText(settings.getGoogleApiKey()) && hasText(settings.getGoogleCseCx())
                ? "Uses Google Custom Search API when key + CX are configured."
                : "Falls back to generated Google search links if API key/CX are not configured."));
    providers.add(
        providerCapability(
            "gemini",
            "Gemini (Free Tier)",
            settings.isEnableGeminiFreeSearch(),
            settings.isEnableGeminiFreeSearch() && hasText(settings.getGeminiApiKey()),
            true,
            settings.getGeminiFreeModel(),
            hasText(settings.getGeminiApiKey()),
            "Requires GEMINI_API_KEY."));
    providers.add(
        providerCapability(
            "huggingface",
            "Hugging Face",
            settings.isEnableHuggingFaceSearch(),
            settings.isEnableHuggingFaceSearch() && hasText(settings.getHuggingFaceApiKey()),
            true,
            settings.getHuggingFaceModel(),
            hasText(settings.getHuggingFaceApiKey()),
            "Uses Hugging Face router chat-completions API."));
    providers.add(
        providerCapability(
            "groq",
            "Groq (Free Tier)",
            settings.isEnableGroqFreeSearch(),
            settings.isEnableGroqFreeSearch() && hasText(settings.getGroqApiKey()),
            true,
            settings.getGroqFreeModel(),
            hasText(settings.getGroqApiKey()),
            "Requires GROQ_API_KEY."));
    providers.add(
        providerCapability(
            "openrouter",
            "OpenRouter Free",
            settings.isEnableOpenRouterFreeSearch(),
            settings.isEnableOpenRouterFreeSearch() && hasText(settings.getOpenRouterApiKey()),
            true,
            settings.getOpenRouterFreeModel(),
            hasText(settings.getOpenRouterApiKey()),
            "Requires OPENROUTER_API_KEY."));
    providers.add(
        providerCapability(
            "stackoverflow",
            "Stack Overflow",
            true,
            true,
            false,
            "",
            false,
            "Uses Stack Exchange public API (no LLM)."));
    providers.add(
        providerCapability(
            "github",
            "GitHub Issues",
            settings.isEnableGithubIssueSearch(),
            settings.isEnableGithubIssueSearch(),
            false,
            "",
            hasText(settings.getGithubToken()),
            hasText(settings.getGithubToken())
                ? "GitHub token configured."
                : "Works without token but may be rate-limited."));
    providers.add(
        providerCapability(
            "chatgpt",
            "ChatGPT Web Search",
            settings.isEnableChatgptWebSearch(),
            settings.isEnableChatgptWebSearch() && hasText(settings.getOpenAiApiKey()),
            true,
            settings.getChatgptWebSearchModel(),
            hasText(settings.getOpenAiApiKey()),
            "Uses OpenAI Responses API with web_search tool; requires LOG_ENABLE_CHATGPT_WEB_SEARCH=true and OPENAI_API_KEY."));
    providers.add(
        providerCapability(
            "local",
            "Local Fallback",
            true,
            true,
            false,
            "",
            false,
            "Non-LLM fallback guidance used when no web providers return results."));

    Map<String, Object> webSolutions = new LinkedHashMap<>();
    webSolutions.put("limit", settings.getWebSolutionLimit());
    webSolutions.put("providers", providers);

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("agentMode", agentMode);
    output.put("webSolutions", webSolutions);
    return output;
  }

  public WebSolutionResponse findSolutions(
      Map<String, Object> finding, Integer requestedLimit, List<String> requestedSourcePriority) {
    int limit =
        clamp(
            requestedLimit == null ? settings.getWebSolutionLimit() : requestedLimit,
            1,
            settings.getWebSolutionLimit());

    String query = buildWebQueryFromFinding(finding);
    if (!hasText(query)) {
      throw new IllegalArgumentException("Missing finding data for web search.");
    }

    List<String> warnings = new ArrayList<>();
    List<WebSolutionItem> solutions = new ArrayList<>();
    List<String> sourcePriority = normalizeSourcePriority(requestedSourcePriority);

    for (String source : sourcePriority) {
      if (solutions.size() >= limit) {
        break;
      }

      int remaining = Math.max(1, limit - solutions.size());
      switch (source) {
        case "localai":
          try {
            List<WebSolutionItem> localAi = buildLocalAiSolutions(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, localAi, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "Local AI analyzer failed." : exception.getMessage());
          }
          break;

        case "local":
          if (solutions.isEmpty()) {
            List<WebSolutionItem> fallback = buildFallbackSolutions(finding);
            solutions = mergeUniqueSolutions(solutions, fallback, limit);
          }
          break;

        case "google":
          if (!settings.isEnableGoogleSearch()) {
            break;
          }
          try {
            List<WebSolutionItem> googleSolutions = searchGoogleSolutions(query, remaining);
            solutions = mergeUniqueSolutions(solutions, googleSolutions, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "Google search failed." : exception.getMessage());
          }
          break;

        case "gemini":
          if (!settings.isEnableGeminiFreeSearch()) {
            break;
          }
          if (!hasText(settings.getGeminiApiKey())) {
            break;
          }
          try {
            List<WebSolutionItem> geminiFree = searchWithGeminiFree(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, geminiFree, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "Gemini free search failed." : exception.getMessage());
          }
          break;

        case "huggingface":
          if (!settings.isEnableHuggingFaceSearch()) {
            break;
          }
          if (!hasText(settings.getHuggingFaceApiKey())) {
            break;
          }
          try {
            List<WebSolutionItem> huggingFace = searchWithHuggingFace(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, huggingFace, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "Hugging Face search failed." : exception.getMessage());
          }
          break;

        case "groq":
          if (!settings.isEnableGroqFreeSearch()) {
            break;
          }
          if (!hasText(settings.getGroqApiKey())) {
            break;
          }
          try {
            List<WebSolutionItem> groqFree = searchWithGroqFree(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, groqFree, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "Groq free search failed." : exception.getMessage());
          }
          break;

        case "openrouter":
          if (!settings.isEnableOpenRouterFreeSearch()) {
            break;
          }
          if (!hasText(settings.getOpenRouterApiKey())) {
            break;
          }
          try {
            List<WebSolutionItem> openRouterFree = searchWithOpenRouterFree(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, openRouterFree, limit);
          } catch (Exception exception) {
            warnings.add(
                exception.getMessage() == null ? "OpenRouter free search failed." : exception.getMessage());
          }
          break;

        case "stackoverflow":
          try {
            List<WebSolutionItem> stackOverflow = searchStackOverflowSolutions(query, remaining);
            solutions = mergeUniqueSolutions(solutions, stackOverflow, limit);
          } catch (Exception exception) {
            warnings.add(
                exception.getMessage() == null ? "Stack Overflow search failed." : exception.getMessage());
          }
          break;

        case "github":
          if (!settings.isEnableGithubIssueSearch()) {
            warnings.add("GitHub issue search is disabled by LOG_ENABLE_GITHUB_ISSUE_SEARCH=false.");
            break;
          }
          try {
            List<WebSolutionItem> githubIssues = searchGithubIssueSolutions(query, remaining);
            solutions = mergeUniqueSolutions(solutions, githubIssues, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "GitHub issue search failed." : exception.getMessage());
          }
          break;

        case "chatgpt":
          if (!settings.isEnableChatgptWebSearch()) {
            break;
          }
          if (!hasText(settings.getOpenAiApiKey())) {
            break;
          }
          try {
            List<WebSolutionItem> chatgpt = searchWithChatgptWeb(query, finding, remaining);
            solutions = mergeUniqueSolutions(solutions, chatgpt, limit);
          } catch (Exception exception) {
            warnings.add(exception.getMessage() == null ? "ChatGPT web search failed." : exception.getMessage());
          }
          break;

        default:
          break;
      }
    }

    if (solutions.isEmpty()) {
      warnings.add("No matches found for selected sources. Showing local fallback guidance.");
      solutions = mergeUniqueSolutions(solutions, buildFallbackSolutions(finding), limit);
    }

    String warning = warnings.stream().filter(WebSolutionsService::hasText).collect(Collectors.joining(" "));
    return new WebSolutionResponse(query, warning, solutions);
  }

  private Map<String, Object> providerCapability(
      String id,
      String label,
      boolean enabled,
      boolean active,
      boolean usesLlm,
      String model,
      boolean credentialConfigured,
      String note) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", id);
    item.put("label", label);
    item.put("enabled", enabled);
    item.put("active", active);
    item.put("usesLlm", usesLlm);
    item.put("model", hasText(model) ? model.trim() : "");
    item.put("credentialConfigured", credentialConfigured);
    item.put("note", hasText(note) ? note.trim() : "");
    return item;
  }

  private List<String> normalizeSourcePriority(List<String> requestedSourcePriority) {
    List<String> normalized = new ArrayList<>();
    if (requestedSourcePriority != null) {
      for (String item : requestedSourcePriority) {
        String key = normalizeSourceKey(item);
        if (hasText(key) && !normalized.contains(key)) {
          normalized.add(key);
        }
      }
    }

    if (normalized.isEmpty()) {
      normalized.add("localai");
      if (settings.isEnableGoogleSearch()) {
        normalized.add("google");
      }
      if (settings.isEnableGeminiFreeSearch() && hasText(settings.getGeminiApiKey())) {
        normalized.add("gemini");
      }
      if (settings.isEnableHuggingFaceSearch() && hasText(settings.getHuggingFaceApiKey())) {
        normalized.add("huggingface");
      }
      if (settings.isEnableGroqFreeSearch() && hasText(settings.getGroqApiKey())) {
        normalized.add("groq");
      }
      if (settings.isEnableOpenRouterFreeSearch() && hasText(settings.getOpenRouterApiKey())) {
        normalized.add("openrouter");
      }
      normalized.add("stackoverflow");
      if (settings.isEnableGithubIssueSearch()) {
        normalized.add("github");
      }
      if (settings.isEnableChatgptWebSearch()) {
        normalized.add("chatgpt");
      }
      normalized.add("local");
      return normalized;
    }

    if (!normalized.contains("local")) {
      normalized.add("local");
    }
    for (String source : ALL_WEB_SOURCE_KEYS) {
      if (!normalized.contains(source)) {
        normalized.add(source);
      }
    }
    return normalized;
  }

  private String normalizeSourceKey(String raw) {
    String value = safe(raw).trim().toLowerCase(Locale.ROOT);
    if (!hasText(value)) {
      return "";
    }
    if ("localai".equals(value) || "local-ai".equals(value) || "offline-ai".equals(value)
        || "codex".equals(value) || "localengine".equals(value) || "engine".equals(value)) {
      return "localai";
    }
    if ("local".equals(value) || "fallback".equals(value)) {
      return "local";
    }
    if ("google".equals(value) || "google-search".equals(value) || "google-links".equals(value)
        || "search".equals(value)) {
      return "google";
    }
    if ("gemini".equals(value) || "google-gemini".equals(value)) {
      return "gemini";
    }
    if ("huggingface".equals(value) || "hugging-face".equals(value) || "hf".equals(value)) {
      return "huggingface";
    }
    if ("groq".equals(value) || "groq-free".equals(value) || "groqfree".equals(value)) {
      return "groq";
    }
    if ("openrouter".equals(value) || "open-router".equals(value) || "router".equals(value)
        || "openrouter-free".equals(value)) {
      return "openrouter";
    }
    if ("stackoverflow".equals(value) || "stack-overflow".equals(value) || "stack_overflow".equals(value)
        || "so".equals(value)) {
      return "stackoverflow";
    }
    if ("github".equals(value) || "github-issues".equals(value) || "github_issues".equals(value)
        || "issues".equals(value)) {
      return "github";
    }
    if ("chatgpt".equals(value) || "chat-gpt".equals(value) || "openai".equals(value)) {
      return "chatgpt";
    }
    return "";
  }

  private List<WebSolutionItem> buildLocalAiSolutions(
      String query, Map<String, Object> finding, int maxSolutions) {
    String context =
        compactText(
            query
                + " "
                + safe(readValue(finding, "title"))
                + " "
                + safe(readValue(finding, "categoryLabel"))
                + " "
                + evidenceLines(finding).stream().limit(10).collect(Collectors.joining(" ")))
            .toLowerCase(Locale.ROOT);

    List<WebSolutionItem> output = new ArrayList<>();
    output.add(
        new WebSolutionItem(
            "Pinpoint first failing frame",
            "Local AI Engine",
            "Locate the first exception stack frame in application code, capture request context, and identify the exact failing dependency call.",
            ""));

    if (containsAny(context, "nullpointerexception", "null pointer", "cannot invoke", "is null")) {
      output.add(
          new WebSolutionItem(
              "Null-safety hardening",
              "Local AI Engine",
              "Add null guards at the failing object boundary, validate upstream payload fields, and add unit tests for null and empty variants.",
              ""));
    }

    if (containsAny(context, "timeout", "timed out", "sockettimeout", "connect timed out")) {
      output.add(
          new WebSolutionItem(
              "Timeout and dependency latency",
              "Local AI Engine",
              "Trace downstream latency for the same timestamp, tune client timeout/retry with exponential backoff, and verify connection pool saturation.",
              ""));
    }

    if (containsAny(context, "connection refused", "econnrefused", "host unreachable", "no route to host")) {
      output.add(
          new WebSolutionItem(
              "Connectivity and service availability",
              "Local AI Engine",
              "Confirm endpoint DNS/port reachability, service health, and firewall or security-group rules between caller and target service.",
              ""));
    }

    if (containsAny(context, "sqlstate", "ora-", "sql", "database", "deadlock", "constraint")) {
      output.add(
          new WebSolutionItem(
              "Database failure isolation",
              "Local AI Engine",
              "Capture exact SQL error code, validate connection pool and credentials, and inspect DB locks/indexes for the failed transaction window.",
              ""));
    }

    if (containsAny(context, "unauthorized", "forbidden", "401", "403", "token", "jwt", "auth")) {
      output.add(
          new WebSolutionItem(
              "Authentication and authorization checks",
              "Local AI Engine",
              "Verify token issuer/audience/expiry, check clock skew and key rotation, and confirm role-to-endpoint permission mapping.",
              ""));
    }

    if (containsAny(context, "outofmemory", "java heap space", "metaspace", "gc overhead", "oom")) {
      output.add(
          new WebSolutionItem(
              "Memory pressure remediation",
              "Local AI Engine",
              "Correlate heap growth with request pattern, cap in-memory batch size, and capture heap dump to identify retained objects.",
              ""));
    }

    if (containsAny(context, "ssl", "certificate", "handshake", "pkix", "sun.security.validator")) {
      output.add(
          new WebSolutionItem(
              "TLS certificate chain validation",
              "Local AI Engine",
              "Validate truststore/keystore chain, hostname SAN match, and certificate expiry/rotation for the target endpoint.",
              ""));
    }

    if (containsAny(context, "classnotfoundexception", "noclassdeffounderror", "nosuchmethoderror")) {
      output.add(
          new WebSolutionItem(
              "Dependency and classpath mismatch",
              "Local AI Engine",
              "Check runtime artifact versions against compile-time versions, inspect transitive dependency conflicts, and align deployment classpath.",
              ""));
    }

    if (containsAny(context, "filenotfound", "no such file", "path not found", "access denied")) {
      output.add(
          new WebSolutionItem(
              "File path and permission validation",
              "Local AI Engine",
              "Verify absolute file path, runtime user permissions, and mount/share availability for the host where the process runs.",
              ""));
    }

    output.add(
        new WebSolutionItem(
            "Safe rollout and verification",
            "Local AI Engine",
            "Apply fix in lower environment, replay representative traffic, and monitor error rate plus latency during controlled production rollout.",
            ""));

    output.add(
        new WebSolutionItem(
            "Configuration and dependency drift check",
            "Local AI Engine",
            "Compare runtime configuration and dependency versions across working and failing environments to isolate drift introduced by deployment.",
            ""));

    output.add(
        new WebSolutionItem(
            "Observability and alert validation",
            "Local AI Engine",
            "Add structured logs and counters around the failing path, then verify alerts track both recovery and relapse after fix deployment.",
            ""));

    output.add(
        new WebSolutionItem(
            "Rollback and containment plan",
            "Local AI Engine",
            "Prepare immediate rollback or feature-flag containment if error rate does not improve, and document incident timeline with remediation steps.",
            ""));

    return mergeUniqueSolutions(List.of(), output, maxSolutions);
  }

  private boolean containsAny(String value, String... needles) {
    String haystack = safe(value).toLowerCase(Locale.ROOT);
    if (!hasText(haystack)) {
      return false;
    }
    for (String needle : needles) {
      String token = safe(needle).toLowerCase(Locale.ROOT);
      if (!hasText(token)) {
        continue;
      }
      if (haystack.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private List<WebSolutionItem> searchGoogleSolutions(String query, int maxSolutions)
      throws IOException, InterruptedException {
    List<WebSolutionItem> fromCse = searchGoogleCustomSearch(query, maxSolutions);
    if (!fromCse.isEmpty()) {
      return fromCse;
    }
    return buildGoogleSearchLinks(query, maxSolutions);
  }

  private List<WebSolutionItem> searchGoogleCustomSearch(String query, int maxSolutions)
      throws IOException, InterruptedException {
    if (!hasText(settings.getGoogleApiKey()) || !hasText(settings.getGoogleCseCx())) {
      return List.of();
    }

    int pageSize = Math.min(Math.max(maxSolutions, 1), 10);
    String searchUrl =
        GOOGLE_CSE_API_URL
            + "?key="
            + urlEncode(settings.getGoogleApiKey())
            + "&cx="
            + urlEncode(settings.getGoogleCseCx())
            + "&q="
            + urlEncode(query)
            + "&num="
            + pageSize;

    JsonNode searchJson = httpGetJson(searchUrl);
    JsonNode items = searchJson.path("items");
    if (!items.isArray() || items.size() == 0) {
      return List.of();
    }

    List<WebSolutionItem> output = new ArrayList<>();
    for (JsonNode item : items) {
      String title = item.path("title").asText("Google result");
      String link = item.path("link").asText("");
      String snippet = item.path("snippet").asText("");
      String solution =
          hasText(snippet)
              ? snippet
              : "Open the linked result and validate whether the fix matches your stack trace.";
      output.add(new WebSolutionItem(title, "Google Search", solution, hasText(link) ? link : ""));
      if (output.size() >= maxSolutions) {
        break;
      }
    }
    return output;
  }

  private List<WebSolutionItem> buildGoogleSearchLinks(String query, int maxSolutions) {
    List<String> queryVariants = new ArrayList<>();
    queryVariants.add(query);
    queryVariants.add(query + " site:stackoverflow.com");
    queryVariants.add(query + " site:github.com");
    queryVariants.add(query + " \"fix\"");
    queryVariants.add(query + " \"root cause\"");

    List<WebSolutionItem> output = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (String variant : queryVariants) {
      String normalized = safe(variant).trim();
      if (!hasText(normalized)) {
        continue;
      }
      String key = normalized.toLowerCase(Locale.ROOT);
      if (!seen.add(key)) {
        continue;
      }
      String url = "https://www.google.com/search?q=" + urlEncode(normalized);
      String title = "Google: " + truncate(normalized, 96);
      String solution = "Open this Google query and review top matching fixes and issue discussions.";
      output.add(new WebSolutionItem(title, "Google Search", solution, url));
      if (output.size() >= maxSolutions) {
        break;
      }
    }
    return output;
  }

  private List<WebSolutionItem> searchWithChatgptWeb(
      String query, Map<String, Object> finding, int maxSolutions)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", settings.getChatgptWebSearchModel());
    payload.put("tools", List.of(Map.of("type", "web_search")));
    payload.put("temperature", 0.1);
    payload.put("max_output_tokens", 1400);

    List<Map<String, Object>> input = new ArrayList<>();
    input.add(
        Map.of(
            "role",
            "system",
            "content",
            "You are an SRE assistant. Use web search and return only valid JSON with practical fixes."));
    input.add(
        Map.of(
            "role",
            "user",
            "content",
            buildChatgptWebPrompt(query, finding, maxSolutions)));
    payload.put("input", input);

    String body = objectMapper.writeValueAsString(payload);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OPENAI_RESPONSES_API_URL))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getOpenAiApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(response.body(), 160);
      throw new IllegalStateException("ChatGPT web search failed (" + response.statusCode() + "). " + suffix);
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String outputText = extractResponseText(responseJson);
    JsonNode parsed = parseJsonFromText(outputText);

    if (parsed != null && parsed.isArray()) {
      return normalizeWebSolutionItems(parsed, "ChatGPT Web Search", maxSolutions);
    }
    JsonNode candidates = parsed == null ? null : firstNonNull(parsed.get("solutions"), parsed.get("items"));
    return normalizeWebSolutionItems(candidates, "ChatGPT Web Search", maxSolutions);
  }

  private List<WebSolutionItem> searchWithGeminiFree(
      String query, Map<String, Object> finding, int maxSolutions)
      throws IOException, InterruptedException {
    String endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/"
            + settings.getGeminiFreeModel()
            + ":generateContent?key="
            + urlEncode(settings.getGeminiApiKey());

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", "You are an SRE assistant."))));
    payload.put("generationConfig", Map.of("temperature", 0.1, "maxOutputTokens", 1400));
    payload.put(
        "contents",
        List.of(
            Map.of(
                "role",
                "user",
                "parts",
                List.of(Map.of("text", buildGenericLlmPrompt(query, finding, maxSolutions))))));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(response.body(), 160);
      throw new IllegalStateException("Gemini free search failed (" + response.statusCode() + "). " + suffix);
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String outputText = extractGeminiText(responseJson);
    JsonNode parsed = parseJsonFromText(outputText);
    if (parsed != null && parsed.isArray()) {
      return normalizeWebSolutionItems(parsed, "Gemini Free", maxSolutions);
    }
    JsonNode candidates = parsed == null ? null : firstNonNull(parsed.get("solutions"), parsed.get("items"));
    return normalizeWebSolutionItems(candidates, "Gemini Free", maxSolutions);
  }

  private List<WebSolutionItem> searchWithHuggingFace(
      String query, Map<String, Object> finding, int maxSolutions)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", settings.getHuggingFaceModel());
    payload.put("temperature", 0.1);
    payload.put("max_tokens", 1400);
    payload.put(
        "messages",
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are an SRE assistant. Return only valid JSON with concise, practical fixes."),
            Map.of("role", "user", "content", buildGenericLlmPrompt(query, finding, maxSolutions))));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(HUGGINGFACE_CHAT_COMPLETIONS_API_URL))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getHuggingFaceApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(response.body(), 160);
      throw new IllegalStateException(
          "Hugging Face search failed (" + response.statusCode() + "). " + suffix);
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String outputText = extractChatCompletionText(responseJson);
    JsonNode parsed = parseJsonFromText(outputText);
    if (parsed != null && parsed.isArray()) {
      return normalizeWebSolutionItems(parsed, "Hugging Face", maxSolutions);
    }
    JsonNode candidates = parsed == null ? null : firstNonNull(parsed.get("solutions"), parsed.get("items"));
    return normalizeWebSolutionItems(candidates, "Hugging Face", maxSolutions);
  }

  private List<WebSolutionItem> searchWithGroqFree(
      String query, Map<String, Object> finding, int maxSolutions)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", settings.getGroqFreeModel());
    payload.put("temperature", 0.1);
    payload.put("max_tokens", 1400);
    payload.put(
        "messages",
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are an SRE assistant. Return only valid JSON with concise, practical fixes."),
            Map.of("role", "user", "content", buildGenericLlmPrompt(query, finding, maxSolutions))));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(GROQ_CHAT_COMPLETIONS_API_URL))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getGroqApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(response.body(), 160);
      throw new IllegalStateException("Groq free search failed (" + response.statusCode() + "). " + suffix);
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String outputText = extractChatCompletionText(responseJson);
    JsonNode parsed = parseJsonFromText(outputText);
    if (parsed != null && parsed.isArray()) {
      return normalizeWebSolutionItems(parsed, "Groq Free", maxSolutions);
    }
    JsonNode candidates = parsed == null ? null : firstNonNull(parsed.get("solutions"), parsed.get("items"));
    return normalizeWebSolutionItems(candidates, "Groq Free", maxSolutions);
  }

  private List<WebSolutionItem> searchWithOpenRouterFree(
      String query, Map<String, Object> finding, int maxSolutions)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", settings.getOpenRouterFreeModel());
    payload.put("temperature", 0.1);
    payload.put("max_tokens", 1400);
    payload.put(
        "messages",
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are an SRE assistant. Return only valid JSON with concise, practical fixes."),
            Map.of("role", "user", "content", buildGenericLlmPrompt(query, finding, maxSolutions))));

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(OPENROUTER_CHAT_COMPLETIONS_API_URL))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getOpenRouterApiKey())
            .header("HTTP-Referer", "https://ailoganalyzer.local")
            .header("X-Title", "AILogAnalyzerJava")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(response.body(), 160);
      throw new IllegalStateException(
          "OpenRouter free search failed (" + response.statusCode() + "). " + suffix);
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String outputText = extractChatCompletionText(responseJson);
    JsonNode parsed = parseJsonFromText(outputText);
    if (parsed != null && parsed.isArray()) {
      return normalizeWebSolutionItems(parsed, "OpenRouter Free", maxSolutions);
    }
    JsonNode candidates = parsed == null ? null : firstNonNull(parsed.get("solutions"), parsed.get("items"));
    return normalizeWebSolutionItems(candidates, "OpenRouter Free", maxSolutions);
  }

  private List<WebSolutionItem> searchStackOverflowSolutions(String query, int maxSolutions)
      throws IOException, InterruptedException {
    int pageSize = Math.min(Math.max(maxSolutions * 2, 5), 15);
    String searchUrl =
        "https://api.stackexchange.com/2.3/search/advanced"
            + "?order=desc&sort=relevance&site=stackoverflow&accepted=True&answers=1"
            + "&pagesize="
            + pageSize
            + "&q="
            + urlEncode(query);

    JsonNode searchJson = httpGetJson(searchUrl);
    JsonNode questionsNode = searchJson.path("items");
    if (!questionsNode.isArray() || questionsNode.size() == 0) {
      return List.of();
    }

    Set<Integer> answerIds = new LinkedHashSet<>();
    for (JsonNode question : questionsNode) {
      JsonNode accepted = question.get("accepted_answer_id");
      if (accepted != null && accepted.canConvertToInt()) {
        answerIds.add(accepted.asInt());
      }
    }

    Map<Integer, String> answerById = new LinkedHashMap<>();
    if (!answerIds.isEmpty()) {
      String idList = answerIds.stream().map(String::valueOf).collect(Collectors.joining(";"));
      String answersUrl =
          "https://api.stackexchange.com/2.3/answers/"
              + idList
              + "?order=desc&sort=votes&site=stackoverflow&filter=withbody";
      JsonNode answersJson = httpGetJson(answersUrl);
      JsonNode answersNode = answersJson.path("items");
      if (answersNode.isArray()) {
        for (JsonNode answer : answersNode) {
          JsonNode idNode = answer.get("answer_id");
          if (idNode == null || !idNode.canConvertToInt()) {
            continue;
          }
          String text = htmlToPlainText(answer.path("body").asText(""));
          answerById.put(idNode.asInt(), text);
        }
      }
    }

    List<WebSolutionItem> output = new ArrayList<>();
    for (JsonNode question : questionsNode) {
      String title = decodeHtmlEntities(question.path("title").asText("Stack Overflow solution"));
      int acceptedId = question.path("accepted_answer_id").asInt(-1);
      String answerText = answerById.getOrDefault(acceptedId, "");
      String link = question.path("link").asText("");
      output.add(
          new WebSolutionItem(
              title, "Stack Overflow", extractActionableSnippet(answerText), hasText(link) ? link : ""));
      if (output.size() >= maxSolutions) {
        break;
      }
    }

    return output;
  }

  private List<WebSolutionItem> searchGithubIssueSolutions(String query, int maxSolutions)
      throws IOException, InterruptedException {
    int pageSize = Math.min(Math.max(maxSolutions * 4, 10), 30);
    String issueQuery = query + " in:title,body is:issue";
    String searchUrl =
        "https://api.github.com/search/issues"
            + "?q="
            + urlEncode(issueQuery)
            + "&sort=updated&order=desc&per_page="
            + pageSize;

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    headers.put("X-GitHub-Api-Version", "2022-11-28");
    headers.put("User-Agent", "AILogAnalyzerJava");
    if (hasText(settings.getGithubToken())) {
      headers.put("Authorization", "Bearer " + settings.getGithubToken());
    }

    JsonNode searchJson = httpGetJson(searchUrl, headers);
    JsonNode items = searchJson.path("items");
    if (!items.isArray() || items.size() == 0) {
      return List.of();
    }

    List<WebSolutionItem> output = new ArrayList<>();
    for (JsonNode item : items) {
      if (item.has("pull_request")) {
        continue;
      }

      String title = item.path("title").asText("GitHub issue");
      String link = item.path("html_url").asText("");
      String body = markdownToPlainText(item.path("body").asText(""));
      String solution = extractActionableSnippet(body);
      if (!hasText(solution)) {
        solution = "Review the linked GitHub issue discussion and apply the validated fix pattern.";
      }
      String source = "GitHub Issues";
      String repositoryUrl = item.path("repository_url").asText("");
      String repositoryRef = extractRepositoryRef(repositoryUrl);
      if (hasText(repositoryRef)) {
        source = "GitHub Issues (" + repositoryRef + ")";
      }

      output.add(new WebSolutionItem(title, source, solution, hasText(link) ? link : ""));
      if (output.size() >= maxSolutions) {
        break;
      }
    }

    return output;
  }

  private String extractRepositoryRef(String repositoryUrl) {
    String value = safe(repositoryUrl);
    if (!hasText(value)) {
      return "";
    }
    int slash = value.lastIndexOf('/');
    int ownerSlash = slash > 0 ? value.lastIndexOf('/', slash - 1) : -1;
    if (ownerSlash < 0 || slash <= ownerSlash) {
      return "";
    }
    return value.substring(ownerSlash + 1);
  }

  private JsonNode httpGetJson(String url) throws IOException, InterruptedException {
    return httpGetJson(url, Map.of());
  }

  private JsonNode httpGetJson(String url, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(25));
    for (Map.Entry<String, String> header : headers.entrySet()) {
      if (!hasText(header.getKey()) || !hasText(header.getValue())) {
        continue;
      }
      requestBuilder.header(header.getKey(), header.getValue());
    }
    HttpRequest request = requestBuilder.build();
    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("Web search request failed.");
    }
    return objectMapper.readTree(response.body());
  }

  private List<WebSolutionItem> normalizeWebSolutionItems(JsonNode items, String defaultSource, int maxSolutions) {
    if (items == null || !items.isArray()) {
      return List.of();
    }

    List<WebSolutionItem> output = new ArrayList<>();
    String providerSource = hasText(defaultSource) ? defaultSource.trim() : "Web";
    for (int i = 0; i < items.size(); i++) {
      JsonNode item = items.get(i);
      String title =
          firstNonBlank(
              readText(item, "title"),
              readText(item, "name"),
              "Solution " + (i + 1));
      String solution =
          firstNonBlank(
              readText(item, "solution"),
              readText(item, "resolution"),
              readText(item, "steps"),
              readText(item, "summary"));
      String modelSource = safe(readText(item, "source")).trim();
      String source = providerSource;
      if (hasText(modelSource) && !providerSource.equalsIgnoreCase(modelSource)) {
        source = providerSource + " | " + truncate(modelSource, 60);
      }
      String url = firstNonBlank(readText(item, "url"), readText(item, "link"), readText(item, "reference"), "");
      if (!hasText(title) || !hasText(solution)) {
        continue;
      }
      output.add(new WebSolutionItem(title, source, solution, url));
      if (output.size() >= maxSolutions) {
        break;
      }
    }
    return output;
  }

  private List<WebSolutionItem> mergeUniqueSolutions(
      List<WebSolutionItem> base, List<WebSolutionItem> incoming, int limit) {
    List<WebSolutionItem> merged = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    List<WebSolutionItem> source = new ArrayList<>();
    source.addAll(base);
    source.addAll(incoming);

    for (WebSolutionItem item : source) {
      if (merged.size() >= limit) {
        break;
      }
      String title = safe(item.getTitle());
      String solution = safe(item.getSolution());
      String url = safe(item.getUrl());
      if (!hasText(title) || !hasText(solution)) {
        continue;
      }
      String key =
          title.toLowerCase(Locale.ROOT)
              + "::"
              + url.toLowerCase(Locale.ROOT)
              + "::"
              + truncate(solution, 120).toLowerCase(Locale.ROOT);
      if (!seen.add(key)) {
        continue;
      }
      merged.add(
          new WebSolutionItem(
              title,
              hasText(item.getSource()) ? item.getSource() : "Web",
              solution,
              hasText(url) ? url : ""));
    }
    return merged;
  }

  private List<WebSolutionItem> buildFallbackSolutions(Map<String, Object> finding) {
    String resolution = safe(readValue(finding, "resolution"));
    return List.of(
        new WebSolutionItem(
            "Validate root cause",
            "Local analyzer",
            hasText(resolution)
                ? resolution
                : "Inspect the first exception occurrence and confirm which dependency is failing.",
            ""),
        new WebSolutionItem(
            "Correlate by request and timestamp",
            "Local analyzer",
            "Match the error timestamp with request IDs, dependency logs, and infrastructure events to isolate the trigger.",
            ""),
        new WebSolutionItem(
            "Patch and verify safely",
            "Local analyzer",
            "Apply fix in lower environments, add regression tests for the signature, and monitor error rates after rollout.",
            ""));
  }

  private String buildWebQueryFromFinding(Map<String, Object> finding) {
    List<String> candidates = new ArrayList<>();
    candidates.add(safe(readValue(finding, "title")));
    candidates.add(safe(readValue(finding, "categoryLabel")));
    candidates.add(firstEvidenceLine(finding));
    candidates.addAll(evidenceLines(finding).stream().limit(8).collect(Collectors.toList()));

    LinkedHashSet<String> extractedTerms = new LinkedHashSet<>();
    for (String candidate : candidates) {
      extractErrorTerms(candidate, extractedTerms);
      if (extractedTerms.size() >= 4) {
        break;
      }
    }

    if (!extractedTerms.isEmpty()) {
      List<String> prioritizedTerms = prioritizeExtractedTerms(extractedTerms);
      String query = prioritizedTerms.stream().limit(3).collect(Collectors.joining(" "));
      return truncate(query, 220);
    }

    String fallback = extractKeywordOnlyFallback(candidates);
    return truncate(fallback, 180);
  }

  private void extractErrorTerms(String text, Set<String> output) {
    String value = compactText(text);
    if (!hasText(value)) {
      return;
    }

    Matcher withMessageMatcher = EXCEPTION_WITH_MESSAGE_PATTERN.matcher(value);
    while (withMessageMatcher.find()) {
      String exceptionClass = withMessageMatcher.group(1);
      String message = normalizeExceptionMessage(withMessageMatcher.group(2));
      if (!hasText(exceptionClass) || !hasText(message)) {
        continue;
      }
      String term = exceptionClass + ": " + truncate(message, 72);
      output.add(term);
      if (output.size() >= 4) {
        return;
      }
    }

    Matcher classMatcher = EXCEPTION_CLASS_PATTERN.matcher(value);
    while (classMatcher.find()) {
      String exceptionClass = compactText(classMatcher.group(1));
      if (!hasText(exceptionClass)) {
        continue;
      }
      output.add(exceptionClass);
      if (output.size() >= 4) {
        return;
      }
    }

    Matcher codeMatcher = ERROR_CODE_PATTERN.matcher(value);
    while (codeMatcher.find()) {
      String code = compactText(codeMatcher.group());
      if (!hasText(code)) {
        continue;
      }
      output.add(code.toUpperCase(Locale.ROOT));
      if (output.size() >= 4) {
        return;
      }
    }
  }

  private String extractKeywordOnlyFallback(List<String> candidates) {
    for (String candidate : candidates) {
      String value = compactText(candidate);
      if (!hasText(value)) {
        continue;
      }
      Matcher matcher = ERROR_KEYWORD_PATTERN.matcher(value);
      if (!matcher.find()) {
        continue;
      }
      int start = Math.max(0, matcher.start() - 25);
      int end = Math.min(value.length(), matcher.end() + 95);
      return compactText(value.substring(start, end));
    }
    return "";
  }

  private List<String> prioritizeExtractedTerms(Set<String> extractedTerms) {
    List<String> prioritized = new ArrayList<>();
    Set<String> dedupeKeys = new LinkedHashSet<>();
    for (String term : extractedTerms) {
      String value = compactText(term);
      if (!hasText(value)) {
        continue;
      }
      String key = dedupeSignatureKey(value);
      if (!dedupeKeys.add(key)) {
        continue;
      }
      prioritized.add(value);
    }
    return prioritized;
  }

  private String dedupeSignatureKey(String term) {
    String value = compactText(term);
    int colonIndex = value.indexOf(':');
    if (colonIndex > 0) {
      String prefix = value.substring(0, colonIndex);
      if (EXCEPTION_CLASS_PATTERN.matcher(prefix).find()) {
        return prefix.toLowerCase(Locale.ROOT);
      }
    }
    if (EXCEPTION_CLASS_PATTERN.matcher(value).find()) {
      return value.toLowerCase(Locale.ROOT);
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private String normalizeExceptionMessage(String rawMessage) {
    String value = compactText(rawMessage);
    if (!hasText(value)) {
      return "";
    }

    // Keep query stable by masking volatile values and reducing long free-text tails.
    String normalized =
        value
            .replaceAll("\"[^\"]{1,80}\"", "\"...\"")
            .replaceAll("\\b\\d{2,}\\b", "#")
            .replaceAll("\\s+", " ")
            .trim();

    String[] words = normalized.split("\\s+");
    if (words.length <= 12) {
      return truncate(normalized, 72);
    }

    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < words.length && i < 12; i++) {
      if (i > 0) {
        builder.append(' ');
      }
      builder.append(words[i]);
    }
    return truncate(builder.toString(), 72);
  }

  private String compactText(String value) {
    return safe(value).replaceAll("\\s+", " ").trim();
  }

  private String buildChatgptWebPrompt(String query, Map<String, Object> finding, int maxSolutions) {
    List<String> evidence = evidenceLines(finding).stream().limit(5).collect(Collectors.toList());
    StringBuilder builder = new StringBuilder();
    builder.append("Find practical, working resolutions for this production log issue using web search.\n");
    builder.append("Issue query: ").append(query).append('\n');
    appendIfText(builder, "Issue title: ", readValue(finding, "title"));
    appendIfText(builder, "Category: ", readValue(finding, "categoryLabel"));
    appendIfText(builder, "Source file: ", readValue(finding, "sourceName"));
    appendIfText(builder, "Current local resolution: ", readValue(finding, "resolution"));
    if (!evidence.isEmpty()) {
      builder.append("Evidence lines:\n");
      for (String line : evidence) {
        builder.append("- ").append(line).append('\n');
      }
    }
    builder.append(
        "Return strictly valid JSON with this shape: {\"solutions\":[{\"title\":\"string\",\"solution\":\"string\",\"source\":\"string\",\"url\":\"string\"}]}\n");
    builder
        .append("Give up to ")
        .append(maxSolutions)
        .append(" unique solutions. Keep each solution concise and actionable.\n");
    builder.append("If a URL is unavailable, use an empty string for url.");
    return builder.toString();
  }

  private String buildGenericLlmPrompt(String query, Map<String, Object> finding, int maxSolutions) {
    List<String> evidence = evidenceLines(finding).stream().limit(5).collect(Collectors.toList());
    StringBuilder builder = new StringBuilder();
    builder.append("Provide practical resolutions for this production error signature.\n");
    builder.append("Issue query: ").append(query).append('\n');
    appendIfText(builder, "Issue title: ", readValue(finding, "title"));
    appendIfText(builder, "Category: ", readValue(finding, "categoryLabel"));
    appendIfText(builder, "Source file: ", readValue(finding, "sourceName"));
    appendIfText(builder, "Current local resolution: ", readValue(finding, "resolution"));
    if (!evidence.isEmpty()) {
      builder.append("Evidence lines:\n");
      for (String line : evidence) {
        builder.append("- ").append(line).append('\n');
      }
    }
    builder.append(
        "Return strictly valid JSON with this shape: {\"solutions\":[{\"title\":\"string\",\"solution\":\"string\",\"source\":\"string\",\"url\":\"string\"}]}\n");
    builder
        .append("Give up to ")
        .append(maxSolutions)
        .append(" unique solutions. Keep each solution concise and actionable.\n");
    builder.append("If a URL is unavailable, use an empty string for url.");
    return builder.toString();
  }

  private void appendIfText(StringBuilder builder, String prefix, String value) {
    if (hasText(value)) {
      builder.append(prefix).append(value.trim()).append('\n');
    }
  }

  private List<String> evidenceLines(Map<String, Object> finding) {
    Object evidence = finding.get("evidence");
    if (!(evidence instanceof List<?>)) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (Object line : (List<?>) evidence) {
      String value = line == null ? "" : line.toString().trim();
      if (hasText(value)) {
        lines.add(value);
      }
    }
    return lines;
  }

  private String firstEvidenceLine(Map<String, Object> finding) {
    List<String> lines = evidenceLines(finding);
    return lines.isEmpty() ? "" : lines.get(0);
  }

  private String extractResponseText(JsonNode responseJson) {
    if (responseJson == null || responseJson.isMissingNode()) {
      return "";
    }
    JsonNode outputText = responseJson.get("output_text");
    if (outputText != null && outputText.isTextual()) {
      return outputText.asText("").trim();
    }

    JsonNode output = responseJson.get("output");
    if (output == null || !output.isArray()) {
      return "";
    }

    StringBuilder text = new StringBuilder();
    for (JsonNode item : output) {
      JsonNode content = item.get("content");
      if (content == null || !content.isArray()) {
        continue;
      }
      for (JsonNode part : content) {
        String value =
            firstNonBlank(
                readText(part, "text"),
                readText(part, "output_text"),
                readText(part, "value"),
                readText(part.path("text"), "value"));
        if (hasText(value)) {
          if (text.length() > 0) {
            text.append('\n');
          }
          text.append(value.trim());
        }
      }
    }
    return text.toString().trim();
  }

  private String extractGeminiText(JsonNode responseJson) {
    if (responseJson == null || responseJson.isMissingNode()) {
      return "";
    }
    JsonNode candidates = responseJson.path("candidates");
    if (!candidates.isArray()) {
      return "";
    }

    StringBuilder text = new StringBuilder();
    for (JsonNode candidate : candidates) {
      JsonNode parts = candidate.path("content").path("parts");
      if (!parts.isArray()) {
        continue;
      }
      for (JsonNode part : parts) {
        String value = readText(part, "text");
        if (!hasText(value)) {
          continue;
        }
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(value.trim());
      }
    }
    return text.toString().trim();
  }

  private String extractChatCompletionText(JsonNode responseJson) {
    if (responseJson == null || responseJson.isMissingNode()) {
      return "";
    }

    JsonNode choices = responseJson.path("choices");
    if (!choices.isArray()) {
      return "";
    }

    StringBuilder text = new StringBuilder();
    for (JsonNode choice : choices) {
      JsonNode message = choice.path("message");
      String value = readText(message, "content");
      if (!hasText(value)) {
        JsonNode contentNode = message.get("content");
        if (contentNode != null && contentNode.isArray()) {
          for (JsonNode part : contentNode) {
            String partText = firstNonBlank(readText(part, "text"), readText(part.path("text"), "value"));
            if (!hasText(partText)) {
              continue;
            }
            if (text.length() > 0) {
              text.append('\n');
            }
            text.append(partText.trim());
          }
        }
        continue;
      }
      if (text.length() > 0) {
        text.append('\n');
      }
      text.append(value.trim());
    }
    return text.toString().trim();
  }

  private JsonNode parseJsonFromText(String rawText) {
    String text = safe(rawText).trim();
    if (!hasText(text)) {
      return null;
    }

    List<String> candidates = new ArrayList<>();
    candidates.add(text);

    Matcher fenced = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(text);
    if (fenced.find() && hasText(fenced.group(1))) {
      candidates.add(0, fenced.group(1).trim());
    }

    int objectStart = text.indexOf('{');
    int objectEnd = text.lastIndexOf('}');
    if (objectStart >= 0 && objectEnd > objectStart) {
      candidates.add(text.substring(objectStart, objectEnd + 1));
    }

    for (String candidate : candidates) {
      try {
        return objectMapper.readTree(candidate);
      } catch (Exception ignored) {
        // try next candidate
      }
    }
    return null;
  }

  private String extractActionableSnippet(String text) {
    String normalized = safe(text).trim();
    if (!hasText(normalized)) {
      return "Review the accepted answer and adapt the fix for your runtime configuration.";
    }

    String[] sentences = normalized.split("(?<=[.!?])\\s+");
    List<String> useful = new ArrayList<>();
    for (String sentence : sentences) {
      String candidate = sentence.trim();
      if (candidate.length() > 24) {
        useful.add(candidate);
      }
      if (useful.size() >= 2) {
        break;
      }
    }
    if (!useful.isEmpty()) {
      return String.join(" ", useful);
    }
    return truncate(normalized, 260);
  }

  private String markdownToPlainText(String markdown) {
    return decodeHtmlEntities(
        safe(markdown)
            .replaceAll("(?is)```[\\s\\S]*?```", " ")
            .replaceAll("(?is)`[^`]+`", " ")
            .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
            .replaceAll("(?m)^\\s{0,3}[-*+]\\s+", "")
            .replaceAll("(?m)^\\s{0,3}>\\s?", "")
            .replaceAll("\\s+", " ")
            .trim());
  }

  private String htmlToPlainText(String html) {
    String sanitized =
        safe(html)
            .replaceAll("(?is)<pre><code>[\\s\\S]*?</code></pre>", " ")
            .replaceAll("(?is)<code>[\\s\\S]*?</code>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .trim();
    return decodeHtmlEntities(sanitized);
  }

  private String decodeHtmlEntities(String value) {
    return safe(value)
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }

  private String readText(JsonNode node, String key) {
    if (node == null || node.isMissingNode() || !hasText(key)) {
      return "";
    }
    JsonNode value = node.get(key);
    return value != null && value.isValueNode() ? value.asText("") : "";
  }

  private String readValue(Map<String, Object> map, String key) {
    if (map == null || !map.containsKey(key) || map.get(key) == null) {
      return "";
    }
    return map.get(key).toString();
  }

  private JsonNode firstNonNull(JsonNode... candidates) {
    for (JsonNode candidate : candidates) {
      if (candidate != null && !candidate.isMissingNode() && !candidate.isNull()) {
        return candidate;
      }
    }
    return null;
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return "";
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private String truncate(String value, int maxChars) {
    String text = safe(value);
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(safe(value), StandardCharsets.UTF_8);
  }
}
