package com.uber.nullaway.oracles;

/**
 * One compilation unit of a seed or of a variant derived from it.
 *
 * @param path the relative path javac reports the unit under, such as {@code com/uber/Foo.java}
 * @param text the unit's full source text
 */
record Source(String path, String text) {

  /** Returns a unit with the same path and {@code newText} as its source. */
  Source withText(String newText) {
    return new Source(path, newText);
  }
}
