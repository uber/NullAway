package com.uber.nullaway.autofixer.results;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
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
  private final List<JSONObject> errors = new ArrayList<>();

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
  public void saveError(ErrorMessage error) {
    JSONObject errorObject = new JSONObject();
    errorObject.put("type", error.getMessageType().toString());
    errorObject.put("message", error.getMessage());
    errors.add(errorObject);
    JSONObject toWrite = new JSONObject();
    toWrite.put("errors", errors);
    try (java.io.Writer writer =
        Files.newBufferedWriter(
            Paths.get("/tmp/NullAwayFix/errors.json"), Charset.defaultCharset())) {
      writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not create the fix json file");
    }
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
    OutputStream os;
    try {
      os = new FileOutputStream("/tmp/NullAwayFix/method_info.csv", true);
      os.write(toWrite.getBytes(Charset.defaultCharset()), 0, toWrite.length());
      os.close();
    } catch (Exception e) {
      System.out.println("Error happened");
    }
  }
}
