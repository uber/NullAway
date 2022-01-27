package com.uber.nullaway.jarinfer;

import java.util.List;

/** Interface to be implemented by providers of stubx files to be read by NullAway */
public interface JarInferStubxProvider {

  /**
   * Returns a list of paths to stubx files to be loaded. Paths can be relative to the location of
   * the implementing provider class (recommended), or absolute (starting with {@code /}).
   *
   * <p><b>NOTE:</b> NullAway does <em>not</em> have any special handling for cases where there are
   * multiple jars in its classpath containing a stubx file at the same path. So, providers are
   * strongly encouraged to use paths to stubx files that are unlikely to clash with those in other
   * jars.
   */
  List<String> pathsToStubxFiles();
}
