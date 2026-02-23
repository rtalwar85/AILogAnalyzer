package com.ailoganalyzer.app.agent;

import com.ailoganalyzer.app.config.AnalyzerSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AgentLlmService {
  private static final String OPENAI_CHAT_COMPLETIONS_API_URL =
      "https://api.openai.com/v1/chat/completions";

  private final AnalyzerSettings settings;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AgentLlmService(AnalyzerSettings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
  }

  public boolean isLlmSupervisionEnabled() {
    return settings.isAgentLlmEnabled();
  }

  public boolean isLlmSupervisionActive() {
    return settings.isAgentLlmEnabled() && hasText(settings.getOpenAiApiKey());
  }

  public String getAgentLlmModel() {
    return hasText(settings.getAgentLlmModel())
        ? settings.getAgentLlmModel().trim()
        : AnalyzerSettings.DEFAULT_AGENT_LLM_MODEL;
  }

  public Map<String, Object> classifyIssueCandidates(
      String goal, Map<String, Object> evidenceOutput, List<String> heuristicBuckets)
      throws Exception {
    ensureAvailable();

    String prompt =
        String.join(
            "\n",
            "Classify likely incident categories for a supervised remediation agent.",
            "Return strict JSON only with keys:",
            "{\"suggestedBuckets\":[\"...\"],\"confidenceHint\":\"high|medium|low\",\"note\":\"...\"}",
            "Keep suggestedBuckets to 1-5 items using practical SRE categories.",
            "",
            "Goal:",
            truncate(safe(goal), 500),
            "",
            "Heuristic buckets:",
            String.join(", ", sanitizeStringList(heuristicBuckets, 6)),
            "",
            "Evidence summary:",
            buildEvidencePromptSection(evidenceOutput));

    JsonNode parsed =
        chatCompletionsJson(
            "You are an SRE incident triage assistant. Output JSON only. No prose outside JSON.",
            prompt,
            0.1d,
            700);

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("suggestedBuckets", sanitizeStringList(readJsonStringList(parsed, "suggestedBuckets"), 5));
    output.put("confidenceHint", normalizeConfidence(readJsonText(parsed, "confidenceHint")));
    output.put("note", compact(readJsonText(parsed, "note")));
    output.put("llmUsed", true);
    output.put("provider", "OpenAI");
    output.put("model", getAgentLlmModel());
    return output;
  }

  public Map<String, Object> draftRemediationPlanHints(
      String goal,
      String primaryBucket,
      List<String> suggestedBuckets,
      String evidenceSummary,
      List<String> readableSamplePaths,
      List<String> unreadablePathHints)
      throws Exception {
    ensureAvailable();

    String prompt =
        String.join(
            "\n",
            "Draft supervised remediation plan hints for an on-call incident assistant.",
            "IMPORTANT: This agent is plan-only; no automatic restart/deploy/destructive actions.",
            "Return strict JSON only with keys:",
            "{\"primaryCategory\":\"...\",\"targetedActions\":[\"...\"],\"successSignals\":[\"...\"],\"operatorNote\":\"...\",\"evidenceSummary\":\"...\"}",
            "Provide 3-5 targetedActions and 2-4 successSignals. Keep them concise and safe.",
            "",
            "Goal:",
            truncate(safe(goal), 600),
            "Primary category (heuristic): " + safe(primaryBucket),
            "Suggested categories: " + String.join(", ", sanitizeStringList(suggestedBuckets, 8)),
            "Evidence summary: " + truncate(safe(evidenceSummary), 400),
            "Readable sample paths: " + String.join(", ", sanitizeStringList(readableSamplePaths, 4)),
            "Unreadable path hints: " + String.join(", ", sanitizeStringList(unreadablePathHints, 4)));

    JsonNode parsed =
        chatCompletionsJson(
            "You are a cautious SRE planning assistant. Output JSON only and keep all guidance approval-gated.",
            prompt,
            0.15d,
            900);

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("primaryCategory", compact(readJsonText(parsed, "primaryCategory")));
    output.put("targetedActions", sanitizeStringList(readJsonStringList(parsed, "targetedActions"), 5));
    output.put("successSignals", sanitizeStringList(readJsonStringList(parsed, "successSignals"), 4));
    output.put("operatorNote", compact(readJsonText(parsed, "operatorNote")));
    output.put("evidenceSummary", compact(readJsonText(parsed, "evidenceSummary")));
    output.put("llmUsed", true);
    output.put("provider", "OpenAI");
    output.put("model", getAgentLlmModel());
    return output;
  }

  private void ensureAvailable() {
    if (!settings.isAgentLlmEnabled()) {
      throw new IllegalStateException("Agent LLM supervision is disabled by configuration.");
    }
    if (!hasText(settings.getOpenAiApiKey())) {
      throw new IllegalStateException("OPENAI_API_KEY is not configured for Agent LLM supervision.");
    }
  }

  private JsonNode chatCompletionsJson(String systemPrompt, String userPrompt, double temperature, int maxTokens)
      throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", getAgentLlmModel());
    payload.put("response_format", Map.of("type", "json_object"));
    payload.put("temperature", temperature);
    payload.put("max_tokens", Math.max(256, maxTokens));
    payload.put(
        "messages",
        List.of(
            Map.of("role", "system", "content", safe(systemPrompt)),
            Map.of("role", "user", "content", safe(userPrompt))));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(OPENAI_CHAT_COMPLETIONS_API_URL))
            .timeout(Duration.ofSeconds(35))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getOpenAiApiKey())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "Agent LLM request failed ("
              + response.statusCode()
              + "). "
              + truncate(compact(response.body()), 220));
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String content = responseJson.path("choices").path(0).path("message").path("content").asText("");
    if (!hasText(content)) {
      throw new IllegalStateException("Agent LLM returned empty content.");
    }

    JsonNode parsed = parseJsonFromText(content);
    if (parsed == null || !parsed.isObject()) {
      throw new IllegalStateException("Agent LLM returned invalid JSON.");
    }
    return parsed;
  }

  private String buildEvidencePromptSection(Map<String, Object> evidenceOutput) {
    if (evidenceOutput == null || evidenceOutput.isEmpty()) {
      return "No evidence output available.";
    }

    List<String> lines = new ArrayList<>();
    lines.add(
        "totalPaths="
            + asInt(evidenceOutput.get("totalPaths"))
            + ", sampledPaths="
            + asInt(evidenceOutput.get("sampledPaths"))
            + ", readablePaths="
            + asInt(evidenceOutput.get("readablePaths")));

    Object pathResultsRaw = evidenceOutput.get("pathResults");
    if (pathResultsRaw instanceof List<?>) {
      int count = 0;
      for (Object item : (List<?>) pathResultsRaw) {
        if (!(item instanceof Map<?, ?>)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> pathResult = (Map<Object, Object>) item;
        String path = compact(stringValue(pathResult.get("path")));
        String status = compact(stringValue(pathResult.get("status")));
        String mode = compact(stringValue(pathResult.get("mode")));
        String error = compact(stringValue(pathResult.get("error")));
        String preview = compact(stringValue(pathResult.get("preview")));
        StringBuilder line = new StringBuilder("- ");
        line.append(hasText(path) ? truncate(path, 140) : "(unknown path)");
        if (hasText(status)) {
          line.append(" status=").append(status);
        }
        if (hasText(mode)) {
          line.append(" mode=").append(mode);
        }
        if (hasText(error)) {
          line.append(" error=").append(truncate(error, 180));
        }
        if (hasText(preview)) {
          line.append(" preview=").append(truncate(preview, 220));
        }
        lines.add(line.toString());
        count += 1;
        if (count >= 5) {
          break;
        }
      }
    }
    return String.join("\n", lines);
  }

  private JsonNode parseJsonFromText(String raw) {
    String text = safe(raw).trim();
    if (!hasText(text)) {
      return null;
    }
    List<String> candidates = new ArrayList<>();
    candidates.add(text);
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

  private List<String> readJsonStringList(JsonNode node, String key) {
    List<String> out = new ArrayList<>();
    if (node == null || key == null) {
      return out;
    }
    JsonNode values = node.path(key);
    if (!values.isArray()) {
      return out;
    }
    for (JsonNode item : values) {
      String value = compact(item.asText(""));
      if (hasText(value)) {
        out.add(value);
      }
    }
    return out;
  }

  private String readJsonText(JsonNode node, String key) {
    if (node == null || key == null) {
      return "";
    }
    return safe(node.path(key).asText(""));
  }

  private List<String> sanitizeStringList(List<String> values, int maxItems) {
    List<String> out = new ArrayList<>();
    if (values == null) {
      return out;
    }
    for (String value : values) {
      String item = compact(value);
      if (!hasText(item)) {
        continue;
      }
      if (out.contains(item)) {
        continue;
      }
      out.add(item);
      if (out.size() >= Math.max(1, maxItems)) {
        break;
      }
    }
    return out;
  }

  private String normalizeConfidence(String value) {
    String normalized = safe(value).trim().toLowerCase(Locale.ROOT);
    if ("high".equals(normalized) || "medium".equals(normalized) || "low".equals(normalized)) {
      return normalized;
    }
    return "medium";
  }

  private int asInt(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(stringValue(value).trim());
    } catch (Exception ignored) {
      return 0;
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private String compact(String value) {
    return safe(value).replaceAll("\\s+", " ").trim();
  }

  private String truncate(String value, int maxChars) {
    String text = safe(value);
    int limit = Math.max(1, maxChars);
    if (text.length() <= limit) {
      return text;
    }
    return text.substring(0, Math.max(1, limit - 3)) + "...";
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
