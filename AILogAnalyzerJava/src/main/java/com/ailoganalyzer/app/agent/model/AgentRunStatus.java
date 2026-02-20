package com.ailoganalyzer.app.agent.model;

public enum AgentRunStatus {
  QUEUED,
  PLANNING,
  RUNNING,
  AWAITING_APPROVAL,
  VERIFYING,
  COMPLETED,
  FAILED,
  CANCELLED
}
