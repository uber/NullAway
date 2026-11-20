package com.uber.nullaway.oracles;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.ClassTree;

/**
 * Sleeps for 4.5 s on an interface named {@code A3}, which a {@link BoundGraphGenerator} program
 * declares from depth 3, standing in for a NullAway run whose time grows with the depth. The sleep
 * is over twice {@link GrowthOracle#SLOW_RUN}, so the oracle does not repeat the run.
 */
@BugPattern(summary = "Sleeps on A3", severity = BugPattern.SeverityLevel.WARNING)
public class SlowFromDepthThreeChecker extends BugChecker implements BugChecker.ClassTreeMatcher {

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    if (tree.getSimpleName().contentEquals("A3")) {
      try {
        Thread.sleep(4500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return Description.NO_MATCH;
  }
}
