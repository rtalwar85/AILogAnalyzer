package com.ailoganalyzer.app.agent.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class AgentStep {
  private String id;
  private String title;
  private String toolName;
  private String riskLevel;
  private String status;
  private boolean requiresApproval;
  private String summary;
  private Map<String, Object> input = new LinkedHashMap<>();
  private Map<String, Object> output = new LinkedHashMap<>();
  private String error;
  private Instant createdAt;
  private Instant updatedAt;

  public AgentStep() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getRiskLevel() {
    return riskLevel;
  }

  public void setRiskLevel(String riskLevel) {
    this.riskLevel = riskLevel;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public boolean isRequiresApproval() {
    return requiresApproval;
  }

  public void setRequiresApproval(boolean requiresApproval) {
    this.requiresApproval = requiresApproval;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public Map<String, Object> getInput() {
    return input;
  }

  public void setInput(Map<String, Object> input) {
    this.input = input == null ? new LinkedHashMap<>() : input;
  }

  public Map<String, Object> getOutput() {
    return output;
  }

  public void setOutput(Map<String, Object> output) {
    this.output = output == null ? new LinkedHashMap<>() : output;
  }

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
