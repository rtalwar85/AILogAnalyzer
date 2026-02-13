package com.ailoganalyzer.app.model;

public class WebSolutionItem {
  private String title;
  private String source;
  private String solution;
  private String url;

  public WebSolutionItem() {}

  public WebSolutionItem(String title, String source, String solution, String url) {
    this.title = title;
    this.source = source;
    this.solution = solution;
    this.url = url;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getSolution() {
    return solution;
  }

  public void setSolution(String solution) {
    this.solution = solution;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }
}
