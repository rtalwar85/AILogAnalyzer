package com.ailoganalyzer.app.agent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentRun {
  private String id;
  private String goal;
  private String status;
  private String summary;
  private double confidence;
  private List<String> paths = new ArrayList<>();
  private Map<String, Object> constraints = new LinkedHashMap<>();
  private List<AgentStep> steps = new ArrayList<>();
  private Instant createdAt;
  private Instant updatedAt;

  public AgentRun() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getGoal() {
    return goal;
  }

  public void setGoal(String goal) {
    this.goal = goal;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public double getConfidence() {
    return confidence;
  }

  public void setConfidence(double confidence) {
    this.confidence = confidence;
  }

  public List<String> getPaths() {
    return paths;
  }

  public void setPaths(List<String> paths) {
    this.paths = paths == null ? new ArrayList<>() : paths;
  }

  public Map<String, Object> getConstraints() {
    return constraints;
  }

  public void setConstraints(Map<String, Object> constraints) {
    this.constraints = constraints == null ? new LinkedHashMap<>() : constraints;
  }

  public List<AgentStep> getSteps() {
    return steps;
  }

  public void setSteps(List<AgentStep> steps) {
    this.steps = steps == null ? new ArrayList<>() : steps;
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
