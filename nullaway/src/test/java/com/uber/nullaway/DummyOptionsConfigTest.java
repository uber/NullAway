package com.uber.nullaway;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DummyOptionsConfigTest {

  DummyOptionsConfig dummyOptionsConfig;

  @Before
  public void setup() {
    dummyOptionsConfig = new DummyOptionsConfig();
  }

  @Test
  public void allDeclaredMethodsThrowIllegalStateException() {
    // DummyOptionsConfig is expected to throw a runtime exception if ever used (see documentation
    // on that class)
    // this test guarantees that all methods declared in the class throw the exception
    Class<? extends DummyOptionsConfig> klass = dummyOptionsConfig.getClass();
    for (Method method : klass.getDeclaredMethods()) {
      if (method.getName().contains("jacocoInit")) {
        // Declared method added by jacoco coverage reporting (via reflection?). Plots within
        // plots...
        continue;
      }
      Class<?>[] parameterTypes = method.getParameterTypes();
      Object[] nullParams = Arrays.stream(parameterTypes).map((t) -> null).toArray();
      Exception reflectionException =
          assertThrows(
              InvocationTargetException.class,
              () -> {
                method.invoke(dummyOptionsConfig, nullParams);
              },
              String.format(
                  "Expected method DummyOptionsConfig.%s to fail with IllegalStateException.",
                  method.getName()));
      // The real exception, not wrapped by reflection exceptions
      Throwable cause = reflectionException.getCause();
      assertTrue(cause instanceof IllegalStateException);
      IllegalStateException exception = (IllegalStateException) cause;
      assertTrue(exception.getMessage().equals(DummyOptionsConfig.ERROR_MESSAGE));
    }
  }
}
