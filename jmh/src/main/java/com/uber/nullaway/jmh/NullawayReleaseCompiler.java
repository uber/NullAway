/*
 * Copyright (c) 2021 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.nullaway.jmh;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class NullawayReleaseCompiler extends AbstractBenchmarkCompiler {

  public NullawayReleaseCompiler() throws IOException {
    super();
  }

  @Override
  protected String getSourceDirectory() {
    return System.getProperty("nullaway.nullawayRelease.sources");
  }

  @Override
  protected List<String> getExtraErrorProneArgs() {
    return Arrays.asList(
        "-XepOpt:NullAway:CheckOptionalEmptiness=true",
        "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true",
        "-XepOpt:NullAway:CastToNonNullMethod=com.uber.nullaway.NullabilityUtil.castToNonNull");
  }

  @Override
  protected String getAnnotatedPackages() {
    return "com.uber,org.checkerframework.nullaway";
  }

  @Override
  protected String getClasspath() {
    return System.getProperty("nullaway.nullawayRelease.classpath");
  }

  @Override
  protected String getExtraProcessorPath() {
    return System.getProperty("nullaway.nullawayRelease.processorpath");
  }
}
