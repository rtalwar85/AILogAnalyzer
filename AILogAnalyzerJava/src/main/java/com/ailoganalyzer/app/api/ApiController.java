package com.ailoganalyzer.app.api;

import com.ailoganalyzer.app.model.LogPayload;
import com.ailoganalyzer.app.model.WebSolutionResponse;
import com.ailoganalyzer.app.service.LogReadService;
import com.ailoganalyzer.app.service.PathHistoryService;
import com.ailoganalyzer.app.service.WebSolutionsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final LogReadService logReadService;
  private final PathHistoryService pathHistoryService;
  private final WebSolutionsService webSolutionsService;

  public ApiController(
      LogReadService logReadService,
      PathHistoryService pathHistoryService,
      WebSolutionsService webSolutionsService) {
    this.logReadService = logReadService;
    this.pathHistoryService = pathHistoryService;
    this.webSolutionsService = webSolutionsService;
  }

  @GetMapping("/logs")
  public ResponseEntity<?> readLogs(@RequestParam(name = "path", required = false) String path) {
    try {
      LogPayload payload = logReadService.readLogs(path);
      return ResponseEntity.ok(payload);
    } catch (LogReadService.BadRequestException ex) {
      return badRequest(ex.getMessage());
    } catch (LogReadService.PathNotAllowedException ex) {
      return forbidden(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @GetMapping(value = "/logs/raw", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> readRawLogs(@RequestParam(name = "path", required = false) String path) {
    try {
      return ResponseEntity.ok(logReadService.readRawLogs(path));
    } catch (LogReadService.BadRequestException ex) {
      return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    } catch (LogReadService.PathNotAllowedException ex) {
      return ResponseEntity.status(403).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    } catch (Exception ex) {
      return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body(safeMessage(ex));
    }
  }

  @GetMapping("/path-history")
  public ResponseEntity<Map<String, Object>> getPathHistory() {
    List<String> paths = pathHistoryService.readPaths();
    return ResponseEntity.ok(Map.of("paths", paths));
  }

  @PostMapping("/path-history")
  public ResponseEntity<?> savePathHistory(@RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
      boolean replace = toBoolean(payload.get("replace"));
      List<String> incomingPaths = toStringList(payload.get("paths"));
      List<String> saved = pathHistoryService.writePaths(incomingPaths, replace);
      return ResponseEntity.ok(Map.of("paths", saved));
    } catch (Exception ex) {
      return badRequest(ex.getMessage());
    }
  }

  @PostMapping("/web-solutions")
  public ResponseEntity<?> webSolutions(@RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
      Map<String, Object> finding = toObjectMap(payload.get("finding"));
      Integer limit = toInteger(payload.get("limit"));
      List<String> sourcePriority = toStringList(payload.get("sourcePriority"));
      WebSolutionResponse response = webSolutionsService.findSolutions(finding, limit, sourcePriority);
      return ResponseEntity.ok(response);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  private ResponseEntity<Map<String, Object>> badRequest(String message) {
    return ResponseEntity.badRequest().body(Map.of("error", safeMessage(message)));
  }

  private ResponseEntity<Map<String, Object>> forbidden(String message) {
    return ResponseEntity.status(403).body(Map.of("error", safeMessage(message)));
  }

  private ResponseEntity<Map<String, Object>> serverError(String message) {
    return ResponseEntity.status(500).body(Map.of("error", safeMessage(message)));
  }

  private Map<String, Object> toObjectMap(Object value) {
    if (value instanceof Map<?, ?>) {
      Map<String, Object> output = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        output.put(entry.getKey() == null ? "" : entry.getKey().toString(), entry.getValue());
      }
      return output;
    }
    return Collections.emptyMap();
  }

  private List<String> toStringList(Object value) {
    if (!(value instanceof List<?>)) {
      return List.of();
    }
    List<String> output = new ArrayList<>();
    for (Object item : (List<?>) value) {
      if (item != null) {
        output.add(item.toString());
      }
    }
    return output;
  }

  private Integer toInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (Exception ignored) {
      return null;
    }
  }

  private boolean toBoolean(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return "true".equalsIgnoreCase(value.toString().trim());
  }

  private String safeMessage(Exception exception) {
    return safeMessage(exception == null ? "" : exception.getMessage());
  }

  private String safeMessage(String value) {
    return value == null || value.trim().isEmpty() ? "Request failed." : value;
  }
}
