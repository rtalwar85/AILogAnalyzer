package com.ailoganalyzer.app.service;

import com.ailoganalyzer.app.config.AnalyzerSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PathHistoryService {
  private final AnalyzerSettings settings;
  private final ObjectMapper objectMapper;

  public PathHistoryService(AnalyzerSettings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
  }

  public synchronized List<String> readPaths() {
    Map<String, Object> json = readConfigPayload();
    Object raw = json.get("recentPaths");
    if (!(raw instanceof List)) {
      return Collections.emptyList();
    }
    List<String> values = new ArrayList<>();
    for (Object item : (List<?>) raw) {
      values.add(item == null ? "" : item.toString());
    }
    return normalizePathList(values, settings.getPathHistoryLimit());
  }

  public synchronized List<String> writePaths(List<String> incomingPaths, boolean replace) {
    List<String> incoming = normalizePathList(incomingPaths, settings.getPathHistoryLimit());
    List<String> next;
    if (replace) {
      next = incoming;
    } else {
      List<String> existing = readPaths();
      List<String> merged = new ArrayList<>(incoming);
      merged.addAll(existing);
      next = normalizePathList(merged, settings.getPathHistoryLimit());
    }

    Map<String, Object> payload = readConfigPayload();
    payload.put("recentPaths", next);
    payload.put("updatedAt", Instant.now().toString());
    writeConfigPayload(payload);

    return next;
  }

  public synchronized Map<String, Object> readSearchPreferences() {
    Map<String, Object> payload = readConfigPayload();
    Object raw = payload.get("searchPreferences");
    if (!(raw instanceof Map<?, ?>)) {
      return Collections.emptyMap();
    }
    return normalizeSearchPreferences((Map<?, ?>) raw);
  }

  public synchronized Map<String, Object> writeSearchPreferences(Map<String, Object> preferences) {
    Map<String, Object> normalized = normalizeSearchPreferences(preferences);
    Map<String, Object> payload = readConfigPayload();
    payload.put("searchPreferences", normalized);
    payload.put("updatedAt", Instant.now().toString());
    writeConfigPayload(payload);
    return normalized;
  }

  private Map<String, Object> readConfigPayload() {
    Path configPath = settings.getConfigFilePath();
    if (!Files.exists(configPath)) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, Object> json =
          objectMapper.readValue(configPath.toFile(), new TypeReference<Map<String, Object>>() {});
      if (json == null) {
        return new LinkedHashMap<>();
      }
      return new LinkedHashMap<>(json);
    } catch (IOException ignored) {
      return new LinkedHashMap<>();
    }
  }

  private void writeConfigPayload(Map<String, Object> payload) {
    Path configPath = settings.getConfigFilePath();
    try {
      if (configPath.getParent() != null) {
        Files.createDirectories(configPath.getParent());
      }
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), payload);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to write path history config: " + exception.getMessage(), exception);
    }
  }

  private Map<String, Object> normalizeSearchPreferences(Map<?, ?> input) {
    Map<String, Object> normalized = new LinkedHashMap<>();
    Map<?, ?> source = input == null ? Collections.emptyMap() : input;

    putTrimmed(normalized, "dateFrom", source.get("dateFrom"), 16);
    putTrimmed(normalized, "dateTo", source.get("dateTo"), 16);
    putTrimmed(normalized, "timeFrom", source.get("timeFrom"), 16);
    putTrimmed(normalized, "timeTo", source.get("timeTo"), 16);
    putTrimmedOrDefault(normalized, "problemType", source.get("problemType"), 120, "all");

    normalized.put("searchCaseSensitive", toBoolean(source.get("searchCaseSensitive"), false));
    normalized.put("autoRead", toBoolean(source.get("autoRead"), false));
    normalized.put("pollSeconds", clamp(toInteger(source.get("pollSeconds"), 30), 1, 3600));
    normalized.put(
        "webSourcePriority",
        normalizeStringList(source.get("webSourcePriority"), 12, 40));

    return normalized;
  }

  private List<String> normalizeStringList(Object value, int maxItems, int maxCharsPerItem) {
    if (!(value instanceof List<?>)) {
      return List.of();
    }
    List<String> output = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int safeLimit = Math.max(1, maxItems);
    int safeChars = Math.max(1, maxCharsPerItem);
    for (Object item : (List<?>) value) {
      String token = item == null ? "" : item.toString().trim().toLowerCase(Locale.ROOT);
      if (!hasText(token)) {
        continue;
      }
      if (token.length() > safeChars) {
        token = token.substring(0, safeChars);
      }
      if (!seen.add(token)) {
        continue;
      }
      output.add(token);
      if (output.size() >= safeLimit) {
        break;
      }
    }
    return output;
  }

  private void putTrimmed(Map<String, Object> output, String key, Object value, int maxChars) {
    String normalized = value == null ? "" : value.toString().trim();
    int safeMaxChars = Math.max(1, maxChars);
    if (normalized.length() > safeMaxChars) {
      normalized = normalized.substring(0, safeMaxChars);
    }
    output.put(key, normalized);
  }

  private void putTrimmedOrDefault(
      Map<String, Object> output, String key, Object value, int maxChars, String fallback) {
    String normalized = value == null ? "" : value.toString().trim();
    if (!hasText(normalized)) {
      output.put(key, fallback);
      return;
    }
    int safeMaxChars = Math.max(1, maxChars);
    if (normalized.length() > safeMaxChars) {
      normalized = normalized.substring(0, safeMaxChars);
    }
    output.put(key, normalized);
  }

  private static int toInteger(Object value, int fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static boolean toBoolean(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
    if ("true".equals(normalized)) {
      return true;
    }
    if ("false".equals(normalized)) {
      return false;
    }
    return fallback;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  public static List<String> normalizePathList(List<String> paths, int limit) {
    if (paths == null || paths.isEmpty()) {
      return Collections.emptyList();
    }

    List<String> output = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    int safeLimit = Math.max(1, limit);

    for (String raw : paths) {
      String normalized = normalizeInputPath(raw);
      if (!hasText(normalized)) {
        continue;
      }
      String dedupeKey = normalized.toLowerCase(Locale.ROOT);
      if (!seen.add(dedupeKey)) {
        continue;
      }
      output.add(normalized);
      if (output.size() >= safeLimit) {
        break;
      }
    }
    return output;
  }

  public static String normalizeInputPath(String rawPath) {
    String value = rawPath == null ? "" : rawPath.trim();
    if (!hasText(value)) {
      return "";
    }
    boolean quotedWithDouble = value.startsWith("\"") && value.endsWith("\"");
    boolean quotedWithSingle = value.startsWith("'") && value.endsWith("'");
    if (quotedWithDouble || quotedWithSingle) {
      value = value.substring(1, value.length() - 1).trim();
    }
    return value;
  }

  private static boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
