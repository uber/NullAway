package com.uber.nullaway.jarinfer;

import java.util.List;

/** Interface to be implemented by providers of stubx files to be read by NullAway */
public interface JarInferStubxProvider {

  /**
   * Returns a list of paths to stubx files to be loaded. Paths must be <b>absolute</b> within the
   * jar, i.e., they should all start with the {@code '/'} character. Typically, the stubx files
   * reside in the same jar as the implementing class.
   *
   * <p><b>NOTE:</b> NullAway does <em>not</em> have any special handling for cases where there are
   * multiple jars in its classpath containing a stubx file at the same path. So, providers are
   * strongly encouraged to use paths to stubx files that are unlikely to clash with those in other
   * jars.
   */
  List<String> pathsToStubxFiles();
}
