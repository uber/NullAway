package com.uber.nullaway.autofix;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.out.CallGraphNode;
import com.uber.nullaway.autofix.out.ErrorNode;
import com.uber.nullaway.autofix.out.Fix;
import com.uber.nullaway.autofix.out.MethodInfo;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import javax.lang.model.element.Element;

public class Writer {
  public static final String ERROR = "/tmp/NullAwayFix/errors.csv";
  public static final String METHOD_INFO = "/tmp/NullAwayFix/method_info.csv";
  public static final String CALL_GRAPH = "/tmp/NullAwayFix/call_graph.csv";
  public static final String SUGGEST_FIX = "/tmp/NullAwayFix/fixes.csv";
  public static final String DELIMITER = "$*$";

  private static boolean firstFix = true;
  private static boolean firstErrorNode = true;
  private static boolean firstMethodInfo = true;
  private static boolean firstCallGraphNode = true;

  public static String getDelimiterRegex() {
    StringBuilder ans = new StringBuilder("(");
    for (int i = 0; i < DELIMITER.length(); i++) {
      ans.append("\\").append(DELIMITER.charAt(i));
    }
    ans.append(")");
    return ans.toString();
  }

  public static void saveFix(Fix fix) {
    appendToFile(fix, SUGGEST_FIX, firstFix);
    firstFix = false;
  }

  public static void saveErrorNode(ErrorMessage errorMessage, VisitorState state, boolean deep) {
    ErrorNode error = new ErrorNode(errorMessage);
    if (deep) {
      error.findEnclosing(state);
    }
    appendToFile(error, ERROR, firstErrorNode);
    firstErrorNode = false;
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
    appendToFile(methodInfo, METHOD_INFO, firstMethodInfo);
    firstMethodInfo = false;
  }

  public static void saveCallGraphNode(CallGraphNode node) {
    appendToFile(node, CALL_GRAPH, firstCallGraphNode);
    firstCallGraphNode = false;
  }

  public static void reset(AutoFixConfig config) {
    try {
      Files.createDirectories(Paths.get("/tmp/NullAwayFix/"));
      if (config.SUGGEST_ENABLED) {
        Files.deleteIfExists(Paths.get(SUGGEST_FIX));
      }
      if (config.LOG_ERROR_ENABLED) {
        Files.deleteIfExists(Paths.get(ERROR));
      }
      if (config.MAKE_METHOD_TREE_INHERITANCE_ENABLED) {
        Files.deleteIfExists(Paths.get(METHOD_INFO));
      }
      if (config.MAKE_CALL_GRAPH_ENABLED) {
        Files.deleteIfExists(Paths.get(CALL_GRAPH));
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not finish resetting writer: " + e);
    }
    firstFix = true;
    firstErrorNode = true;
    firstMethodInfo = true;
    firstCallGraphNode = true;
  }

  private static void appendToFile(
      SeperatedValueDisplay value, String filePath, boolean withHeader) {
    OutputStream os;
    String toWrite = "";
    if (withHeader) {
      toWrite = value.header(DELIMITER) + "\n";
    }
    toWrite += value.display(DELIMITER) + "\n";
    try {
      os = new FileOutputStream(filePath, true);
      os.write(toWrite.getBytes(Charset.defaultCharset()), 0, toWrite.length());
      os.close();
    } catch (Exception e) {
      System.err.println("Error happened for writing at file: " + filePath);
    }
  }
}
