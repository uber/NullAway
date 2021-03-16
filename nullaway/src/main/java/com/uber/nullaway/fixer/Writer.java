package com.uber.nullaway.fixer;

import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.json.simple.JSONObject;

public class Writer {
  private final List<JSONObject> fixes = new ArrayList<>();
  private final Config config;

  Map<Symbol.MethodSymbol, Set<Element>> methodSymbolSetMap = new HashMap<>();

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

  public void saveMethodInfo(Symbol.MethodSymbol methodSymbol, Set<Element> elements) {
    methodSymbolSetMap.put(methodSymbol, elements);
  }
}
