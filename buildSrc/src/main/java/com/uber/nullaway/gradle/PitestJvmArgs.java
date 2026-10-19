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
package com.uber.nullaway.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;

/** Builds the value of PIT's {@code --jvmArgs} from the settings of a Gradle {@code Test} task. */
public final class PitestJvmArgs {

  private PitestJvmArgs() {}

  /**
   * Returns the JVM arguments of the test task as one comma-separated {@code --jvmArgs} value.
   *
   * <p>Gradle moves every {@code -Dname=value} given through {@code jvmArgs} into the task's system
   * properties, so the arguments the test JVM gets are the JVM arguments, the system properties,
   * and the heap limit together.
   *
   * @param maxHeapSize the test task's {@code maxHeapSize}, or null where it sets none
   * @throws GradleException where an argument contains a comma, which PIT would split into two
   *     arguments and pass to the test JVM broken
   */
  public static String of(
      List<String> jvmArgs, Map<String, ?> systemProperties, String maxHeapSize) {
    List<String> arguments = new ArrayList<>(jvmArgs);
    for (Map.Entry<String, ?> property : systemProperties.entrySet()) {
      arguments.add("-D" + property.getKey() + "=" + property.getValue());
    }
    if (maxHeapSize != null) {
      arguments.add("-Xmx" + maxHeapSize);
    }
    for (String argument : arguments) {
      if (argument.contains(",")) {
        throw new GradleException(
            "PIT splits --jvmArgs at commas, so the test JVM argument "
                + argument
                + " cannot be passed to it");
      }
    }
    return String.join(",", arguments);
  }
}
