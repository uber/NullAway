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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.nullaway.gradle.MutantsFile;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.ClassMutationResults;
import org.pitest.mutationtest.DetectionStatus;
import org.pitest.mutationtest.MutationResult;
import org.pitest.mutationtest.MutationResultListenerFactory;
import org.pitest.mutationtest.MutationStatusTestPair;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;

/**
 * The listener writes every mutant PIT reports, with the tests PIT recorded as covering it, and PIT
 * finds the listener under the output format name the build passes.
 */
class MutantsFileListenerFactoryTest {

  private static MutationResult result(
      DetectionStatus status, int line, String description, List<String> coveringTests) {
    Location location = new Location(ClassName.fromString("com.x.A"), "m", "()V");
    MutationDetails details =
        new MutationDetails(
            new MutationIdentifier(location, 0, "NEGATE_CONDITIONALS"),
            "A.java",
            description,
            line,
            0);
    return new MutationResult(
        details, new MutationStatusTestPair(1, status, List.of(), List.of(), coveringTests));
  }

  @Test
  void everyMutantIsWrittenWithItsCoveringTests() {
    StringWriter out = new StringWriter();
    MutantsFileListenerFactory.Listener listener = new MutantsFileListenerFactory.Listener(out);

    listener.runStart();
    listener.handleMutationResult(
        new ClassMutationResults(
            List.of(
                result(DetectionStatus.SURVIVED, 7, "negated conditional", List.of("com.x.T.a(com.x.T)")),
                result(DetectionStatus.KILLED, 8, "removed call", List.of("com.x.T.b(com.x.T)")))));
    listener.runEnd();

    assertEquals(
        "SURVIVED\tcom.x.A\tm\tA.java\t7\tnegated conditional\tcom.x.T.a(com.x.T)\n"
            + "KILLED\tcom.x.A\tm\tA.java\t8\tremoved call\tcom.x.T.b(com.x.T)\n",
        out.toString());
  }

  @Test
  void pitFindsTheListenerUnderTheFormatTheBuildPasses() {
    List<String> names = new ArrayList<>();
    ServiceLoader.load(MutationResultListenerFactory.class)
        .forEach(factory -> names.add(factory.name()));

    assertTrue(names.contains(MutantsFile.LISTENER_FORMAT), () -> "listener names: " + names);
  }
}
