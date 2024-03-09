/*
 * Copyright (c) 2019 Uber Technologies, Inc.
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
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Utilized for generating an astubx file from a directory containing annotated Java source code.
 *
 * <p>This class utilizes com.github.javaparser APIs to analyze Java source files within a specified
 * directory. It processes the annotated Java source code to generate an astubx file that contains
 * the required annotation information to be able to generate library models.
 */
public class LibraryModelGenerator {

  public void generateAstubxForLibraryModels(String inputSourceDirectory, String outputDirectory) {
    Map<String, MethodAnnotationsRecord> methodRecords = processDirectory(inputSourceDirectory);
    writeToAstubx(outputDirectory, methodRecords);
  }

  /**
   * Parses all the source files within the directory using javaparser.
   *
   * @param sourceDirectoryRoot Directory containing annotated java source files.
   * @return a Map containing the Nullability annotation information from the source files.
   */
  private Map<String, MethodAnnotationsRecord> processDirectory(String sourceDirectoryRoot) {
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();
    Path root = dirnameToPath(sourceDirectoryRoot);
    AnnotationCollectorCallback ac = new AnnotationCollectorCallback(methodRecords);
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
    return methodRecords;
  }

  /**
   * Writes the Nullability annotation information into the output directory as an astubx file.
   *
   * @param outputPath Output Directory.
   * @param methodRecords Map containing the collected Nullability annotation information.
   */
  private void writeToAstubx(
      String outputPath, Map<String, MethodAnnotationsRecord> methodRecords) {
    if (methodRecords.isEmpty()) {
      return;
    }
    Map<String, String> importedAnnotations =
        ImmutableMap.of(
            "Nonnull", "javax.annotation.Nonnull",
            "Nullable", "javax.annotation.Nullable");
    Path outputPathInstance = Paths.get(outputPath);
    try {
      Files.createDirectories(outputPathInstance.getParent());
      try (DataOutputStream dos = new DataOutputStream(Files.newOutputStream(outputPathInstance))) {
        StubxWriter.write(
            dos,
            importedAnnotations,
            Collections.emptyMap(),
            Collections.emptyMap(),
            methodRecords);
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

    public AnnotationCollectorCallback(Map<String, MethodAnnotationsRecord> methodRecords) {
      this.annotationCollectionVisitor = new AnnotationCollectionVisitor(methodRecords);
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

    private String packageName = "";
    private boolean isJspecifyNullableImportPresent = false;
    private Map<String, MethodAnnotationsRecord> methodRecords;
    private static final String ARRAY_RETURN_TYPE_STRING = "Array";
    private static final String JSPECIFY_NULLABLE_IMPORT = "org.jspecify.annotations.Nullable";

    public AnnotationCollectionVisitor(Map<String, MethodAnnotationsRecord> methodRecords) {
      this.methodRecords = methodRecords;
    }

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
      this.packageName = pd.getNameAsString();
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
      String classOrInterfaceName = packageName + "." + cid.getNameAsString();
      @SuppressWarnings("all")
      Map<Integer, String> nullableTypeBoundsMap = new HashMap<>();
      List<TypeParameter> paramList = cid.getTypeParameters();
      // Finding Nullable upper bounds for generic type parameters.
      for (int i = 0; i < paramList.size(); i++) {
        boolean hasNullableUpperBound = false;
        NodeList<ClassOrInterfaceType> upperBoundList = paramList.get(i).getTypeBound();
        for (ClassOrInterfaceType upperBound : upperBoundList) {
          if (upperBound.isAnnotationPresent("Nullable")) {
            hasNullableUpperBound = true;
            break;
          }
        }
        if (hasNullableUpperBound) {
          nullableTypeBoundsMap.put(i, classOrInterfaceName);
        }
      }
      super.visit(cid, null);
    }

    @Override
    public void visit(MethodDeclaration md, Void arg) {
      Optional<Node> parentClassNode = md.getParentNode();
      String parentClassName = "";
      if (parentClassNode.isPresent()) {
        if (parentClassNode.get() instanceof ClassOrInterfaceDeclaration) {
          parentClassName = ((ClassOrInterfaceDeclaration) parentClassNode.get()).getNameAsString();
        } else if (parentClassNode.get() instanceof EnumDeclaration) {
          parentClassName = ((EnumDeclaration) parentClassNode.get()).getNameAsString();
        }
      }
      if (hasNullableReturn(md)) {
        methodRecords.put(
            packageName
                + "."
                + parentClassName
                + ":"
                + getMethodReturnTypeString(md)
                + " "
                + md.getSignature().toString(),
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
      // Since we only want to consider jspecify Nullable annotations.
      return (annotation.getNameAsString().equalsIgnoreCase("Nullable")
              && this.isJspecifyNullableImportPresent)
          || annotation.getNameAsString().equalsIgnoreCase(JSPECIFY_NULLABLE_IMPORT);
    }
  }
}
