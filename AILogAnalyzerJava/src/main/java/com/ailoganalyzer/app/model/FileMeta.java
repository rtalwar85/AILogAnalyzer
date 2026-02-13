package com.ailoganalyzer.app.model;

public class FileMeta {
  private String path;
  private long totalBytes;
  private boolean truncated;

  public FileMeta() {}

  public FileMeta(String path, long totalBytes, boolean truncated) {
    this.path = path;
    this.totalBytes = totalBytes;
    this.truncated = truncated;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public long getTotalBytes() {
    return totalBytes;
  }

  public void setTotalBytes(long totalBytes) {
    this.totalBytes = totalBytes;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public void setTruncated(boolean truncated) {
    this.truncated = truncated;
  }
}
