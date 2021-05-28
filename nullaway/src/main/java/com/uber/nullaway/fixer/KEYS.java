package com.uber.nullaway.fixer;

public enum KEYS {
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

  KEYS(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return "KEYS{" + "label='" + label + '\'' + '}';
  }
}
