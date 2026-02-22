package com.ailoganalyzer.app.api;

import com.ailoganalyzer.app.agent.AgentRunService;
import com.ailoganalyzer.app.agent.model.AgentRun;
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
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  private final AgentRunService agentRunService;

  public ApiController(
      LogReadService logReadService,
      PathHistoryService pathHistoryService,
      WebSolutionsService webSolutionsService,
      AgentRunService agentRunService) {
    this.logReadService = logReadService;
    this.pathHistoryService = pathHistoryService;
    this.webSolutionsService = webSolutionsService;
    this.agentRunService = agentRunService;
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

  @GetMapping("/preferences")
  public ResponseEntity<Map<String, Object>> getPreferences() {
    Map<String, Object> preferences = pathHistoryService.readSearchPreferences();
    return ResponseEntity.ok(Map.of("preferences", preferences));
  }

  @PostMapping("/preferences")
  public ResponseEntity<?> savePreferences(@RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
      Map<String, Object> preferences = toObjectMap(payload.get("preferences"));
      if (preferences.isEmpty()) {
        preferences = new LinkedHashMap<>(payload);
      }
      Map<String, Object> saved = pathHistoryService.writeSearchPreferences(preferences);
      return ResponseEntity.ok(Map.of("preferences", saved));
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

  @PostMapping("/agent/runs")
  public ResponseEntity<?> createAgentRun(@RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
      String goal = payload.get("goal") == null ? "" : payload.get("goal").toString();
      List<String> paths = toStringList(payload.get("paths"));
      Map<String, Object> constraints = toObjectMap(payload.get("constraints"));
      AgentRun run = agentRunService.createRun(goal, paths, constraints);
      return ResponseEntity.ok(Map.of("run", run, "events", agentRunService.getEvents(run.getId())));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @GetMapping("/agent/capabilities")
  public ResponseEntity<?> getAgentCapabilities() {
    try {
      return ResponseEntity.ok(agentRunService.getCapabilities());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @GetMapping("/agent/runs/{runId}")
  public ResponseEntity<?> getAgentRun(@PathVariable("runId") String runId) {
    try {
      AgentRun run = agentRunService.getRun(runId);
      return ResponseEntity.ok(Map.of("run", run));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @GetMapping("/agent/runs/{runId}/events")
  public ResponseEntity<?> getAgentRunEvents(@PathVariable("runId") String runId) {
    try {
      return ResponseEntity.ok(Map.of("events", agentRunService.getEvents(runId)));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @PostMapping("/agent/runs/{runId}/steps/{stepId}/approve")
  public ResponseEntity<?> approveAgentStep(
      @PathVariable("runId") String runId,
      @PathVariable("stepId") String stepId,
      @RequestBody(required = false) Map<String, Object> body) {
    try {
      String note = toNote(body);
      AgentRun run = agentRunService.approveStep(runId, stepId, note);
      return ResponseEntity.ok(Map.of("run", run, "events", agentRunService.getEvents(run.getId())));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (IllegalStateException ex) {
      return badRequest(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @PostMapping("/agent/runs/{runId}/steps/{stepId}/reject")
  public ResponseEntity<?> rejectAgentStep(
      @PathVariable("runId") String runId,
      @PathVariable("stepId") String stepId,
      @RequestBody(required = false) Map<String, Object> body) {
    try {
      String note = toNote(body);
      AgentRun run = agentRunService.rejectStep(runId, stepId, note);
      return ResponseEntity.ok(Map.of("run", run, "events", agentRunService.getEvents(run.getId())));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (IllegalStateException ex) {
      return badRequest(ex.getMessage());
    } catch (Exception ex) {
      return serverError(ex.getMessage());
    }
  }

  @PostMapping("/agent/runs/{runId}/actions/{actionType}")
  public ResponseEntity<?> executeAgentAction(
      @PathVariable("runId") String runId,
      @PathVariable("actionType") String actionType,
      @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
      String note = toNote(body);
      String confirmationPhrase = toConfirmationPhrase(payload);
      Map<String, Object> actionResult =
          agentRunService.executePrivilegedAction(runId, actionType, confirmationPhrase, note);
      AgentRun run = agentRunService.getRun(runId);
      return ResponseEntity.ok(
          Map.of("run", run, "events", agentRunService.getEvents(run.getId()), "action", actionResult));
    } catch (NoSuchElementException ex) {
      return notFound(ex.getMessage());
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    } catch (IllegalStateException ex) {
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

  private ResponseEntity<Map<String, Object>> notFound(String message) {
    return ResponseEntity.status(404).body(Map.of("error", safeMessage(message)));
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

  private String toNote(Map<String, Object> body) {
    Map<String, Object> payload = body == null ? Collections.emptyMap() : body;
    Object note = payload.get("note");
    if (note == null) {
      note = payload.get("reason");
    }
    return note == null ? "" : note.toString();
  }

  private String toConfirmationPhrase(Map<String, Object> body) {
    Object confirmation =
        body == null ? null : body.getOrDefault("confirmationPhrase", body.get("confirmation"));
    return confirmation == null ? "" : confirmation.toString();
  }

  private String safeMessage(Exception exception) {
    return safeMessage(exception == null ? "" : exception.getMessage());
  }

  private String safeMessage(String value) {
    return value == null || value.trim().isEmpty() ? "Request failed." : value;
  }
}
