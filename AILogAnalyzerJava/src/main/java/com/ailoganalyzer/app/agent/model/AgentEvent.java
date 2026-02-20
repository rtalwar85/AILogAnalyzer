package com.ailoganalyzer.app.agent.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentEvent {
  private String id;
  private String runId;
  private String stepId;
  private String type;
  private String message;
  private Instant timestamp;
  private Map<String, Object> payload = new LinkedHashMap<>();

  public AgentEvent() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getRunId() {
    return runId;
  }

  public void setRunId(String runId) {
    this.runId = runId;
  }

  public String getStepId() {
    return stepId;
  }

  public void setStepId(String stepId) {
    this.stepId = stepId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Object> getPayload() {
    return payload;
  }

  public void setPayload(Map<String, Object> payload) {
    this.payload = payload == null ? new LinkedHashMap<>() : payload;
  }
}
