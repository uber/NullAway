package com.uber.nullaway.fixer;

import com.google.common.collect.Iterables;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.uber.nullaway.AnnotationFactory;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.simple.JSONObject;

@SuppressWarnings({
  "UnusedVariable",
  "PackageAccessibility"
}) // This class is still under construction
public class Fixer {

  private final Config config;
  private final WriterUtils writerUtils;

  public Fixer(Config config) {
    this.config = config;
    this.writerUtils = new WriterUtils(config);
  }

  public void fix(ErrorMessage errorMessage, Location location) {
    Fix fix = new Fix();
    if (!config.shouldAutoFix()) return;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
        fix = addReturnNullableFix(location);
        break;
      case WRONG_OVERRIDE_PARAM:
        fix = addParamNullableFix(location);
        break;
      default:
        suggestSuppressWarning(errorMessage, location);
    }
    writerUtils.saveFix(fix);
  }

  private void suggestSuppressWarning(ErrorMessage errorMessage, Location location) {}

  private Fix addParamNullableFix(Location location) {
    if (!location.kind.equals(Location.Kind.METHOD_PARAM)) {
      throw new RuntimeException(
          "Incompatible Fix Call: Cannot fix location type: "
              + location.kind.label
              + " with this method: addParamNullableFix");
    }
    final Fix fix = new Fix();
    fix.location = location;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = true;
    return fix;
  }

  private Fix addReturnNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.getAnnotationFactory().getNonNull();

    if (!location.kind.equals(Location.Kind.METHOD_RETURN)) {
      throw new RuntimeException(
          "Incompatible Fix Call: Cannot fix location type: "
              + location.kind.label
              + " with this method: addReturnNullableFix");
    }
    final Fix fix = new Fix();
    final ModifiersTree modifiers = location.methodTree.getModifiers();

    final List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
    // noinspection ConstantConditions
    com.google.common.base.Optional<? extends AnnotationTree> nonNullAnnot =
        Iterables.tryFind(
            annotations, annot -> annot.getAnnotationType().toString().endsWith(nonNull.name));
    fix.location = location;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = !nonNullAnnot.isPresent();
    return fix;
  }

  @SuppressWarnings("unchecked")
  static class WriterUtils {

    private final List<JSONObject> fixes = new ArrayList<>();
    private final Config config;

    WriterUtils(Config config) {
      this.config = config;
    }

    void saveFix(Fix fix) {
      fixes.add(fix.getJson());
      JSONObject toWrite = new JSONObject();
      toWrite.put("fixes", fixes);
      try (Writer writer =
          Files.newBufferedWriter(
              Paths.get(config.getJsonFileWriterPath()), Charset.defaultCharset())) {
        writer.write(toWrite.toJSONString().replace("\\", ""));
        writer.flush();
      } catch (IOException e) {
        throw new RuntimeException("Could not create the fix json file");
      }
    }
  }
}
