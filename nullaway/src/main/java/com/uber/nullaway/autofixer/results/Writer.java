package com.uber.nullaway.autofixer.results;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Element;
import org.json.simple.JSONObject;

public class Writer {
  private final List<JSONObject> batches = new ArrayList<>();
  private final JSONObject methodInfos = new JSONObject();
  private final List<JSONObject> errors = new ArrayList<>();
  private final Config config;

  public Writer(Config config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public void saveBatch(Batch batch) {
    batches.add(batch.getJson());
    JSONObject toWrite = new JSONObject();
    toWrite.put("batches", batches);
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
      String path,
      CompilationUnitTree c,
      VisitorState state) {
    String method = methodSymbol.toString();
    String clazz = ASTHelpers.enclosingClass(methodSymbol).toString();
    String uri = c.getSourceFile().toUri().toASCIIString();

    MethodInfo methodInfo = MethodInfo.findOrCreate(method, clazz, uri);
    JSONObject toWrite = new JSONObject();
    methodInfos.put(methodInfo.id, methodInfo.getJSON());
    toWrite.put("infos", methodInfos);
    try (java.io.Writer writer =
        Files.newBufferedWriter(Paths.get(path), Charset.defaultCharset())) {
      writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
      writer.flush();
    } catch (IOException e) {
      throw new RuntimeException("Could not create the method info json file");
    }
  }
}
