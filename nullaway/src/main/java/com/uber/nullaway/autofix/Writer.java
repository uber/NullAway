package com.uber.nullaway.autofix;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.autofix.out.ErrorInfo;
import com.uber.nullaway.autofix.out.Fix;
import com.uber.nullaway.autofix.out.MethodInfo;
import com.uber.nullaway.autofix.out.SeperatedValueDisplay;
import com.uber.nullaway.autofix.out.TrackerNode;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;

public class Writer {
  public static final String ERROR = "/tmp/NullAwayFix/errors.csv";
  public static final String METHOD_INFO = "/tmp/NullAwayFix/method_info.csv";
  public static final String CALL_GRAPH = "/tmp/NullAwayFix/call_graph.csv";
  public static final String SUGGEST_FIX = "/tmp/NullAwayFix/fixes.csv";
  public static final String FIELD_GRAPH = "/tmp/NullAwayFix/field_graph.csv";
  public static final String DELIMITER = "$*$";

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

  public static void saveFieldGraphNode(Tree tree, VisitorState state) {
    TrackerNode node = new TrackerNode(ASTHelpers.getSymbol(tree), state.getPath());
    appendToFile(node, FIELD_GRAPH);
  }

  public static void saveCallGraphNode(Tree tree, VisitorState state) {
    TrackerNode node = new TrackerNode(ASTHelpers.getSymbol(tree), state.getPath());
    appendToFile(node, CALL_GRAPH);
  }

  public static void saveErrorNode(ErrorMessage errorMessage, VisitorState state, boolean deep) {
    ErrorInfo error = new ErrorInfo(errorMessage);
    if (deep) {
      error.findEnclosing(state);
    }
    appendToFile(error, ERROR);
  }

  public static void saveMethodInfo(
      Symbol.MethodSymbol methodSymbol,
      Set<Element> nonnullFieldsAtExit,
      CompilationUnitTree c,
      VisitorState state,
      Config config) {
    String method = methodSymbol.toString();
    String clazz = ASTHelpers.enclosingClass(methodSymbol).toString();
    MethodInfo methodInfo = MethodInfo.findOrCreate(method, clazz);
    methodInfo.setUri(c);
    methodInfo.setNonnullFieldsElements(nonnullFieldsAtExit);
    methodInfo.setParent(methodSymbol, state);
    methodInfo.setParamNumber(methodSymbol.getParameters().size());
    List<Boolean> paramAnnotations = new ArrayList<>();
    for (Symbol.VarSymbol var : methodSymbol.getParameters()) {
      paramAnnotations.add(Nullness.hasNullableAnnotation(var, config));
    }
    methodInfo.setParamAnnotations(paramAnnotations);
    appendToFile(methodInfo, METHOD_INFO);
  }

  private static void resetFile(String path, String header) {
    try {
      Files.deleteIfExists(Paths.get(path));
      OutputStream os = new FileOutputStream(path);
      header += "\n";
      os.write(header.getBytes(Charset.defaultCharset()), 0, header.length());
      os.flush();
      os.close();
    } catch (IOException e) {
      throw new RuntimeException(
          "Could not finish resetting File at Path: " + path + ", Exception: " + e);
    }
  }

  public static void reset(AutoFixConfig config) {
    try {
      Files.createDirectories(Paths.get("/tmp/NullAwayFix/"));
      if (config.SUGGEST_ENABLED) {
        resetFile(SUGGEST_FIX, Fix.header(DELIMITER));
      }
      if (config.LOG_ERROR_ENABLED) {
        resetFile(ERROR, ErrorInfo.header(DELIMITER));
      }
      if (config.MAKE_METHOD_TREE_INHERITANCE_ENABLED) {
        resetFile(METHOD_INFO, MethodInfo.header(DELIMITER));
      }
      if (config.MAKE_CALL_GRAPH_ENABLED) {
        resetFile(CALL_GRAPH, TrackerNode.header(DELIMITER));
      }
      if (config.MAKE_FIELD_GRAPH_ENABLED) {
        resetFile(FIELD_GRAPH, TrackerNode.header(DELIMITER));
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not finish resetting writer: " + e);
    }
  }

  private static void appendToFile(SeperatedValueDisplay value, String filePath) {
    OutputStream os;
    String display = value.display(DELIMITER);
    if (display == null || display.equals("")) {
      return;
    }
    display = display.replaceAll("\\R+", " ").replaceAll("\t", "") + "\n";
    try {
      os = new FileOutputStream(filePath, true);
      os.write(display.getBytes(Charset.defaultCharset()), 0, display.length());
      os.flush();
      os.close();
    } catch (Exception e) {
      System.err.println("Error happened for writing at file: " + filePath);
    }
  }
}
