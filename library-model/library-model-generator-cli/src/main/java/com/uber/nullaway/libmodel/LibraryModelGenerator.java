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

  /**
   * This is the main method of the cli tool. It parses the source files within a specified
   * directory, obtains meaningful Nullability annotation information and writes it into an astubx
   * file.
   *
   * @param args Command line arguments for the directory containing source files and the output
   *     directory.
   */
  public static void main(String[] args) {
    LibraryModelGenerator libraryModelGenerator = new LibraryModelGenerator();
    libraryModelGenerator.generateAstubxForLibraryModels(args[0], args[1]);
  }

  public void generateAstubxForLibraryModels(String inputSourceDirectory, String outputDirectory) {
    Map<String, MethodAnnotationsRecord> methodRecords = processDirectory(inputSourceDirectory);
    writeToAstubx(outputDirectory, methodRecords);
  }

  /**
   * Parses the source files within the directory using javaparser.
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
    private Map<String, MethodAnnotationsRecord> methodRecords;

    public AnnotationCollectionVisitor(Map<String, MethodAnnotationsRecord> methodRecords) {
      this.methodRecords = methodRecords;
    }

    @Override
    public void visit(PackageDeclaration pd, Void arg) {
      this.packageName = pd.getNameAsString();
      super.visit(pd, null);
    }

    @Override
    public void visit(ClassOrInterfaceDeclaration cid, Void arg) {
      String classOrInterfaceName = packageName + "." + cid.getNameAsString();
      @SuppressWarnings("all")
      Map<Integer, String> nullableTypeBoundsMap = new HashMap<>();
      @SuppressWarnings("all")
      Map<String, NodeList<AnnotationExpr>> classOrInterfaceAnnotationsMap = new HashMap<>();
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
      // Finding All the annotations on the class or interface.
      if (cid.getAnnotations().isNonEmpty()) {
        classOrInterfaceAnnotationsMap.put(classOrInterfaceName, cid.getAnnotations());
      }
      /*System.out.println("Fully qualified class name: " + classOrInterfaceName);
      nullableTypeBoundsMap.forEach(
          (p, a) -> System.out.println("Nullable Index: " + p + "\tClass: " + a));
      classOrInterfaceAnnotationsMap.forEach(
          (c, a) -> System.out.println("Class: " + c + "\tAnnotations: " + a));*/
      super.visit(cid, null);
    }

    @Override
    public void visit(MethodDeclaration md, Void arg) {
      String methodName = md.getNameAsString();
      Optional<Node> parentClassNode = md.getParentNode();
      String parentClassName = "";
      if (parentClassNode.isPresent()) {
        if (parentClassNode.get() instanceof ClassOrInterfaceDeclaration) {
          parentClassName = ((ClassOrInterfaceDeclaration) parentClassNode.get()).getNameAsString();
        } else if (parentClassNode.get() instanceof EnumDeclaration) {
          parentClassName = ((EnumDeclaration) parentClassNode.get()).getNameAsString();
        }
      }
      String methodSignature = md.getSignature().toString();
      @SuppressWarnings("all")
      Map<String, NodeList<AnnotationExpr>> methodAnnotationsMap = new HashMap<>();
      Map<String, String> nullableReturnMethods = new HashMap<>();
      boolean isNullableAnnotationPresent = false;
      if (md.getAnnotations().isNonEmpty()) {
        methodAnnotationsMap.put(methodName, md.getAnnotations());
      }
      /*methodAnnotationsMap.forEach(
      (m, a) -> System.out.println("Method: " + m + "\tAnnotations: " + a));*/
      for (AnnotationExpr annotation : md.getAnnotations()) {
        if (annotation.getNameAsString().equalsIgnoreCase("Nullable")) {
          isNullableAnnotationPresent = true;
          break;
        }
      }
      String methodReturnType = "";
      if (md.getType() instanceof ClassOrInterfaceType) {
        methodReturnType = md.getType().getChildNodes().get(0).toString();
      } else if (md.getType() instanceof ArrayType) {
        // methodReturnType = "Array";
        // TODO: Figure out the right way to handle Array types
        // For now we don't consider it as Nullable
        isNullableAnnotationPresent = false;
      } else {
        methodReturnType = md.getType().toString();
      }
      if (isNullableAnnotationPresent) {
        nullableReturnMethods.put(packageName + "." + parentClassName, methodSignature);
        methodRecords.put(
            packageName + "." + parentClassName + ":" + methodReturnType + " " + methodSignature,
            MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
      }
      nullableReturnMethods.forEach(
          (c, s) -> System.out.println("Enclosing Class: " + c + "\tMethod Signature: " + s));
      super.visit(md, null);
    }
  }
}
