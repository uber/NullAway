package com.ibm.wala.examples.util;

import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.util.config.FileOfClasses;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class ExampleUtil {

  // more aggressive exclusions to avoid library blowup
  // in interprocedural tests
  private static final String EXCLUSIONS =
      "java\\/awt\\/.*\n"
          + "javax\\/swing\\/.*\n"
          + "sun\\/awt\\/.*\n"
          + "sun\\/swing\\/.*\n"
          + "com\\/sun\\/.*\n"
          + "sun\\/.*\n"
          + "org\\/netbeans\\/.*\n"
          + "org\\/openide\\/.*\n"
          + "com\\/ibm\\/crypto\\/.*\n"
          + "com\\/ibm\\/security\\/.*\n"
          + "org\\/apache\\/xerces\\/.*\n"
          + "java\\/security\\/.*\n"
          + "";

  public static void addDefaultExclusions(AnalysisScope scope)
      throws UnsupportedEncodingException, IOException {
    scope.setExclusions(
        new FileOfClasses(new ByteArrayInputStream(ExampleUtil.EXCLUSIONS.getBytes("UTF-8"))));
  }
}
