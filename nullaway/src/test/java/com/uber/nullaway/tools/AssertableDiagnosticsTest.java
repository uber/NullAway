package com.uber.nullaway.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.errorprone.FileObjects;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class AssertableDiagnosticsTest {

  private static final JavaFileObject THREE_LINE_SOURCE =
      FileObjects.forSourceLines("Test.java", "class Test {", "  Object f;", "}");

  @Test
  public void diagnosticOnALineOfACompiledSourcePasses() {
    AssertableDiagnostics.checkEveryDiagnosticIsAssertable(
        List.of(THREE_LINE_SOURCE), List.of(diagnosticOn(THREE_LINE_SOURCE, 2)));
  }

  @Test
  public void diagnosticOnTheLastLinePasses() {
    AssertableDiagnostics.checkEveryDiagnosticIsAssertable(
        List.of(THREE_LINE_SOURCE), List.of(diagnosticOn(THREE_LINE_SOURCE, 3)));
  }

  @Test
  public void diagnosticPastTheEndOfTheFileFails() {
    List<JavaFileObject> sources = List.of(THREE_LINE_SOURCE);
    List<Diagnostic<JavaFileObject>> diagnostics = List.of(diagnosticOn(THREE_LINE_SOURCE, 4));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> AssertableDiagnostics.checkEveryDiagnosticIsAssertable(sources, diagnostics));
    assertThat(error).hasMessageThat().contains("names line 4 of a 3-line file");
  }

  @Test
  public void diagnosticInAFileThatWasNotCompiledFails() {
    JavaFileObject other = FileObjects.forSourceLines("Other.java", "class Other {}");
    List<JavaFileObject> sources = List.of(THREE_LINE_SOURCE);
    List<Diagnostic<JavaFileObject>> diagnostics = List.of(diagnosticOn(other, 1));
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> AssertableDiagnostics.checkEveryDiagnosticIsAssertable(sources, diagnostics));
    assertThat(error).hasMessageThat().contains("was not compiled as a source");
  }

  /**
   * javac reports the notes it summarizes at the end of a compilation against a file rather than
   * against a position in it, and no line-based assertion is meant to reach those.
   */
  @Test
  public void summaryNoteWithoutAPositionPasses() {
    AssertableDiagnostics.checkEveryDiagnosticIsAssertable(
        List.of(THREE_LINE_SOURCE), List.of(diagnosticOn(THREE_LINE_SOURCE, Diagnostic.NOPOS)));
  }

  private static Diagnostic<JavaFileObject> diagnosticOn(JavaFileObject source, long line) {
    return new Diagnostic<>() {
      @Override
      public Kind getKind() {
        return Kind.ERROR;
      }

      @Override
      public JavaFileObject getSource() {
        return source;
      }

      @Override
      public long getPosition() {
        return Diagnostic.NOPOS;
      }

      @Override
      public long getStartPosition() {
        return Diagnostic.NOPOS;
      }

      @Override
      public long getEndPosition() {
        return Diagnostic.NOPOS;
      }

      @Override
      public long getLineNumber() {
        return line;
      }

      @Override
      public long getColumnNumber() {
        return Diagnostic.NOPOS;
      }

      @Override
      public String getCode() {
        return "test";
      }

      @Override
      public String getMessage(Locale locale) {
        return "[NullAway] a diagnostic";
      }
    };
  }
}
