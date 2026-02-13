package com.ailoganalyzer.app.model;

import java.util.ArrayList;
import java.util.List;

public class LogPayload {
  private String path;
  private String mode;
  private List<String> files = new ArrayList<>();
  private String content;
  private List<FileMeta> meta = new ArrayList<>();

  public LogPayload() {}

  public LogPayload(String path, String mode, List<String> files, String content, List<FileMeta> meta) {
    this.path = path;
    this.mode = mode;
    this.files = files;
    this.content = content;
    this.meta = meta;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getMode() {
    return mode;
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public List<String> getFiles() {
    return files;
  }

  public void setFiles(List<String> files) {
    this.files = files;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public List<FileMeta> getMeta() {
    return meta;
  }

  public void setMeta(List<FileMeta> meta) {
    this.meta = meta;
  }
}
