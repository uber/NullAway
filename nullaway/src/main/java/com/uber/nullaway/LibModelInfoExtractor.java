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

package com.uber.nullaway;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.utils.CollectionStrategy;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LibModelInfoExtractor {

  public static void main(String[] args) {
    for (String arg : args) {
      processSourceFile(arg);
    }
  }

  public static void processSourceFile(String file) {
    Path root = dirnameToPath(file);
    MinimizerCallback mc = new MinimizerCallback();
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
                sourceRoot.parse("", mc);
              } catch (IOException e) {
                System.err.println("IOException: " + e);
              }
            });
  }

  public static Path dirnameToPath(String dir) {
    File f = new File(dir);
    if (!f.exists()) {
      System.err.printf("Directory %s (%s) does not exist.%n", dir, f);
      System.exit(1);
    }
    if (!f.isDirectory()) {
      System.err.printf("Not a directory: %s (%s).%n", dir, f);
      System.exit(1);
    }
    String absoluteDir = f.getAbsolutePath();
    if (absoluteDir.endsWith("/.")) {
      absoluteDir = absoluteDir.substring(0, absoluteDir.length() - 2);
    }
    return Paths.get(absoluteDir);
  }

  /** Callback to process each Java file; see class documentation for details. */
  private static class MinimizerCallback implements SourceRoot.Callback {

    /** The visitor instance. */
    private final CompilationUnitVisitor mv;

    /** Create a MinimizerCallback instance. */
    public MinimizerCallback() {
      this.mv = new CompilationUnitVisitor();
    }

    @Override
    public Result process(Path localPath, Path absolutePath, ParseResult<CompilationUnit> result) {
      Result res = Result.SAVE;
      Optional<CompilationUnit> opt = result.getResult();
      if (opt.isPresent()) {
        CompilationUnit cu = opt.get();
        visitAll(cu);
      }
      return res;
    }

    @SuppressWarnings("unused")
    public void visitAll(Node rootNode) {
      if (rootNode == null) {
        return;
      }
      ArrayDeque<Node> stack = new ArrayDeque<>();
      stack.addFirst(rootNode);
      while (!stack.isEmpty()) {
        Node current = stack.removeFirst();
        current.accept(mv, null);
        List<Node> children = current.getChildNodes();
        if (children != null) {
          for (Node child : children) {
            stack.addFirst(child);
          }
        }
      }
    }
  }

  private static class CompilationUnitVisitor extends ModifierVisitor<Void> {

    private String packageName = "";

    @Override
    public PackageDeclaration visit(PackageDeclaration n, Void arg) {
      this.packageName = n.getNameAsString();
      System.out.println("Package name: " + packageName);
      return n;
    }

    @Override
    public ClassOrInterfaceDeclaration visit(ClassOrInterfaceDeclaration cid, Void arg) {
      String classOrInterfaceName = packageName + "." + cid.getNameAsString();
      Map<Integer, String> nullableTypeBoundsMap = new HashMap<>();
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
      classOrInterfaceAnnotationsMap.put(classOrInterfaceName, cid.getAnnotations());
      System.out.println("Fully qualified class name: " + classOrInterfaceName);
      nullableTypeBoundsMap.forEach(
          (p, a) -> System.out.println("Nullable Index: " + p + "\tClass: " + a));
      classOrInterfaceAnnotationsMap.forEach(
          (c, a) -> System.out.println("Class: " + c + "\tAnnotations: " + a));
      return cid;
    }

    @Override
    public EnumDeclaration visit(EnumDeclaration ed, Void arg) {
      return ed;
    }

    @Override
    public ConstructorDeclaration visit(ConstructorDeclaration cd, Void arg) {
      return cd;
    }

    @Override
    public MethodDeclaration visit(MethodDeclaration md, Void arg) {
      String methodName = md.getNameAsString();
      Map<String, NodeList<AnnotationExpr>> methodAnnotationsMap = new HashMap<>();
      methodAnnotationsMap.put(methodName, md.getAnnotations());
      methodAnnotationsMap.forEach(
          (m, a) -> System.out.println("Method: " + m + "\tAnnotations: " + a));
      return md;
    }

    @Override
    public FieldDeclaration visit(FieldDeclaration fd, Void arg) {
      return fd;
    }

    @Override
    public InitializerDeclaration visit(InitializerDeclaration id, Void arg) {
      return id;
    }

    @Override
    public NormalAnnotationExpr visit(NormalAnnotationExpr nae, Void arg) {
      return nae;
    }
  }
}
