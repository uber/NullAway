package com.uber.nullaway.autofix;

import com.google.errorprone.VisitorState;
import com.google.errorprone.matchers.MethodInvocation;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.out.ErrorNode;
import com.uber.nullaway.autofix.out.Fix;
import com.uber.nullaway.autofix.out.MethodInfo;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Set;
import javax.lang.model.element.Element;

public class Writer {
  public static final String ERROR = "/tmp/NullAwayFix/errors.csv";
  public static final String METHOD_INFO = "/tmp/NullAwayFix/method_info.csv";
  //  public static final String CALL_GRAPH = "/tmp/NullAwayFix/errors.csv";
  public static final String SUGGEST_FIX = "/tmp/NullAwayFix/fixes.json";
  public static final String DELIMITER = "$*$";
  public static final String DELIMITER_REGEX = "(\\$\\*\\$)";

  public static String getDelimiterRegex() {
    StringBuilder ans = new StringBuilder("(");
    for (int i = 0; i < DELIMITER.length(); i++) {
      ans.append("\\").append(DELIMITER.charAt(i));
    }
    ans.append(")");
    return ans.toString();
  }

  public static void saveFix(Fix fix) {
    appendToFile(fix, SUGGEST_FIX);
  }

  public static void saveError(ErrorMessage errorMessage, VisitorState state, boolean deep) {
    ErrorNode error = new ErrorNode(errorMessage);
    if (deep) {
      error.findEnclosing(state);
    }
    appendToFile(error, ERROR);
  }

  public static void saveMethodInfo(
      Symbol.MethodSymbol methodSymbol,
      Set<Element> nonnullFieldsAtExit,
      CompilationUnitTree c,
      VisitorState state) {
    String method = methodSymbol.toString();
    String clazz = ASTHelpers.enclosingClass(methodSymbol).toString();
    MethodInfo methodInfo = MethodInfo.findOrCreate(method, clazz);
    methodInfo.setUri(c);
    methodInfo.setNonnullFieldsElements(nonnullFieldsAtExit);
    methodInfo.setParent(methodSymbol, state);
    appendToFile(methodInfo, METHOD_INFO);
  }

  public static void saveMethodInvocation(MethodInvocation inv, VisitorState state) {}

  private static void appendToFile(SeperatedValueDisplay value, String filePath) {
    OutputStream os;
    String toWrite = value.display(DELIMITER) + "\n";
    try {
      os = new FileOutputStream(filePath, true);
      os.write(toWrite.getBytes(Charset.defaultCharset()), 0, toWrite.length());
      os.close();
    } catch (Exception e) {
      System.err.println("Error happened for writing at file: " + filePath);
    }
  }
}
