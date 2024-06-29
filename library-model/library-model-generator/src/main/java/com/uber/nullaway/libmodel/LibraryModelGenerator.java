/*
 * Copyright (c) 2024 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.libmodel;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.CollectionStrategy;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utilized for generating an astubx file from a directory containing annotated Java source code.
 *
 * <p>This class utilizes com.github.javaparser APIs to analyze Java source files within a specified
 * directory. It processes the annotated Java source code to generate an astubx file that contains
 * the required annotation information to be able to generate library models.
 */
public class LibraryModelGenerator {

  /**
   * Parses all the source files within the directory using javaparser.
   *
   * @param inputSourceDirectory Directory containing annotated java source files.
   * @param outputDirectory Directory to write the astubx file into.
   */
  public void generateAstubxForLibraryModels(String inputSourceDirectory, String outputDirectory) {
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();
    Map<String, Set<Integer>> nullableUpperBounds = new LinkedHashMap<>();
    Path root = dirnameToPath(inputSourceDirectory);
    AnnotationCollectorCallback ac =
        new AnnotationCollectorCallback(methodRecords, nullableUpperBounds);
    CollectionStrategy strategy = new ParserCollectionStrategy();
    // Required to include directories that contain a module-info.java, which don't parse by
    // default.
    strategy.getParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_17);
    ProjectRoot projectRoot = strategy.collect(root);

    projectRoot
        .getSourceRoots()
        .forEach(
            sourceRoot -> {
              try {
                sourceRoot.parse("", ac);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    writeToAstubx(outputDirectory, methodRecords, nullableUpperBounds);
  }

  /**
   * Writes the Nullability annotation information into the output directory as an astubx file.
   *
   * @param outputPath Output Directory.
   * @param methodRecords Map containing the collected Nullability annotation information.
   */
  private void writeToAstubx(
      String outputPath,
      Map<String, MethodAnnotationsRecord> methodRecords,
      Map<String, Set<Integer>> nullableUpperBounds) {
    if (methodRecords.isEmpty() && nullableUpperBounds.isEmpty()) {
      return;
    }
    Map<String, String> importedAnnotations =
        ImmutableMap.of(
            "NonNull", "org.jspecify.annotations.NonNull",
            "Nullable", "org.jspecify.annotations.Nullable");
    Path outputPathInstance = Paths.get(outputPath);
    try {
      Files.createDirectories(outputPathInstance.getParent());
      try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(outputPathInstance))) {
        StubxWriter.write(
            dos,
            importedAnnotations,
            Collections.emptyMap(),
            Collections.emptyMap(),
            methodRecords,
            nullableUpperBounds);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public Path dirnameToPath(String dir) {
    File f = new File(dir);
    String absoluteDir = f.getAbsolutePath();
    if (absoluteDir.endsWith("/.")) {
      absoluteDir = absoluteDir.substring(0, absoluteDir.length() - 2);
    }
    return Paths.get(absoluteDir);
  }

  private static class AnnotationCollectorCallback implements SourceRoot.Callback {

    private final AnnotationCollectionVisitor annotationCollectionVisitor;

    public AnnotationCollectorCallback(
        Map<String, MethodAnnotationsRecord> methodRecords,
        Map<String, Set<Integer>> nullableUpperBounds) {
      this.annotationCollectionVisitor =
          new AnnotationCollectionVisitor(methodRecords, nullableUpperBounds);
    }

    @Override
    public Result process(Path localPath, Path absolutePath, ParseResult<CompilationUnit> result) {
      Result res = Result.SAVE;
      Optional<CompilationUnit> opt = result.getResult();
      if (opt.isPresent()) {
        CompilationUnit cu = opt.get();
        cu.accept(annotationCollectionVisitor, null);
      }
      return res;
    }
  }

  private static class AnnotationCollectionVisitor extends VoidVisitorAdapter<Void> {

    private String parentName = "";
    private boolean isJspecifyNullableImportPresent = false;
    private boolean isNullMarked = false;
    private final Map<String, MethodAnnotationsRecord> methodRecords;
    private final Map<String, Set<Integer>> nullableUpperBounds;
    private static final String ARRAY_RETURN_TYPE_STRING = "Array";
    private static final String NULL_MARKED = "NullMarked";
    private static final String NULLABLE = "Nullable";
    private static final String JSPECIFY_NULLABLE_IMPORT = "org.jspecify.annotations.Nullable";

    public AnnotationCollectionVisitor(
        Map<String, MethodAnnotationsRecord> methodRecords,
        Map<String, Set<Integer>> nullableUpperBounds) {
      this.methodRecords = methodRecords;
      this.nullableUpperBounds = nullableUpperBounds;
    }

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
      this.parentName = pd.getNameAsString();
      super.visit(pd, null);
    }

    @Override
    public void visit(ImportDeclaration id, Void arg) {
      if (id.getName().toString().contains(JSPECIFY_NULLABLE_IMPORT)) {
        this.isJspecifyNullableImportPresent = true;
      }
      super.visit(id, null);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
      /*This logic assumes an explicit @NullMarked annotation on the top-level class within a
      source file, and it's expected that each source file contains only one top-level class. The
      logic does not currently handle cases where @NullMarked annotations appear on some nested
      classes but not others. It also does not consider annotations within package-info.java or
      module-info.java files.*/
      parentName += "." + cid.getNameAsString();
      cid.getAnnotations()
          .forEach(
              a -> {
                if (a.getNameAsString().equalsIgnoreCase(NULL_MARKED)) {
                  this.isNullMarked = true;
                }
              });
      if (this.isNullMarked) {
        Set<Integer> nullableUpperBoundParams = getGenericTypeParameterNullableUpperBounds(cid);
        if (!nullableUpperBoundParams.isEmpty()) {
          nullableUpperBounds.put(parentName, nullableUpperBoundParams);
        }
      }
      super.visit(cid, null);
      // We reset the variable that constructs the parent name after visiting all the children.
      parentName = parentName.substring(0, parentName.lastIndexOf("." + cid.getNameAsString()));
    }

    @Override
    public void visit(MethodDeclaration md, Void arg) {
      if (this.isNullMarked && hasNullableReturn(md)) {
        methodRecords.put(
            parentName + ":" + getMethodReturnTypeString(md) + " " + md.getSignature().toString(),
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
      }
      super.visit(md, null);
    }

    /**
     * Determines if a MethodDeclaration can return null.
     *
     * @param md The MethodDeclaration instance.
     * @return {@code true} if the method can return null, {@code false} otherwise.
     */
    private boolean hasNullableReturn(MethodDeclaration md) {
      if (md.getType() instanceof ArrayType) {
        /* For an Array return type the annotation is on the type when the Array instance is
        Nullable(Object @Nullable []) and on the node when the elements inside are
        Nullable(@Nullable Object []) */
        for (AnnotationExpr annotation : md.getType().getAnnotations()) {
          if (isAnnotationNullable(annotation)) {
            return true;
          }
        }
      } else {
        for (AnnotationExpr annotation : md.getAnnotations()) {
          if (isAnnotationNullable(annotation)) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Takes a MethodDeclaration and returns the String value for the return type that will be
     * written into the astubx file.
     *
     * @param md The MethodDeclaration instance.
     * @return The return type string value to be written into the astubx file.
     */
    private String getMethodReturnTypeString(MethodDeclaration md) {
      if (md.getType() instanceof ClassOrInterfaceType) {
        return md.getType().getChildNodes().get(0).toString();
      } else if (md.getType() instanceof ArrayType) {
        return ARRAY_RETURN_TYPE_STRING;
      } else {
        return md.getType().toString();
      }
    }

    private boolean isAnnotationNullable(AnnotationExpr annotation) {
      // We only consider jspecify Nullable annotations(star imports are not supported).
      return (annotation.getNameAsString().equalsIgnoreCase(NULLABLE)
              && this.isJspecifyNullableImportPresent)
          || annotation.getNameAsString().equalsIgnoreCase(JSPECIFY_NULLABLE_IMPORT);
    }

    /**
     * Takes a ClassOrInterfaceDeclaration instance and returns a Map of the indexes and a set of
     * annotations for generic type parameters with Nullable upper bounds.
     *
     * @param cid ClassOrInterfaceDeclaration instance.
     * @return Set of indices for generic type parameters with Nullable upper bounds.
     */
    private ImmutableSet<Integer> getGenericTypeParameterNullableUpperBounds(
        ClassOrInterfaceDeclaration cid) {
      ImmutableSet.Builder<Integer> setBuilder = ImmutableSet.builder();
      List<TypeParameter> typeParamList = cid.getTypeParameters();
      for (int i = 0; i < typeParamList.size(); i++) {
        TypeParameter param = typeParamList.get(i);
        for (ClassOrInterfaceType type : param.getTypeBound()) {
          Optional<AnnotationExpr> nullableAnnotation = type.getAnnotationByName(NULLABLE);
          if (nullableAnnotation.isPresent() && isAnnotationNullable(nullableAnnotation.get())) {
            setBuilder.add(i);
          }
        }
      }
      return setBuilder.build();
    }
  }
}
