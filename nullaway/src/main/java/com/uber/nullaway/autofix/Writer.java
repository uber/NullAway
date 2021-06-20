package com.uber.nullaway.autofix;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.explorer.MethodInfo;
import com.uber.nullaway.autofix.fixer.Fix;
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
import org.json.simple.JSONObject;

public class Writer {
  private final List<JSONObject> fixes = new ArrayList<>();
  private final AutoFixConfig config;

  public Writer(Config config) {
    this.config = config.getAutoFixConfig();
  }

  @SuppressWarnings("unchecked")
  public void saveFix(Fix fix) {
    fixes.add(fix.getJson());
    JSONObject toWrite = new JSONObject();
    toWrite.put("fixes", fixes);
    try (java.io.Writer writer =
        Files.newBufferedWriter(
            Paths.get("/tmp/NullAwayFix/fixes.json"), Charset.defaultCharset())) {
      writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not create the fix json file");
    }
  }

  @SuppressWarnings("unchecked")
  public void saveError(ErrorMessage error, VisitorState state) {
    StringBuilder newLine = new StringBuilder();
    final String delimiter = "%*%";
    newLine.append(error.getMessageType().toString()).append(delimiter).append(error.getMessage());
    if (config.LOG_ERROR_DEEP) {
      MethodTree enclosingMethod = ASTHelpers.findEnclosingNode(state.getPath(), MethodTree.class);
      ClassTree enclosingClass = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
      if (enclosingClass != null && enclosingMethod != null) {
        Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(enclosingClass);
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        newLine.append(delimiter).append(classSymbol).append(delimiter).append(methodSymbol);
      }
    }
    newLine.append("\n");
    appendToFile(newLine.toString(), "/tmp/NullAwayFix/errors.csv");
  }

  @SuppressWarnings("unchecked")
  public void saveMethodInfo(
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
    String toWrite = methodInfo + "\n";
    appendToFile(toWrite, "/tmp/NullAwayFix/method_info.csv");
  }

  private void appendToFile(String toWrite, String filePath) {
    OutputStream os;
    try {
      os = new FileOutputStream(filePath, true);
      os.write(toWrite.getBytes(Charset.defaultCharset()), 0, toWrite.length());
      os.close();
    } catch (Exception e) {
      System.out.println("Error happened for writing at file: " + filePath);
    }
  }
}
