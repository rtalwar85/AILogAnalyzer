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
    Path configPath = settings.getConfigFilePath();
    if (!Files.exists(configPath)) {
      return Collections.emptyList();
    }

    try {
      Map<String, Object> json =
          objectMapper.readValue(configPath.toFile(), new TypeReference<Map<String, Object>>() {});
      Object raw = json.get("recentPaths");
      if (!(raw instanceof List)) {
        return Collections.emptyList();
      }

      List<String> values = new ArrayList<>();
      for (Object item : (List<?>) raw) {
        values.add(item == null ? "" : item.toString());
      }
      return normalizePathList(values, settings.getPathHistoryLimit());
    } catch (IOException ignored) {
      return Collections.emptyList();
    }
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

    Path configPath = settings.getConfigFilePath();
    try {
      if (configPath.getParent() != null) {
        Files.createDirectories(configPath.getParent());
      }
      Map<String, Object> payload =
          Map.of(
              "recentPaths", next,
              "updatedAt", Instant.now().toString());
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), payload);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to write path history config: " + exception.getMessage(), exception);
    }

    return next;
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
