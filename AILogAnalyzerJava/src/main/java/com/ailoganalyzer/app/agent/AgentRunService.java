package com.ailoganalyzer.app.agent;

import com.ailoganalyzer.app.agent.model.AgentEvent;
import com.ailoganalyzer.app.agent.model.AgentRiskLevel;
import com.ailoganalyzer.app.agent.model.AgentRun;
import com.ailoganalyzer.app.agent.model.AgentRunStatus;
import com.ailoganalyzer.app.agent.model.AgentStep;
import com.ailoganalyzer.app.agent.model.AgentStepStatus;
import com.ailoganalyzer.app.model.LogPayload;
import com.ailoganalyzer.app.service.LogReadService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentRunService {
  private static final int MAX_EVENT_HISTORY = 500;
  private static final int MAX_PATH_SAMPLE = 5;
  private static final int MAX_COMMAND_OUTPUT_CHARS = 12000;
  private static final Pattern TARGET_FROM_GOAL_PATTERN =
      Pattern.compile("(?i)\\bfor\\s+([a-z0-9][a-z0-9._-]{0,252})\\b");
  private static final Pattern SAFE_TARGET_PATTERN =
      Pattern.compile("^[a-z0-9][a-z0-9._-]{0,252}$", Pattern.CASE_INSENSITIVE);

  private final AgentLlmService agentLlmService;
  private final LogReadService logReadService;
  private final int maxSteps;
  private final boolean approvalRequiredForRisky;
  private final boolean privilegedActionsEnabled;
  private final String privilegedActionsConfirmationPhrase;
  private final long privilegedActionTimeoutSeconds;
  private final Map<String, String> privilegedActionCommands = new LinkedHashMap<>();
  private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
  private final Map<String, List<AgentEvent>> eventsByRun = new ConcurrentHashMap<>();

  public AgentRunService(
      AgentLlmService agentLlmService,
      LogReadService logReadService,
      @Value("${agent.max-steps:30}") int maxSteps,
      @Value("${agent.require-approval-for-risky:true}") boolean approvalRequiredForRisky,
      @Value("${agent.privileged-actions.enabled:false}") boolean privilegedActionsEnabled,
      @Value("${agent.privileged-actions.confirmation-phrase:ENABLE_AGENT_ACTIONS}")
          String privilegedActionsConfirmationPhrase,
      @Value("${agent.privileged-actions.restart-command:}") String restartCommand,
      @Value("${agent.privileged-actions.deploy-command:}") String deployCommand,
      @Value("${agent.privileged-actions.code-change-command:}") String codeChangeCommand,
      @Value("${agent.privileged-actions.timeout-seconds:600}") long privilegedActionTimeoutSeconds) {
    this.agentLlmService = agentLlmService;
    this.logReadService = logReadService;
    this.maxSteps = Math.max(1, maxSteps);
    this.approvalRequiredForRisky = approvalRequiredForRisky;
    this.privilegedActionsEnabled = privilegedActionsEnabled;
    this.privilegedActionsConfirmationPhrase = safeTrim(privilegedActionsConfirmationPhrase);
    this.privilegedActionTimeoutSeconds = Math.max(5L, privilegedActionTimeoutSeconds);
    privilegedActionCommands.put("restart_server", safeTrim(restartCommand));
    privilegedActionCommands.put("deploy", safeTrim(deployCommand));
    privilegedActionCommands.put("code_change", safeTrim(codeChangeCommand));
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
    Map<String, Object> builtConstraints = buildConstraints(constraints);
    String extractedTarget = extractTargetFromGoal(normalizedGoal);
    if (!extractedTarget.isEmpty()) {
      builtConstraints.put("extracted_target", extractedTarget);
      builtConstraints.put("target_source", "goal_prompt_for_clause");
    }
    run.setConstraints(builtConstraints);

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

  public synchronized Map<String, Object> getCapabilities() {
    Map<String, Object> actions = new LinkedHashMap<>();
    actions.put("restart_server", privilegedActionsEnabled && hasConfiguredActionCommand("restart_server"));
    actions.put("deploy", privilegedActionsEnabled && hasConfiguredActionCommand("deploy"));
    actions.put("code_change", privilegedActionsEnabled && hasConfiguredActionCommand("code_change"));

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("privilegedActionsEnabled", privilegedActionsEnabled);
    output.put("confirmationPhrase", privilegedActionsConfirmationPhrase);
    output.put("timeoutSeconds", privilegedActionTimeoutSeconds);
    output.put("actions", actions);
    output.put(
        "policyNotice",
        privilegedActionsEnabled
            ? "Privileged actions require explicit user confirmation phrase and run-level permissions."
            : "Privileged action mode is disabled in backend configuration.");
    return output;
  }

  public synchronized Map<String, Object> executePrivilegedAction(
      String runId, String actionType, String confirmationPhrase, String note) {
    AgentRun run = getRunOrThrow(runId);
    String normalizedActionType = normalizeActionType(actionType);
    if (!privilegedActionsEnabled) {
      throw new IllegalStateException("Privileged action mode is disabled by configuration.");
    }
    if (!hasConfiguredActionCommand(normalizedActionType)) {
      throw new IllegalStateException(
          "Privileged action is not configured for type: " + normalizedActionType);
    }

    String normalizedConfirmation = safeTrim(confirmationPhrase);
    if (privilegedActionsConfirmationPhrase.isEmpty()
        || !privilegedActionsConfirmationPhrase.equals(normalizedConfirmation)) {
      throw new IllegalArgumentException("Confirmation phrase mismatch for privileged action.");
    }

    requireRunConstraintPermission(run, normalizedActionType);

    AgentStep remediationStep = findStepByToolName(run, "propose_supervised_actions");
    if (remediationStep == null) {
      throw new IllegalStateException("Remediation plan step not found for this run.");
    }
    if (!AgentStepStatus.COMPLETED.name().equals(remediationStep.getStatus())) {
      throw new IllegalStateException(
          "Approve the remediation plan step before executing privileged actions.");
    }

    String commandTemplate = privilegedActionCommands.get(normalizedActionType);
    String command = renderPrivilegedActionCommand(run, normalizedActionType, commandTemplate);
    String actionLabel = actionLabel(normalizedActionType);
    setRunStatus(run, AgentRunStatus.RUNNING, "Executing privileged action: " + actionLabel);
    addEvent(
        run.getId(),
        remediationStep.getId(),
        "ACTION_STARTED",
        "Privileged action started: " + actionLabel,
        payload("actionType", normalizedActionType, "actionLabel", actionLabel));

    Map<String, Object> actionResult = runPrivilegedCommand(command);
    appendExecutedAction(remediationStep, run, normalizedActionType, note, actionResult);

    if (Boolean.TRUE.equals(actionResult.get("success"))) {
      addEvent(
          run.getId(),
          remediationStep.getId(),
          "ACTION_COMPLETED",
          "Privileged action completed: " + actionLabel,
          payload(
              "actionType",
              normalizedActionType,
              "actionLabel",
              actionLabel,
              "exitCode",
              actionResult.get("exitCode")));
      setRunStatus(run, AgentRunStatus.RUNNING, "Privileged action completed: " + actionLabel);
    } else {
      addEvent(
          run.getId(),
          remediationStep.getId(),
          "ACTION_FAILED",
          "Privileged action failed: " + actionLabel,
          payload(
              "actionType",
              normalizedActionType,
              "actionLabel",
              actionLabel,
              "exitCode",
              actionResult.get("exitCode"),
              "error",
              asString(actionResult.get("error"))));
      setRunStatus(run, AgentRunStatus.RUNNING, "Privileged action failed: " + actionLabel);
    }
    run.setUpdatedAt(Instant.now());
    return actionResult;
  }

  public synchronized AgentRun approveStep(String runId, String stepId, String note) {
    AgentRun run = getRunOrThrow(runId);
    AgentStep step = getStepOrThrow(run, stepId);
    if (!AgentStepStatus.AWAITING_APPROVAL.name().equals(step.getStatus())) {
      throw new IllegalStateException("Step is not awaiting approval.");
    }

    setStepStatus(step, AgentStepStatus.COMPLETED);
    if ("propose_supervised_actions".equals(step.getToolName())) {
      Map<String, Object> planOutput = ensureSupervisedRemediationOutput(run, step);
      planOutput.put("decision", "approved");
      planOutput.put("decisionNote", safeTrim(note));
      planOutput.put(
          "executionPolicy",
          "Approval recorded. No restart/deploy/destructive actions are executed automatically.");
      step.setOutput(planOutput);
      step.setSummary(buildSupervisedPlanSummary(planOutput, "approved"));
    } else {
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
    }

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
    if ("propose_supervised_actions".equals(step.getToolName())) {
      Map<String, Object> planOutput = ensureSupervisedRemediationOutput(run, step);
      planOutput.put("decision", "rejected");
      planOutput.put("decisionNote", safeTrim(note));
      planOutput.put("executionPolicy", "Rejected plan was not executed.");
      step.setOutput(planOutput);
      step.setSummary(buildSupervisedPlanSummary(planOutput, "rejected"));
    } else {
      step.setSummary("User rejected the step. Continuing with safe verification only.");
      step.setOutput(
          payload(
              "decision",
              "rejected",
              "note",
              safeTrim(note),
              "executionPolicy",
              "Rejected step was not executed."));
    }

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
          if ("propose_supervised_actions".equals(next.getToolName())) {
            Map<String, Object> planOutput = ensureSupervisedRemediationOutput(run, next);
            next.setOutput(planOutput);
            next.setSummary(buildSupervisedPlanSummary(planOutput, "awaiting"));
          }
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
      } else if ("propose_supervised_actions".equals(step.getToolName())) {
        output = ensureSupervisedRemediationOutput(run, step);
        summary = buildSupervisedPlanSummary(output, "generated");
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
    List<String> heuristicBuckets = buildHeuristicSuggestedBuckets(run.getGoal());
    Map<String, Object> evidenceOutput = findOutputByToolName(run, "collect_log_evidence");

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("goal", run.getGoal());
    output.put("suggestedBuckets", heuristicBuckets);
    output.put("confidenceHint", "medium");
    output.put("engine", "supervised-heuristic-workflow");
    output.put(
        "note",
        "Classification is heuristic in Step 1. Use finding details and user approval before any remediation.");

    if (!agentLlmService.isLlmSupervisionEnabled()) {
      output.put("llmMode", "disabled");
      return output;
    }
    if (!agentLlmService.isLlmSupervisionActive()) {
      output.put("llmMode", "configured_without_key");
      output.put(
          "llmWarning",
          "Agent LLM supervision is enabled but OPENAI_API_KEY is not available. Using heuristic classification.");
      return output;
    }

    try {
      Map<String, Object> llmResult =
          agentLlmService.classifyIssueCandidates(run.getGoal(), evidenceOutput, heuristicBuckets);
      List<String> llmBuckets = asStringList(llmResult.get("suggestedBuckets"));
      if (!llmBuckets.isEmpty()) {
        output.put("suggestedBuckets", llmBuckets);
      }
      output.put("confidenceHint", asString(llmResult.get("confidenceHint")));
      output.put(
          "note",
          firstNonBlank(
              asString(llmResult.get("note")),
              "Classification drafted by LLM-supervised mode. Review evidence before remediation."));
      output.put("engine", "llm-supervised");
      output.put("llmMode", "active");
      output.put("llmProvider", asString(llmResult.get("provider")));
      output.put("llmModel", asString(llmResult.get("model")));
    } catch (Exception ex) {
      output.put("llmMode", "fallback");
      output.put("llmWarning", safeMessage(ex.getMessage()));
    }
    return output;
  }

  private Map<String, Object> ensureSupervisedRemediationOutput(AgentRun run, AgentStep step) {
    Map<String, Object> existing = step.getOutput();
    if (existing != null && !existing.isEmpty() && existing.containsKey("options")) {
      return new LinkedHashMap<>(existing);
    }
    return buildSupervisedRemediationPlan(run);
  }

  private Map<String, Object> buildSupervisedRemediationPlan(AgentRun run) {
    Map<String, Object> evidenceOutput = findOutputByToolName(run, "collect_log_evidence");
    Map<String, Object> classificationOutput = findOutputByToolName(run, "classify_issue_candidates");

    List<String> suggestedBuckets = asStringList(classificationOutput.get("suggestedBuckets"));
    if (suggestedBuckets.isEmpty()) {
      suggestedBuckets.add("App bug / NullPointer / ClassNotFound / config");
    }
    String primaryBucket = suggestedBuckets.get(0);
    String targetHost = resolveTargetForRun(run);

    int totalPaths = asInt(evidenceOutput.get("totalPaths"));
    int sampledPaths = asInt(evidenceOutput.get("sampledPaths"));
    int readablePaths = asInt(evidenceOutput.get("readablePaths"));
    List<Map<String, Object>> pathResults = asMapList(evidenceOutput.get("pathResults"));

    List<String> readableSamplePaths = new ArrayList<>();
    List<String> unreadablePathHints = new ArrayList<>();
    for (Map<String, Object> pathResult : pathResults) {
      String status = asString(pathResult.get("status")).toLowerCase();
      String path = asString(pathResult.get("path"));
      if ("readable".equals(status) && !path.isEmpty() && readableSamplePaths.size() < 3) {
        readableSamplePaths.add(path);
      }
      if ("error".equals(status) && unreadablePathHints.size() < 3) {
        String error = asString(pathResult.get("error"));
        if (!path.isEmpty() && !error.isEmpty()) {
          unreadablePathHints.add(path + " -> " + error);
        } else if (!path.isEmpty()) {
          unreadablePathHints.add(path);
        } else if (!error.isEmpty()) {
          unreadablePathHints.add(error);
        }
      }
    }

    String evidenceSummary;
    if (totalPaths <= 0) {
      evidenceSummary =
          "No explicit paths were provided. Remediation options are based on goal and classification only.";
    } else {
      evidenceSummary =
          "Readable paths: "
              + readablePaths
              + "/"
              + sampledPaths
              + " sampled ("
              + totalPaths
              + " configured).";
    }

    List<String> targetedActions = buildTargetedActions(primaryBucket);
    List<String> targetedSuccessSignals = buildTargetedSuccessSignals(primaryBucket);
    String planAuthoringMode = "supervised-heuristic-workflow";
    String llmPlanNote = "";
    String llmPlanWarning = "";

    if (agentLlmService.isLlmSupervisionEnabled()) {
      if (!agentLlmService.isLlmSupervisionActive()) {
        llmPlanWarning =
            "Agent LLM supervision is enabled but OPENAI_API_KEY is not available. Using heuristic plan text.";
      } else {
        try {
          Map<String, Object> planHints =
              agentLlmService.draftRemediationPlanHints(
                  run.getGoal(),
                  primaryBucket,
                  suggestedBuckets,
                  evidenceSummary,
                  readableSamplePaths,
                  unreadablePathHints);
          String llmPrimaryCategory = asString(planHints.get("primaryCategory"));
          if (!llmPrimaryCategory.isEmpty()) {
            primaryBucket = llmPrimaryCategory;
          }
          List<String> llmActions = asStringList(planHints.get("targetedActions"));
          if (!llmActions.isEmpty()) {
            targetedActions = llmActions;
          } else if (!llmPrimaryCategory.isEmpty()) {
            targetedActions = buildTargetedActions(primaryBucket);
          }
          List<String> llmSignals = asStringList(planHints.get("successSignals"));
          if (!llmSignals.isEmpty()) {
            targetedSuccessSignals = llmSignals;
          } else if (!llmPrimaryCategory.isEmpty()) {
            targetedSuccessSignals = buildTargetedSuccessSignals(primaryBucket);
          }
          String llmEvidenceSummary = asString(planHints.get("evidenceSummary"));
          if (!llmEvidenceSummary.isEmpty()) {
            evidenceSummary = llmEvidenceSummary;
          }
          llmPlanNote = asString(planHints.get("operatorNote"));
          planAuthoringMode = "llm-supervised";
        } catch (Exception ex) {
          llmPlanWarning = safeMessage(ex.getMessage());
        }
      }
    }

    List<Map<String, Object>> options = new ArrayList<>();
    options.add(
        payload(
            "id",
            "validate-signature",
            "title",
            "Validate failure signature and blast radius (read-only)",
            "risk",
            AgentRiskLevel.SAFE.name(),
            "requiresApproval",
            false,
            "why",
            "Confirming the dominant failure signature first prevents incorrect remediation.",
            "actions",
            List.of(
                "Capture a 15-30 minute log window around first failure timestamp.",
                "Group repeated stack traces and impacted endpoints/user flows.",
                "Confirm whether failures are isolated to one node/path or broad across instances."),
            "successSignals",
            List.of(
                "Dominant error signature is consistently identified.",
                "Blast radius is documented with affected scope."),
            "rollback",
            "Not required for read-only diagnostics."));

    options.add(
        payload(
            "id",
            "targeted-remediation",
            "title",
            "Prepare targeted remediation for " + primaryBucket,
            "risk",
            AgentRiskLevel.APPROVAL_REQUIRED.name(),
            "requiresApproval",
            true,
            "why",
            "Apply the smallest possible change aligned to the likely issue class.",
            "actions",
            targetedActions,
            "successSignals",
            targetedSuccessSignals,
            "rollback",
            "Create explicit rollback steps and owner sign-off before applying any runtime/config/code change."));

    options.add(
        payload(
            "id",
            "controlled-validation",
            "title",
            "Controlled rollout and post-change verification",
            "risk",
            AgentRiskLevel.APPROVAL_REQUIRED.name(),
            "requiresApproval",
            true,
            "why",
            "Reduce incident risk by validating the change with measurable health checks.",
            "actions",
            List.of(
                "Apply change in lower environment or constrained canary scope first.",
                "Track error-rate trend and repeating exception signatures for at least 15-30 minutes.",
                "Stop rollout immediately if new high-severity signatures appear."),
            "successSignals",
            List.of(
                "Target error signature frequency decreases materially.",
                "No new high-severity regressions are introduced."),
            "rollback",
            "Revert to prior known-good config/build and re-run health verification checks."));

    Map<String, Object> output = new LinkedHashMap<>();
    output.put("goal", run.getGoal());
    if (!targetHost.isEmpty()) {
      output.put("targetHost", targetHost);
    }
    output.put("primaryCategory", primaryBucket);
    output.put("suggestedBuckets", suggestedBuckets);
    output.put("evidenceSummary", evidenceSummary);
    output.put("planAuthoringMode", planAuthoringMode);
    if (!llmPlanNote.isEmpty()) {
      output.put("note", llmPlanNote);
    }
    if (!llmPlanWarning.isEmpty()) {
      output.put("llmWarning", llmPlanWarning);
    }
    output.put("options", options);
    output.put(
        "approvalChecklist",
        List.of(
            "Named owner confirms scope, timeline, and communication plan.",
            "Rollback procedure is documented and validated before execution.",
            "Success metrics and abort thresholds are explicitly defined.",
            "Production-impacting actions require explicit user approval.",
            "Agent remains read-only and will not execute start/deploy/destructive actions."));
    output.put(
        "blockedActions",
        List.of("Service restart", "Deployment", "Data deletion", "Infrastructure mutation"));
    if (!readableSamplePaths.isEmpty()) {
      output.put("readableSamplePaths", readableSamplePaths);
    }
    if (!unreadablePathHints.isEmpty()) {
      output.put("unreadablePathHints", unreadablePathHints);
    }
    output.put(
        "executionPolicy",
        "Plan-only mode. Remediation execution requires explicit user approval and manual operator action.");
    return output;
  }

  private List<String> buildHeuristicSuggestedBuckets(String rawGoal) {
    String goal = safeTrim(rawGoal).toLowerCase();
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
    return suggestedBuckets;
  }

  private List<String> buildTargetedActions(String primaryBucket) {
    String bucket = safeTrim(primaryBucket).toLowerCase();
    if (bucket.contains("db2") || bucket.contains("jdbc")) {
      return List.of(
          "Validate DB2 host/port, credentials, and pool timeout settings against current environment.",
          "Confirm JDBC driver compatibility and SQLSTATE details in failing stack traces.",
          "Run controlled connection test in non-prod before any production changes.");
    }
    if (bucket.contains("ssl") || bucket.contains("tls") || bucket.contains("cert")) {
      return List.of(
          "Inspect certificate chain, expiry, and truststore entries for the failing endpoint.",
          "Confirm TLS protocol/cipher overlap between client and server.",
          "Prepare certificate/truststore update procedure with rollback artifact.");
    }
    if (bucket.contains("auth") || bucket.contains("sso") || bucket.contains("token")) {
      return List.of(
          "Validate token expiry, signature validation, and issuer/audience claims.",
          "Confirm role/permission mappings for affected endpoint flows.",
          "Prepare minimal auth config correction with rollback to prior policy.");
    }
    if (bucket.contains("memory") || bucket.contains("oom") || bucket.contains("heap")) {
      return List.of(
          "Capture heap/GC indicators around failure window to confirm pressure pattern.",
          "Define minimal JVM tuning or memory-leak containment hypothesis.",
          "Apply one change at a time with monitored rollback threshold.");
    }
    if (bucket.contains("timeout") || bucket.contains("integration")) {
      return List.of(
          "Measure downstream latency and timeout distribution for failed calls.",
          "Tune client timeout/retry settings with bounded retries and circuit-breaker protection.",
          "Coordinate with downstream owner before production-side timeout changes.");
    }
    if (bucket.contains("search") || bucket.contains("solr")) {
      return List.of(
          "Check Solr core/collection health, replica status, and query errors.",
          "Isolate failing query/index pipeline stage and replay in non-prod.",
          "Prepare targeted index/query fix with rollback for schema or config changes.");
    }
    if (bucket.contains("cache") || bucket.contains("session")) {
      return List.of(
          "Validate cache/session invalidation sequence and key lifecycle.",
          "Confirm session affinity/replication behavior across nodes.",
          "Apply minimal cache/session config correction with rollback guardrails.");
    }
    if (bucket.contains("network") || bucket.contains("dns")) {
      return List.of(
          "Validate DNS resolution and route reachability from the application host.",
          "Confirm firewall and network policy consistency for affected endpoint paths.",
          "Coordinate controlled network/policy change with rapid rollback path.");
    }
    return List.of(
        "Capture full stack trace and identify exact failing class/method/config key.",
        "Prepare minimal code/config fix for the failing branch only.",
        "Validate in lower environment using same input pattern before production approval.");
  }

  private List<String> buildTargetedSuccessSignals(String primaryBucket) {
    String bucket = safeTrim(primaryBucket).toLowerCase();
    if (bucket.contains("db2") || bucket.contains("jdbc")) {
      return List.of(
          "Connection errors/SQLSTATE connectivity failures stop recurring.",
          "Database response latency remains within normal threshold.");
    }
    if (bucket.contains("ssl") || bucket.contains("tls") || bucket.contains("cert")) {
      return List.of(
          "Handshake/trust validation errors disappear.",
          "Secure calls complete without certificate exceptions.");
    }
    if (bucket.contains("auth") || bucket.contains("sso") || bucket.contains("token")) {
      return List.of(
          "401/403 spikes are reduced for affected flow.",
          "Token validation/authz checks pass for legitimate requests.");
    }
    if (bucket.contains("memory") || bucket.contains("oom") || bucket.contains("heap")) {
      return List.of(
          "OOM/GC-thrash signatures no longer appear.",
          "Heap usage trend stabilizes after traffic normalization.");
    }
    return List.of(
        "Target failure signature frequency decreases.",
        "No new high-severity error signature is introduced.");
  }

  private String buildSupervisedPlanSummary(Map<String, Object> output, String phase) {
    int optionCount = asMapList(output.get("options")).size();
    String category = asString(output.get("primaryCategory"));
    if (category.isEmpty()) {
      category = "unclassified runtime issue";
    }
    if ("awaiting".equals(phase)) {
      return "Prepared "
          + optionCount
          + " supervised remediation options for "
          + category
          + ". Awaiting explicit user approval for risky actions.";
    }
    if ("approved".equals(phase)) {
      return "User approved supervised remediation plan for "
          + category
          + ". No automated start/deploy action was executed.";
    }
    if ("rejected".equals(phase)) {
      return "User rejected supervised remediation plan for "
          + category
          + ". Continuing with safe verification only.";
    }
    return "Prepared "
        + optionCount
        + " supervised remediation options for "
        + category
        + " with rollback and approval checklist.";
  }

  private Map<String, Object> findOutputByToolName(AgentRun run, String toolName) {
    String normalizedTool = safeTrim(toolName);
    for (AgentStep step : run.getSteps()) {
      if (normalizedTool.equals(step.getToolName()) && step.getOutput() != null) {
        return step.getOutput();
      }
    }
    return new LinkedHashMap<>();
  }

  private AgentStep findStepByToolName(AgentRun run, String toolName) {
    String normalizedTool = safeTrim(toolName);
    for (AgentStep step : run.getSteps()) {
      if (normalizedTool.equals(step.getToolName())) {
        return step;
      }
    }
    return null;
  }

  private boolean hasConfiguredActionCommand(String actionType) {
    String command = privilegedActionCommands.get(actionType);
    return command != null && !command.isEmpty();
  }

  private String normalizeActionType(String actionType) {
    String normalized = safeTrim(actionType).toLowerCase().replace('-', '_');
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Action type is required.");
    }
    if (!privilegedActionCommands.containsKey(normalized)) {
      throw new IllegalArgumentException("Unsupported action type: " + normalized);
    }
    return normalized;
  }

  private void requireRunConstraintPermission(AgentRun run, String actionType) {
    Map<String, Object> constraints = run.getConstraints() == null ? Map.of() : run.getConstraints();
    boolean allowStart = toBooleanConstraint(constraints.get("allow_start"));
    boolean allowDeploy = toBooleanConstraint(constraints.get("allow_deploy"));
    boolean allowCodeChanges = toBooleanConstraint(constraints.get("allow_code_changes"));

    if ("restart_server".equals(actionType) && !allowStart) {
      throw new IllegalStateException("Run constraints block restart action. Enable allow_start first.");
    }
    if ("deploy".equals(actionType) && !allowDeploy) {
      throw new IllegalStateException("Run constraints block deploy action. Enable allow_deploy first.");
    }
    if ("code_change".equals(actionType) && !allowCodeChanges) {
      throw new IllegalStateException(
          "Run constraints block code-change action. Enable allow_code_changes first.");
    }
  }

  private String actionLabel(String actionType) {
    if ("restart_server".equals(actionType)) {
      return "Restart server";
    }
    if ("deploy".equals(actionType)) {
      return "Run deployment";
    }
    if ("code_change".equals(actionType)) {
      return "Apply code change";
    }
    return actionType;
  }

  private String renderPrivilegedActionCommand(
      AgentRun run, String actionType, String commandTemplate) {
    String template = safeTrim(commandTemplate);
    if (template.isEmpty()) {
      throw new IllegalStateException("No command configured for action: " + actionType);
    }

    String target = resolveTargetForRun(run);
    boolean needsTarget =
        template.contains("{target}") || template.contains("{target_host}") || template.contains("{host}");
    if (needsTarget && target.isEmpty()) {
      throw new IllegalStateException(
          "No target extracted from goal. Use phrasing like 'for server-hostname' or configure a fixed command.");
    }

    String rendered = template;
    if (!target.isEmpty()) {
      rendered = rendered.replace("{target}", target);
      rendered = rendered.replace("{target_host}", target);
      rendered = rendered.replace("{host}", target);
    }
    rendered = rendered.replace("{action}", actionType);
    return rendered;
  }

  private String resolveTargetForRun(AgentRun run) {
    if (run == null) {
      return "";
    }
    Map<String, Object> constraints = run.getConstraints();
    String fromConstraints = asString(constraints == null ? null : constraints.get("extracted_target"));
    if (!fromConstraints.isEmpty()) {
      return fromConstraints;
    }
    return extractTargetFromGoal(run.getGoal());
  }

  private String extractTargetFromGoal(String goal) {
    String normalizedGoal = safeTrim(goal);
    if (normalizedGoal.isEmpty()) {
      return "";
    }
    Matcher matcher = TARGET_FROM_GOAL_PATTERN.matcher(normalizedGoal);
    if (!matcher.find()) {
      return "";
    }
    String candidate = safeTrim(matcher.group(1));
    if (!isSafeTargetToken(candidate)) {
      return "";
    }
    return candidate;
  }

  private boolean isSafeTargetToken(String token) {
    String normalized = safeTrim(token);
    if (normalized.isEmpty()) {
      return false;
    }
    return SAFE_TARGET_PATTERN.matcher(normalized).matches();
  }

  private Map<String, Object> runPrivilegedCommand(String command) {
    Map<String, Object> output = new LinkedHashMap<>();
    long startedAt = System.currentTimeMillis();
    StringBuilder commandOutput = new StringBuilder();
    Process process = null;
    Thread readerThread = null;
    try {
      ProcessBuilder processBuilder = buildCommandProcess(command);
      processBuilder.redirectErrorStream(true);
      process = processBuilder.start();

      final Process streamProcess = process;
      readerThread =
          new Thread(
              () -> captureProcessOutput(streamProcess, commandOutput, MAX_COMMAND_OUTPUT_CHARS),
              "agent-action-output-reader");
      readerThread.setDaemon(true);
      readerThread.start();

      boolean completed = process.waitFor(privilegedActionTimeoutSeconds, TimeUnit.SECONDS);
      if (!completed) {
        process.destroyForcibly();
      }
      if (readerThread != null) {
        readerThread.join(1500L);
      }

      int exitCode = completed ? process.exitValue() : -1;
      long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
      output.put("actionCommand", command);
      output.put("durationMs", durationMs);
      output.put("completed", completed);
      output.put("exitCode", exitCode);
      output.put("success", completed && exitCode == 0);
      output.put("outputSnippet", safeTrim(commandOutput.toString()));
      if (!completed) {
        output.put(
            "error",
            "Action command timed out after " + privilegedActionTimeoutSeconds + " seconds.");
      }
      return output;
    } catch (Exception ex) {
      output.put("actionCommand", command);
      output.put("durationMs", Math.max(0L, System.currentTimeMillis() - startedAt));
      output.put("completed", false);
      output.put("exitCode", -1);
      output.put("success", false);
      output.put("outputSnippet", safeTrim(commandOutput.toString()));
      output.put("error", safeMessage(ex.getMessage()));
      return output;
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      if (readerThread != null && readerThread.isAlive()) {
        readerThread.interrupt();
      }
    }
  }

  private ProcessBuilder buildCommandProcess(String command) {
    String normalizedCommand = safeTrim(command);
    if (normalizedCommand.isEmpty()) {
      throw new IllegalArgumentException("Privileged action command is empty.");
    }
    String osName = System.getProperty("os.name", "").toLowerCase();
    if (osName.contains("win")) {
      return new ProcessBuilder("cmd.exe", "/c", normalizedCommand);
    }
    return new ProcessBuilder("/bin/sh", "-c", normalizedCommand);
  }

  private void captureProcessOutput(Process process, StringBuilder output, int maxChars) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (output.length() >= maxChars) {
          continue;
        }
        if (output.length() > 0) {
          output.append('\n');
        }
        int remaining = maxChars - output.length();
        if (remaining <= 0) {
          continue;
        }
        if (line.length() <= remaining) {
          output.append(line);
        } else {
          output.append(line, 0, remaining);
        }
      }
    } catch (Exception ignored) {
      // ignore stream read failures and rely on process status
    }
    if (output.length() >= maxChars) {
      output.append("\n...output truncated...");
    }
  }

  private void appendExecutedAction(
      AgentStep remediationStep,
      AgentRun run,
      String actionType,
      String note,
      Map<String, Object> actionResult) {
    Map<String, Object> planOutput = ensureSupervisedRemediationOutput(run, remediationStep);
    List<Map<String, Object>> executedActions = asMapList(planOutput.get("executedActions"));

    Map<String, Object> actionRecord = new LinkedHashMap<>();
    actionRecord.put("actionType", actionType);
    actionRecord.put("actionLabel", actionLabel(actionType));
    actionRecord.put("timestamp", Instant.now().toString());
    actionRecord.put("note", safeTrim(note));
    actionRecord.put("success", Boolean.TRUE.equals(actionResult.get("success")));
    actionRecord.put("exitCode", asInt(actionResult.get("exitCode")));
    actionRecord.put("durationMs", asLong(actionResult.get("durationMs")));
    actionRecord.put("outputSnippet", asString(actionResult.get("outputSnippet")));
    if (!asString(actionResult.get("error")).isEmpty()) {
      actionRecord.put("error", asString(actionResult.get("error")));
    }

    executedActions.add(actionRecord);
    planOutput.put("executedActions", executedActions);
    planOutput.put("lastAction", actionRecord);
    planOutput.put(
        "executionPolicy",
        "Privileged command execution requires explicit mode enablement, confirmation phrase, and run constraints.");
    remediationStep.setOutput(planOutput);
    remediationStep.setSummary(
        "Executed privileged action: "
            + actionLabel(actionType)
            + " (success="
            + Boolean.TRUE.equals(actionResult.get("success"))
            + ").");
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
    output.put("allow_code_changes", false);
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

  private String firstNonBlank(String... values) {
    if (values == null) {
      return "";
    }
    for (String value : values) {
      String normalized = safeTrim(value);
      if (!normalized.isEmpty()) {
        return normalized;
      }
    }
    return "";
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

  private long asLong(Object value) {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(value.toString().trim());
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private boolean toBooleanConstraint(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    String normalized = asString(value).toLowerCase();
    return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
  }

  private String asString(Object value) {
    return value == null ? "" : safeTrim(value.toString());
  }

  private List<String> asStringList(Object value) {
    List<String> output = new ArrayList<>();
    if (value instanceof List<?>) {
      for (Object item : (List<?>) value) {
        String normalized = asString(item);
        if (!normalized.isEmpty()) {
          output.add(normalized);
        }
      }
      return output;
    }
    String single = asString(value);
    if (!single.isEmpty()) {
      output.add(single);
    }
    return output;
  }

  private List<Map<String, Object>> asMapList(Object value) {
    List<Map<String, Object>> output = new ArrayList<>();
    if (!(value instanceof List<?>)) {
      return output;
    }
    for (Object item : (List<?>) value) {
      if (item instanceof Map<?, ?>) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        ((Map<?, ?>) item)
            .forEach(
                (key, val) -> {
                  if (key != null) {
                    normalized.put(key.toString(), val);
                  }
                });
        output.add(normalized);
      }
    }
    return output;
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
