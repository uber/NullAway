package com.uber.mylib;

import java.io.IOException;
import javax.annotation.Nullable;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

/** A sample class. */
public class MyClass {

  static void log(Object x) {
    System.out.println(x.toString());
  }

  static void test(@Nullable String s) throws IOException {
    IOUtils.toByteArray(s);
    log(FilenameUtils.getExtension(s));
  }
}
