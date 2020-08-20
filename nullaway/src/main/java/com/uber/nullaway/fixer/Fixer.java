package com.uber.nullaway.fixer;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.json.simple.JSONObject;

@SuppressWarnings({
  "UnusedVariable",
  "PackageAccessibility",
  "UnusedMethod"
}) // This class is still under construction
public class Fixer {

  private final Config config;
  private final WriterUtils writerUtils;
  private final HashMap<ErrorMessage.MessageTypes, List<Location.Kind>> messageTypeLocationMap;

  public Fixer(Config config) {
    this.config = config;
    this.writerUtils = new WriterUtils(config);
    messageTypeLocationMap = new HashMap<>();
    fillMessageTypeLocationMap();
    cleanFixOutPutFolder();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void cleanFixOutPutFolder() {
    File file = new File(config.getJsonFileWriterPath());
    file.delete();
  }

  private void fillMessageTypeLocationMap() {}

  public void fix(ErrorMessage errorMessage, Location location, Tree cause) {
    // todo: remove this condition later, for now we are not supporting anonymous classes
    if (ASTHelpers.getSymbol(location.classTree).toString().startsWith("<anonymous")) return;
    Fix fix;
    if (!config.shouldAutoFix()) return;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
        fix = addReturnNullableFix(location);
        break;
      case WRONG_OVERRIDE_PARAM:
        fix = addParamNullableFix(location);
        break;
      case PASS_NULLABLE:
        fix = addParamPassNullableFix(location);
        break;
      case FIELD_NO_INIT:
      case ASSIGN_FIELD_NULLABLE:
        fix = addFieldNullableFix(location);
        break;
      default:
        suggestSuppressWarning(errorMessage, location);
        return;
    }
    if (fix != null) writerUtils.saveFix(fix);
  }

  private Fix addFieldNullableFix(Location location) {
    // todo: return null if @Nonnull exists
    final Fix fix = new Fix();
    fix.location = location;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = true;
    return fix;
  }

  private Fix addParamPassNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.getAnnotationFactory().getNonNull();
    VariableTree variableTree =
        LocationUtils.getVariableTree(
            location.methodTree, (Symbol.VarSymbol) location.variableSymbol);
    if (variableTree != null) {
      final List<? extends AnnotationTree> annotations =
          variableTree.getModifiers().getAnnotations();
      Optional<? extends AnnotationTree> nonNullAnnot =
          Iterables.tryFind(
              annotations, annot -> annot.toString().equals("@" + nonNull.name + "()"));
      if (nonNullAnnot.isPresent()) return null;
      final Fix fix = new Fix();
      fix.location = location;
      fix.annotation = config.getAnnotationFactory().getNullable();
      fix.inject = true;
      return fix;
    }
    return null;
  }

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

  private void suggestSuppressWarning(ErrorMessage errorMessage, Location location) {}

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
        writer.write(toWrite.toJSONString().replace("\\/", "/").replace("\\\\\\", "\\"));
        writer.flush();
      } catch (IOException e) {
        throw new RuntimeException("Could not create the fix json file");
      }
    }
  }
}
