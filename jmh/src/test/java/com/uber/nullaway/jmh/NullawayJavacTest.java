package com.uber.nullaway.jmh;

import java.io.IOException;
import org.junit.Test;

public class NullawayJavacTest {

  @Test
  public void simpleTest() throws IOException {
    NullawayJavac n = new NullawayJavac();
    n.prepareForSimpleTest();
    org.junit.Assert.assertTrue(!n.testCompile());
  }
}
