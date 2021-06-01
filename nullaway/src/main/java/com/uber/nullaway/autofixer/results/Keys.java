package com.uber.nullaway.autofixer.results;

public enum Keys {
  PARAM("param"),
  METHOD("method"),
  LOCATION("location"),
  CLASS("class"),
  PKG("pkg"),
  URI("uri"),
  INJECT("inject"),
  ANNOTATION("annotation"),
  REASON("reason"),
  COMPULSORY("compulsory");
  public final String label;

  Keys(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return "KEYS{" + "label='" + label + '\'' + '}';
  }
}
