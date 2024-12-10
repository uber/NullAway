package com.uber.nullaway;

import static com.uber.nullaway.ErrorProneCLIFlagsConfig.FL_JSPECIFY_MODE;
import static com.uber.nullaway.ErrorProneCLIFlagsConfig.FL_LEGACY_ANNOTATION_LOCATION;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.errorprone.ErrorProneFlags;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class LegacyAndJspecifyModeTest {

  private static final String ERROR =
      "-XepOpt:"
          + FL_LEGACY_ANNOTATION_LOCATION
          + " cannot be used when "
          + FL_JSPECIFY_MODE
          + " is set ";

  @Test
  public void testIllegalStateExceptionUsingReflection() throws Exception {
    ErrorProneFlags flags =
        ErrorProneFlags.builder()
            .putFlag("NullAway:AnnotatedPackages", "com.uber")
            .putFlag("NullAway:JSpecifyMode", "true")
            .putFlag("NullAway:LegacyAnnotationLocations", "true")
            .build();

    Constructor<?> constructor =
        ErrorProneCLIFlagsConfig.class.getDeclaredConstructor(ErrorProneFlags.class);
    constructor.setAccessible(true);

    InvocationTargetException exception =
        assertThrows(
            InvocationTargetException.class,
            () -> constructor.newInstance(flags),
            "Expected IllegalStateException when both jspecifyMode and legacyAnnotationLocation are true.");

    Throwable cause = exception.getCause();
    assertThat(cause, instanceOf(IllegalStateException.class));
    assertEquals(ERROR, cause.getMessage());
  }
}
