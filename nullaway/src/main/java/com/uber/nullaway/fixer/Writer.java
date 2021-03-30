package com.uber.nullaway.fixer;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class Writer {
  private final List<JSONObject> fixes = new ArrayList<>();
  private final List<JSONObject> methodInfo = new ArrayList<>();
  private final Config config;

  Writer(Config config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public void saveFix(Fix fix) {
    fixes.add(fix.getJson());
    JSONObject toWrite = new JSONObject();
    toWrite.put("fixes", fixes);
    try (java.io.Writer writer =
        Files.newBufferedWriter(
            Paths.get(config.getJsonFileWriterPath()), Charset.defaultCharset())) {
      writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not create the fix json file");
    }
  }

  @SuppressWarnings("unchecked")
  public void saveMethodInfo(
      Symbol.MethodSymbol methodSymbol, Set<Element> elements, String path, CompilationUnitTree c) {
    JSONObject info = new JSONObject();
    info.put("method", methodSymbol.toString());
    info.put("class", ASTHelpers.enclosingClass(methodSymbol).toString());
    info.put("uri", c.getSourceFile().toUri().toASCIIString());
    JSONArray fields = new JSONArray();
    for (Element element : elements) {
      fields.add(element.getSimpleName().toString());
    }
    info.put("fields", fields);
    if (fields.size() < 1) {
      return;
    }
    methodInfo.add(info);
    JSONObject toWrite = new JSONObject();
    toWrite.put("infos", methodInfo);
    try (java.io.Writer writer =
        Files.newBufferedWriter(Paths.get(path), Charset.defaultCharset())) {
      writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not create the method info json file");
    }
  }
}
