package com.uber.nullaway.hang;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.MethodTree;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Blocks on every method until {@link #release} opens, or for a minute at most, standing in for a
 * NullAway run that hangs. It lives outside {@code com.uber.nullaway.oracles}, whose frames {@code
 * CrashSignature} skips, so the signature of its hang names {@link #matchMethod}.
 */
@BugPattern(summary = "Blocks on every method", severity = BugPattern.SeverityLevel.WARNING)
public class BlockingChecker extends BugChecker implements BugChecker.MethodTreeMatcher {

  /** The latch the next run waits on; a test replaces it before the run and opens it after. */
  public static volatile CountDownLatch release = new CountDownLatch(1);

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    try {
      release.await(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return Description.NO_MATCH;
  }
}
