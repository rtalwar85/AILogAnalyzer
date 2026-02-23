package com.ailoganalyzer.app.service;

import com.ailoganalyzer.app.config.AnalyzerSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
public class AiSummaryService {
  private static final String OPENAI_CHAT_COMPLETIONS_API_URL =
      "https://api.openai.com/v1/chat/completions";
  private static final String DEFAULT_AI_SUMMARY_MODEL = "gpt-4o-mini";

  private final AnalyzerSettings settings;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public AiSummaryService(AnalyzerSettings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
  }

  public Map<String, Object> summarize(String logs, List<Map<String, Object>> findings) {
    String safeLogs = safe(logs);
    List<Map<String, Object>> safeFindings = findings == null ? List.of() : findings;
    if (!hasText(safeLogs) && safeFindings.isEmpty()) {
      throw new IllegalArgumentException("Missing logs/findings for AI summary.");
    }

    if (!hasText(settings.getOpenAiApiKey())) {
      return buildFallbackSummary(
          safeLogs, safeFindings, "Backend OPENAI_API_KEY is not configured. Using local summary.");
    }

    try {
      Map<String, Object> summary = requestOpenAiSummary(safeLogs, safeFindings);
      summary.put("provider", "OpenAI");
      summary.put("model", DEFAULT_AI_SUMMARY_MODEL);
      return summary;
    } catch (Exception ex) {
      return buildFallbackSummary(
          safeLogs,
          safeFindings,
          "OpenAI summary failed. Using local summary. " + safe(errorMessage(ex.getMessage())));
    }
  }

  private Map<String, Object> requestOpenAiSummary(String logs, List<Map<String, Object>> findings)
      throws IOException, InterruptedException {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", DEFAULT_AI_SUMMARY_MODEL);
    payload.put("response_format", Map.of("type", "json_object"));
    payload.put("temperature", 0.1d);
    payload.put(
        "messages",
        List.of(
            Map.of(
                "role",
                "system",
                "content",
                "You are an SRE incident assistant. Return JSON only with keys summary and top_causes."),
            Map.of("role", "user", "content", buildPrompt(logs, findings))));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(OPENAI_CHAT_COMPLETIONS_API_URL))
            .timeout(Duration.ofSeconds(35))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + settings.getOpenAiApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String suffix = truncate(compactText(response.body()), 220);
      throw new IllegalStateException(
          "OpenAI AI summary failed (" + response.statusCode() + "). " + safe(suffix));
    }

    JsonNode responseJson = objectMapper.readTree(response.body());
    String content = responseJson.path("choices").path(0).path("message").path("content").asText("");
    if (!hasText(content)) {
      throw new IllegalStateException("OpenAI returned empty summary content.");
    }

    JsonNode parsed = parseJsonFromText(content);
    if (parsed == null || !parsed.isObject()) {
      throw new IllegalStateException("OpenAI summary was not valid JSON.");
    }
    return normalizeSummaryPayload(parsed, "");
  }

  private Map<String, Object> normalizeSummaryPayload(JsonNode json, String warning) {
    String summary = compactText(json.path("summary").asText(""));
    List<Map<String, Object>> topCauses = new ArrayList<>();
    JsonNode causes = json.path("top_causes");
    if (causes.isArray()) {
      for (JsonNode item : causes) {
        String cause = compactText(item.path("cause").asText(""));
        String fix = compactText(item.path("fix").asText(""));
        String confidence = normalizeConfidence(item.path("confidence").asText(""));
        if (!hasText(cause) && !hasText(fix)) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cause", hasText(cause) ? cause : "Potential cause");
        row.put("fix", hasText(fix) ? fix : "Review logs and validate a targeted fix.");
        row.put("confidence", confidence);
        topCauses.add(row);
        if (topCauses.size() >= 6) {
          break;
        }
      }
    }

    if (!hasText(summary)) {
      summary = "AI summary completed, but no concise summary text was returned.";
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("summary", summary);
    output.put("top_causes", topCauses);
    if (hasText(warning)) {
      output.put("warning", warning);
    }
    return output;
  }

  private Map<String, Object> buildFallbackSummary(
      String logs, List<Map<String, Object>> findings, String warning) {
    List<Map<String, Object>> topCauses = new ArrayList<>();
    for (Map<String, Object> finding : findings) {
      String cause =
          firstNonBlank(
              readValue(finding, "classificationLabel"),
              readValue(finding, "categoryLabel"),
              readValue(finding, "title"),
              "Runtime issue");
      String fix =
          firstNonBlank(
              readValue(finding, "resolution"),
              "Review first occurrence, validate root cause, and apply the smallest safe fix.");
      String severity = safe(readValue(finding, "severity")).toLowerCase(Locale.ROOT);
      String confidence = "medium";
      if ("high".equals(severity)) {
        confidence = "high";
      } else if ("low".equals(severity)) {
        confidence = "low";
      }

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("cause", truncate(compactText(cause), 180));
      item.put("fix", truncate(compactText(fix), 240));
      item.put("confidence", confidence);
      topCauses.add(item);
      if (topCauses.size() >= 5) {
        break;
      }
    }

    if (topCauses.isEmpty()) {
      topCauses.add(
          Map.of(
              "cause",
              "Generic runtime issue pattern",
              "fix",
              "Capture a larger log window and group repeated signatures before remediation.",
              "confidence",
              "low"));
    }

    String summary =
        "Local summary generated from "
            + findings.size()
            + " detected finding(s). Review grouped signatures, validate the first failing timestamp, and apply a targeted fix with rollback plan.";
    if (!hasText(logs) && !findings.isEmpty()) {
      summary = "Local summary generated from findings only (log excerpt not provided).";
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("summary", summary);
    output.put("top_causes", topCauses);
    output.put("provider", "Local Fallback");
    output.put("model", "");
    if (hasText(warning)) {
      output.put("warning", warning);
    }
    return output;
  }

  private String buildPrompt(String logs, List<Map<String, Object>> findings) {
    List<String> findingLines = new ArrayList<>();
    for (Map<String, Object> finding : findings) {
      String title =
          firstNonBlank(
              readValue(finding, "classificationLabel"),
              readValue(finding, "categoryLabel"),
              readValue(finding, "title"),
              "Unknown issue");
      String resolution = readValue(finding, "resolution");
      String count = readValue(finding, "count");
      String severity = readValue(finding, "severity");
      StringBuilder line = new StringBuilder("- ");
      line.append(compactText(title));
      if (hasText(severity)) {
        line.append(" [severity=").append(severity).append("]");
      }
      if (hasText(count)) {
        line.append(" [occurrences=").append(count).append("]");
      }
      if (hasText(resolution)) {
        line.append(" -> ").append(truncate(compactText(resolution), 180));
      }
      findingLines.add(line.toString());
      if (findingLines.size() >= 12) {
        break;
      }
    }

    String findingsSection =
        findingLines.isEmpty() ? "No precomputed findings were provided." : String.join("\n", findingLines);

    return String.join(
        "\n",
        "Analyze these production logs/findings and return only strict JSON.",
        "Return shape: {\"summary\":\"...\",\"top_causes\":[{\"cause\":\"...\",\"fix\":\"...\",\"confidence\":\"high|medium|low\"}]}",
        "Keep summary concise and actionable for on-call engineers.",
        "",
        "Detected findings:",
        findingsSection,
        "",
        "Log excerpt:",
        truncate(logs, 12000));
  }

  private JsonNode parseJsonFromText(String rawText) {
    String text = safe(rawText).trim();
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
        // try next
      }
    }
    return null;
  }

  private String normalizeConfidence(String value) {
    String normalized = safe(value).trim().toLowerCase(Locale.ROOT);
    if ("high".equals(normalized) || "medium".equals(normalized) || "low".equals(normalized)) {
      return normalized;
    }
    return "medium";
  }

  private String readValue(Map<String, Object> map, String key) {
    if (map == null || key == null) {
      return "";
    }
    Object value = map.get(key);
    return value == null ? "" : safe(value.toString());
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return "";
  }

  private String compactText(String value) {
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

  private String errorMessage(String value) {
    String normalized = compactText(value);
    return hasText(normalized) ? normalized : "Unknown AI summary error.";
  }
}
