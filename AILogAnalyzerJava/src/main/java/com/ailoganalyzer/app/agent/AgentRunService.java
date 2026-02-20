package com.ailoganalyzer.app.agent;

import com.ailoganalyzer.app.agent.model.AgentEvent;
import com.ailoganalyzer.app.agent.model.AgentRiskLevel;
import com.ailoganalyzer.app.agent.model.AgentRun;
import com.ailoganalyzer.app.agent.model.AgentRunStatus;
import com.ailoganalyzer.app.agent.model.AgentStep;
import com.ailoganalyzer.app.agent.model.AgentStepStatus;
import com.ailoganalyzer.app.model.LogPayload;
import com.ailoganalyzer.app.service.LogReadService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService {
  private static final int MAX_EVENT_HISTORY = 500;
  private static final int MAX_PATH_SAMPLE = 5;

  private final LogReadService logReadService;
  private final int maxSteps;
  private final boolean approvalRequiredForRisky;
  private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
  private final Map<String, List<AgentEvent>> eventsByRun = new ConcurrentHashMap<>();

  public AgentRunService(
      LogReadService logReadService,
      @Value("${agent.max-steps:30}") int maxSteps,
      @Value("${agent.require-approval-for-risky:true}") boolean approvalRequiredForRisky) {
    this.logReadService = logReadService;
    this.maxSteps = Math.max(1, maxSteps);
    this.approvalRequiredForRisky = approvalRequiredForRisky;
  }

  public synchronized AgentRun createRun(
      String goal, List<String> requestedPaths, Map<String, Object> constraints) {
    String normalizedGoal = safeTrim(goal);
    if (normalizedGoal.isEmpty()) {
      throw new IllegalArgumentException("Goal is required to start an agent run.");
    }

    AgentRun run = new AgentRun();
    Instant now = Instant.now();
    run.setId(UUID.randomUUID().toString());
    run.setGoal(normalizedGoal);
    run.setStatus(AgentRunStatus.QUEUED.name());
    run.setSummary("Run queued.");
    run.setConfidence(0.0d);
    run.setCreatedAt(now);
    run.setUpdatedAt(now);
    run.setPaths(normalizePaths(requestedPaths));
    run.setConstraints(buildConstraints(constraints));

    List<AgentStep> steps = buildInitialSteps(run);
    if (steps.size() > maxSteps) {
      throw new IllegalStateException("Planned steps exceed configured max: " + maxSteps);
    }
    run.setSteps(steps);

    runs.put(run.getId(), run);
    eventsByRun.put(run.getId(), new ArrayList<>());

    addEvent(
        run.getId(),
        null,
        "RUN_CREATED",
        "Agent run created.",
        payload("goal", run.getGoal(), "paths", run.getPaths(), "constraints", run.getConstraints()));

    setRunStatus(run, AgentRunStatus.PLANNING, "Initial supervised plan generated.");
    addEvent(
        run.getId(),
        null,
        "PLAN_READY",
        "Plan prepared with strict no start/deploy automation policy.",
        payload("stepCount", run.getSteps().size()));

    executeUntilPause(run);
    return run;
  }

  public synchronized AgentRun getRun(String runId) {
    return getRunOrThrow(runId);
  }

  public synchronized List<AgentEvent> getEvents(String runId) {
    AgentRun run = getRunOrThrow(runId);
    List<AgentEvent> events = eventsByRun.get(run.getId());
    return events == null ? List.of() : new ArrayList<>(events);
  }

  public synchronized AgentRun approveStep(String runId, String stepId, String note) {
    AgentRun run = getRunOrThrow(runId);
    AgentStep step = getStepOrThrow(run, stepId);
    if (!AgentStepStatus.AWAITING_APPROVAL.name().equals(step.getStatus())) {
      throw new IllegalStateException("Step is not awaiting approval.");
    }

    setStepStatus(step, AgentStepStatus.COMPLETED);
    step.setSummary(
        "User approved the step. Skeleton mode records decision only; no start/deploy action was executed.");
    step.setOutput(
        payload(
            "decision",
            "approved",
            "note",
            safeTrim(note),
            "executionPolicy",
            "No restart/deploy/destructive actions are executed in Step 1 skeleton."));

    addEvent(
        run.getId(),
        step.getId(),
        "STEP_APPROVED",
        "Approval received for step: " + step.getTitle(),
        payload("stepId", step.getId(), "title", step.getTitle()));

    setRunStatus(run, AgentRunStatus.RUNNING, "Continuing supervised execution after approval.");
    executeUntilPause(run);
    return run;
  }

  public synchronized AgentRun rejectStep(String runId, String stepId, String note) {
    AgentRun run = getRunOrThrow(runId);
    AgentStep step = getStepOrThrow(run, stepId);
    if (!AgentStepStatus.AWAITING_APPROVAL.name().equals(step.getStatus())) {
      throw new IllegalStateException("Step is not awaiting approval.");
    }

    setStepStatus(step, AgentStepStatus.REJECTED);
    step.setSummary("User rejected the step. Continuing with safe verification only.");
    step.setOutput(
        payload(
            "decision",
            "rejected",
            "note",
            safeTrim(note),
            "executionPolicy",
            "Rejected step was not executed."));

    addEvent(
        run.getId(),
        step.getId(),
        "STEP_REJECTED",
        "Step rejected by user: " + step.getTitle(),
        payload("stepId", step.getId(), "title", step.getTitle()));

    setRunStatus(run, AgentRunStatus.RUNNING, "Continuing with safe verification after rejection.");
    executeUntilPause(run);
    return run;
  }

  private void executeUntilPause(AgentRun run) {
    while (true) {
      if (AgentRunStatus.FAILED.name().equals(run.getStatus())
          || AgentRunStatus.CANCELLED.name().equals(run.getStatus())
          || AgentRunStatus.COMPLETED.name().equals(run.getStatus())) {
        return;
      }

      AgentStep next = findNextPendingStep(run);
      if (next == null) {
        finalizeRun(run);
        return;
      }

      if (next.isRequiresApproval() && approvalRequiredForRisky) {
        if (!AgentStepStatus.AWAITING_APPROVAL.name().equals(next.getStatus())) {
          setStepStatus(next, AgentStepStatus.AWAITING_APPROVAL);
          setRunStatus(
              run,
              AgentRunStatus.AWAITING_APPROVAL,
              "Awaiting user approval for step: " + safeTrim(next.getTitle()));
          addEvent(
              run.getId(),
              next.getId(),
              "STEP_AWAITING_APPROVAL",
              "Approval required before proceeding: " + next.getTitle(),
              payload("stepId", next.getId(), "riskLevel", next.getRiskLevel()));
        }
        return;
      }

      executeSafeStep(run, next);
    }
  }

  private void executeSafeStep(AgentRun run, AgentStep step) {
    setRunStatus(run, AgentRunStatus.RUNNING, "Executing safe step: " + step.getTitle());
    setStepStatus(step, AgentStepStatus.RUNNING);
    addEvent(
        run.getId(),
        step.getId(),
        "STEP_STARTED",
        "Step started: " + step.getTitle(),
        payload("toolName", step.getToolName()));

    try {
      Map<String, Object> output;
      String summary;

      if ("collect_log_evidence".equals(step.getToolName())) {
        output = collectLogEvidence(run);
        summary =
            "Collected log evidence from "
                + asInt(output.get("readablePaths"))
                + "/"
                + asInt(output.get("totalPaths"))
                + " configured paths.";
      } else if ("classify_issue_candidates".equals(step.getToolName())) {
        output = classifyIssueCandidates(run);
        summary = "Generated issue classification hints from goal and evidence.";
      } else if ("verify_post_action_health".equals(step.getToolName())) {
        setRunStatus(run, AgentRunStatus.VERIFYING, "Running supervised verification checks.");
        output = verifyHealth(run);
        summary = "Verification completed in supervised no-side-effect mode.";
      } else {
        output = payload("note", "No executor registered for tool: " + step.getToolName());
        summary = "Step completed with placeholder output.";
      }

      step.setOutput(output);
      step.setSummary(summary);
      step.setError("");
      setStepStatus(step, AgentStepStatus.COMPLETED);

      addEvent(
          run.getId(),
          step.getId(),
          "STEP_COMPLETED",
          "Step completed: " + step.getTitle(),
          payload("summary", summary));
    } catch (Exception ex) {
      step.setError(safeMessage(ex.getMessage()));
      step.setSummary("Step failed. Manual review required.");
      setStepStatus(step, AgentStepStatus.FAILED);
      setRunStatus(run, AgentRunStatus.FAILED, "Run failed at step: " + step.getTitle());
      addEvent(
          run.getId(),
          step.getId(),
          "STEP_FAILED",
          "Step failed: " + step.getTitle(),
          payload("error", safeMessage(ex.getMessage())));
    }
  }

  private Map<String, Object> collectLogEvidence(AgentRun run) {
    Map<String, Object> output = new LinkedHashMap<>();
    List<String> paths = run.getPaths();
    List<Map<String, Object>> pathResults = new ArrayList<>();
    int readable = 0;
    int sampled = 0;

    if (paths.isEmpty()) {
      output.put("totalPaths", 0);
      output.put("sampledPaths", 0);
      output.put("readablePaths", 0);
      output.put("pathResults", pathResults);
      output.put(
          "note",
          "No explicit paths supplied. Agent run still proceeds using goal/context without side effects.");
      return output;
    }

    for (String path : paths) {
      if (sampled >= MAX_PATH_SAMPLE) {
        break;
      }
      sampled += 1;

      Map<String, Object> pathResult = new LinkedHashMap<>();
      pathResult.put("path", path);
      try {
        LogPayload payload = logReadService.readLogs(path);
        String content = payload.getContent() == null ? "" : payload.getContent();
        int lineCount = content.isEmpty() ? 0 : content.split("\\r?\\n").length;
        String preview = content.length() > 280 ? content.substring(0, 280) : content;
        pathResult.put("status", "readable");
        pathResult.put("mode", payload.getMode());
        pathResult.put("lineCount", lineCount);
        pathResult.put("preview", preview);
        readable += 1;
      } catch (Exception ex) {
        pathResult.put("status", "error");
        pathResult.put("error", safeMessage(ex.getMessage()));
      }
      pathResults.add(pathResult);
    }

    output.put("totalPaths", paths.size());
    output.put("sampledPaths", sampled);
    output.put("readablePaths", readable);
    output.put("pathResults", pathResults);
    output.put(
        "executionPolicy",
        "Read-only diagnostics only. No restart/deploy/destructive operation is allowed in skeleton mode.");
    return output;
  }

  private Map<String, Object> classifyIssueCandidates(AgentRun run) {
    String goal = safeTrim(run.getGoal()).toLowerCase();
    List<String> suggestedBuckets = new ArrayList<>();

    if (goal.contains("db2") || goal.contains("sqlstate") || goal.contains("jdbc")) {
      suggestedBuckets.add("DB2 / JDBC connectivity");
    }
    if (goal.contains("memory") || goal.contains("oom") || goal.contains("heap")) {
      suggestedBuckets.add("JVM memory");
    }
    if (goal.contains("timeout") || goal.contains("latency") || goal.contains("downstream")) {
      suggestedBuckets.add("Integration / outbound timeout");
    }
    if (goal.contains("ssl") || goal.contains("tls") || goal.contains("cert")) {
      suggestedBuckets.add("SSL/TLS / certs");
    }
    if (goal.contains("401")
        || goal.contains("403")
        || goal.contains("auth")
        || goal.contains("token")
        || goal.contains("sso")) {
      suggestedBuckets.add("Auth/security");
    }
    if (goal.contains("nullpointer")
        || goal.contains("classnotfound")
        || goal.contains("exception")
        || goal.contains("error")) {
      suggestedBuckets.add("App bug / NullPointer / ClassNotFound / config");
    }

    if (suggestedBuckets.isEmpty()) {
      suggestedBuckets.add("App bug / NullPointer / ClassNotFound / config");
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("goal", run.getGoal());
    output.put("suggestedBuckets", suggestedBuckets);
    output.put("confidenceHint", "medium");
    output.put(
        "note",
        "Classification is heuristic in Step 1. Use finding details and user approval before any remediation.");
    return output;
  }

  private Map<String, Object> verifyHealth(AgentRun run) {
    int completed = 0;
    int rejected = 0;
    int failed = 0;
    for (AgentStep step : run.getSteps()) {
      if (AgentStepStatus.COMPLETED.name().equals(step.getStatus())) {
        completed += 1;
      } else if (AgentStepStatus.REJECTED.name().equals(step.getStatus())) {
        rejected += 1;
      } else if (AgentStepStatus.FAILED.name().equals(step.getStatus())) {
        failed += 1;
      }
    }

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("completedSteps", completed);
    output.put("rejectedSteps", rejected);
    output.put("failedSteps", failed);
    output.put("verificationStatus", failed > 0 ? "manual_follow_up_required" : "supervised_completed");
    output.put(
        "safety",
        "No service restart, deployment, or destructive operation was executed automatically.");
    return output;
  }

  private void finalizeRun(AgentRun run) {
    if (AgentRunStatus.FAILED.name().equals(run.getStatus())
        || AgentRunStatus.CANCELLED.name().equals(run.getStatus())
        || AgentRunStatus.COMPLETED.name().equals(run.getStatus())) {
      return;
    }

    setRunStatus(
        run,
        AgentRunStatus.COMPLETED,
        "Supervised run completed. No restart/deploy actions were executed automatically.");
    run.setConfidence(calculateConfidence(run));
    addEvent(
        run.getId(),
        null,
        "RUN_COMPLETED",
        "Run completed in supervised mode.",
        payload("confidence", run.getConfidence(), "status", run.getStatus()));
  }

  private double calculateConfidence(AgentRun run) {
    double score = 0.45d;
    for (AgentStep step : run.getSteps()) {
      if (AgentStepStatus.COMPLETED.name().equals(step.getStatus())) {
        score += 0.12d;
      } else if (AgentStepStatus.REJECTED.name().equals(step.getStatus())) {
        score -= 0.05d;
      } else if (AgentStepStatus.FAILED.name().equals(step.getStatus())) {
        score -= 0.25d;
      }
    }
    if (score < 0.1d) {
      score = 0.1d;
    }
    if (score > 0.95d) {
      score = 0.95d;
    }
    return Math.round(score * 100.0d) / 100.0d;
  }

  private AgentRun getRunOrThrow(String runId) {
    String normalizedRunId = safeTrim(runId);
    AgentRun run = runs.get(normalizedRunId);
    if (run == null) {
      throw new NoSuchElementException("Agent run not found: " + normalizedRunId);
    }
    return run;
  }

  private AgentStep getStepOrThrow(AgentRun run, String stepId) {
    String normalizedStepId = safeTrim(stepId);
    for (AgentStep step : run.getSteps()) {
      if (normalizedStepId.equals(step.getId())) {
        return step;
      }
    }
    throw new NoSuchElementException("Agent step not found: " + normalizedStepId);
  }

  private AgentStep findNextPendingStep(AgentRun run) {
    for (AgentStep step : run.getSteps()) {
      if (AgentStepStatus.PENDING.name().equals(step.getStatus())) {
        return step;
      }
    }
    return null;
  }

  private List<AgentStep> buildInitialSteps(AgentRun run) {
    List<AgentStep> steps = new ArrayList<>();
    steps.add(
        newStep(
            "Collect log evidence",
            "collect_log_evidence",
            AgentRiskLevel.SAFE,
            false,
            payload("paths", run.getPaths(), "mode", "read-only")));
    steps.add(
        newStep(
            "Classify issue candidates",
            "classify_issue_candidates",
            AgentRiskLevel.SAFE,
            false,
            payload("goal", run.getGoal())));
    steps.add(
        newStep(
            "Propose supervised remediation options",
            "propose_supervised_actions",
            AgentRiskLevel.APPROVAL_REQUIRED,
            true,
            payload(
                "policy",
                "no_start_or_deploy_without_user_input",
                "automaticExecution",
                false)));
    steps.add(
        newStep(
            "Verify post-action health",
            "verify_post_action_health",
            AgentRiskLevel.SAFE,
            false,
            payload(
                "checks",
                List.of("error-rate-trend", "exception-signature-change", "path-read-success-rate"))));
    return steps;
  }

  private AgentStep newStep(
      String title,
      String toolName,
      AgentRiskLevel riskLevel,
      boolean requiresApproval,
      Map<String, Object> input) {
    AgentStep step = new AgentStep();
    Instant now = Instant.now();
    step.setId(UUID.randomUUID().toString());
    step.setTitle(title);
    step.setToolName(toolName);
    step.setRiskLevel(riskLevel.name());
    step.setRequiresApproval(requiresApproval);
    step.setStatus(AgentStepStatus.PENDING.name());
    step.setSummary("");
    step.setInput(input);
    step.setCreatedAt(now);
    step.setUpdatedAt(now);
    return step;
  }

  private Map<String, Object> buildConstraints(Map<String, Object> constraints) {
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("allow_start", false);
    output.put("allow_deploy", false);
    output.put("allow_destructive_actions", false);
    output.put("require_user_approval_for_risky_steps", approvalRequiredForRisky);
    if (constraints != null) {
      output.putAll(constraints);
    }
    return output;
  }

  private List<String> normalizePaths(List<String> paths) {
    Set<String> output = new LinkedHashSet<>();
    if (paths == null) {
      return new ArrayList<>(output);
    }
    for (String path : paths) {
      String normalized = safeTrim(path);
      if (normalized.isEmpty()) {
        continue;
      }
      output.add(normalized);
    }
    return new ArrayList<>(output);
  }

  private void setRunStatus(AgentRun run, AgentRunStatus status, String summary) {
    run.setStatus(status.name());
    run.setSummary(safeTrim(summary));
    run.setUpdatedAt(Instant.now());
  }

  private void setStepStatus(AgentStep step, AgentStepStatus status) {
    step.setStatus(status.name());
    step.setUpdatedAt(Instant.now());
  }

  private void addEvent(
      String runId, String stepId, String type, String message, Map<String, Object> payload) {
    List<AgentEvent> events = eventsByRun.computeIfAbsent(runId, key -> new ArrayList<>());
    AgentEvent event = new AgentEvent();
    event.setId(UUID.randomUUID().toString());
    event.setRunId(runId);
    event.setStepId(stepId);
    event.setType(type);
    event.setMessage(safeTrim(message));
    event.setTimestamp(Instant.now());
    event.setPayload(payload == null ? new LinkedHashMap<>() : payload);
    events.add(event);
    if (events.size() > MAX_EVENT_HISTORY) {
      int trimCount = events.size() - MAX_EVENT_HISTORY;
      events.subList(0, trimCount).clear();
    }
  }

  private String safeTrim(String value) {
    return value == null ? "" : value.trim();
  }

  private String safeMessage(String value) {
    String normalized = safeTrim(value);
    return normalized.isEmpty() ? "Request failed." : normalized;
  }

  private int asInt(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (Exception ignored) {
      return 0;
    }
  }

  private Map<String, Object> payload(Object... keyValues) {
    Map<String, Object> output = new LinkedHashMap<>();
    if (keyValues == null) {
      return output;
    }
    for (int index = 0; index + 1 < keyValues.length; index += 2) {
      Object key = keyValues[index];
      if (key == null) {
        continue;
      }
      output.put(key.toString(), keyValues[index + 1]);
    }
    return output;
  }
}
