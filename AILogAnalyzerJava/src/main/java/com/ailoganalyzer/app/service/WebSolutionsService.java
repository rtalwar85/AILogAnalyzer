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

  private final AnalyzerSettings settings;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public WebSolutionsService(AnalyzerSettings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
  }

  public WebSolutionResponse findSolutions(Map<String, Object> finding, Integer requestedLimit) {
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

    if (hasText(settings.getOpenAiApiKey())) {
      try {
        List<WebSolutionItem> chatgpt = searchWithChatgptWeb(query, finding, limit);
        solutions = mergeUniqueSolutions(solutions, chatgpt, limit);
      } catch (Exception exception) {
        warnings.add(exception.getMessage() == null ? "ChatGPT web search failed." : exception.getMessage());
      }
    } else {
      warnings.add("Set OPENAI_API_KEY to enable ChatGPT web search results.");
    }

    if (solutions.size() < limit) {
      try {
        int remaining = Math.max(1, limit - solutions.size());
        List<WebSolutionItem> stackOverflow = searchStackOverflowSolutions(query, remaining);
        solutions = mergeUniqueSolutions(solutions, stackOverflow, limit);
      } catch (Exception exception) {
        warnings.add(exception.getMessage() == null ? "Stack Overflow search failed." : exception.getMessage());
      }
    }

    if (solutions.isEmpty()) {
      warnings.add("No web matches found for this issue. Showing local fallback guidance.");
      solutions = buildFallbackSolutions(finding).subList(0, Math.min(3, limit));
    }

    String warning = warnings.stream().filter(WebSolutionsService::hasText).collect(Collectors.joining(" "));
    return new WebSolutionResponse(query, warning, solutions);
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

  private JsonNode httpGetJson(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(25)).build();
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
      String source = firstNonBlank(readText(item, "source"), defaultSource, "Web");
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
    List<String> pieces = new ArrayList<>();
    pieces.add(safe(readValue(finding, "categoryLabel")));
    pieces.add(safe(readValue(finding, "title")));
    pieces.add(safe(readValue(finding, "sourceName")));
    pieces.add(firstEvidenceLine(finding));
    return truncate(
        pieces.stream().filter(WebSolutionsService::hasText).collect(Collectors.joining(" ")).trim(), 300);
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
