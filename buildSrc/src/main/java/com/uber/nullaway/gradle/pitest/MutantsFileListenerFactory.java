/*
 * Copyright (C) 2026. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.gradle.pitest;

import com.uber.nullaway.gradle.MutantsFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Properties;
import org.pitest.mutationtest.ClassMutationResults;
import org.pitest.mutationtest.ListenerArguments;
import org.pitest.mutationtest.MutationResult;
import org.pitest.mutationtest.MutationResultListener;
import org.pitest.mutationtest.MutationResultListenerFactory;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Writes every mutant of a PIT run, with the tests PIT recorded as covering it, to a {@link
 * MutantsFile}.
 *
 * <p>PIT selects this report by the name {@link MutantsFile#LISTENER_FORMAT} in {@code
 * --outputFormats}. It finds the
 * factory through {@link java.util.ServiceLoader}, as it finds the changed-lines interceptor.
 */
public final class MutantsFileListenerFactory implements MutationResultListenerFactory {

  @Override
  public String name() {
    return MutantsFile.LISTENER_FORMAT;
  }

  @Override
  public String description() {
    return "Writes every mutant with its covering tests to " + MutantsFile.NAME;
  }

  @Override
  public MutationResultListener getListener(Properties props, ListenerArguments args) {
    return new Listener(args.getOutputStrategy().createWriterForFile(MutantsFile.NAME));
  }

  /** Writes one line per mutant as PIT reports the results of each class. */
  static final class Listener implements MutationResultListener {

    private final Writer out;

    Listener(Writer out) {
      this.out = out;
    }

    @Override
    public void runStart() {}

    @Override
    public void handleMutationResult(ClassMutationResults results) {
      try {
        for (MutationResult result : results.getMutations()) {
          MutationDetails details = result.getDetails();
          out.write(
              MutantsFile.line(
                  new MutantsFile.Mutant(
                      result.getStatus().name(),
                      details.getClassName().asJavaName(),
                      details.getMethod(),
                      details.getFilename(),
                      details.getLineNumber(),
                      details.getDescription(),
                      result.getCoveringTests())));
          out.write('\n');
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void runEnd() {
      try {
        out.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
