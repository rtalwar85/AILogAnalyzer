package com.ailoganalyzer.app.model;

import java.util.ArrayList;
import java.util.List;

public class WebSolutionResponse {
  private String query;
  private String warning;
  private List<WebSolutionItem> solutions = new ArrayList<>();

  public WebSolutionResponse() {}

  public WebSolutionResponse(String query, String warning, List<WebSolutionItem> solutions) {
    this.query = query;
    this.warning = warning;
    this.solutions = solutions;
  }

  public String getQuery() {
    return query;
  }

  public void setQuery(String query) {
    this.query = query;
  }

  public String getWarning() {
    return warning;
  }

  public void setWarning(String warning) {
    this.warning = warning;
  }

  public List<WebSolutionItem> getSolutions() {
    return solutions;
  }

  public void setSolutions(List<WebSolutionItem> solutions) {
    this.solutions = solutions;
  }
}
