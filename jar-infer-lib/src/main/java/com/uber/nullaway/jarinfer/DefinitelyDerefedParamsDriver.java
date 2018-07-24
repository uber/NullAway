/*
 * Copyright (C) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.jarinfer;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CodeScanner;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.PhantomClass;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.config.FileOfClasses;
import com.ibm.wala.util.warnings.Warnings;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

class Result extends HashMap<String, Set<Integer>> {}

/** Driver for running {@link DefinitelyDerefedParams} */
public class DefinitelyDerefedParamsDriver {
  private static boolean DEBUG = false;
  private static boolean VERBOSE = false;

  public static String lastOutPath = "";
  private static Result map_result = new Result();
  private static Set<String> nullableReturns = new HashSet<>();

  private static final String DEFAULT_ASTUBX_LOCATION = "META-INF/nullaway/jarinfer.astubx";
  // TODO: Exclusions-
  // org.ow2.asm : InvalidBytecodeException on
  // com.ibm.wala.classLoader.ShrikeCTMethod.makeDecoder:110
  private static final String DEFAULT_EXCLUSIONS = "org\\/objectweb\\/asm\\/.*";

  public static Result run(String inPath, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String outPath = "";
    if (inPath.endsWith(".jar") || inPath.endsWith(".aar")) {
      outPath =
          FilenameUtils.getFullPath(inPath)
              + FilenameUtils.getBaseName(inPath)
              + "-ji."
              + FilenameUtils.getExtension(inPath);
    }
    return run(inPath, pkgName, outPath, DEBUG, VERBOSE);
  }
  /**
   * Driver for the analysis. {@link DefinitelyDerefedParams} Usage: DefinitelyDerefedParamsDriver (
   * jar/aar_path, package_name, [output_path])
   *
   * @param inPath Path to input jar/aar file to be analyzed.
   * @param pkgName Qualified package name.
   * @param outPath Path to output processed jar/aar file. Default outPath for 'a/b/c/x.jar' is
   *     'a/b/c/x-ji.jar'.
   * @return Result Map of 'method signatures' to their 'list of NonNull parameters'.
   * @throws IOException on IO error.
   * @throws ClassHierarchyException on Class Hierarchy factory error.
   * @throws IllegalArgumentException on illegal argument to WALA API.
   */
  public static Result run(String inPath, String pkgName, String outPath, boolean dbg, boolean vbs)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    DEBUG = dbg;
    VERBOSE = vbs;
    long start = System.currentTimeMillis();

    InputStream jarIS = getInputStream(inPath);
    if (jarIS != null) {
      AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
      scope.setExclusions(
          new FileOfClasses(
              new ByteArrayInputStream(DEFAULT_EXCLUSIONS.getBytes(StandardCharsets.UTF_8))));
      scope.addInputStreamForJarToScope(ClassLoaderReference.Application, jarIS);
      AnalysisOptions options = new AnalysisOptions(scope, null);
      AnalysisCache cache = new AnalysisCacheImpl();
      IClassHierarchy cha = ClassHierarchyFactory.makeWithPhantom(scope);
      Warnings.clear();

      // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
      for (IClassLoader cldr : cha.getLoaders()) {
        if (!cldr.getName().toString().equals("Primordial")) {
          for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
            if (cls instanceof PhantomClass) continue;
            // Only process classes in specified classpath and not its dependencies.
            // TODO: figure the right way to do this
            if (!pkgName.isEmpty() && !cls.getName().toString().startsWith(pkgName)) continue;
            for (IMethod mtd : Iterator2Iterable.make(cls.getDeclaredMethods().iterator())) {
              // Skip methods without parameters, abstract methods, native methods
              // some Application classes are Primordial (why?)
              if (mtd.getNumberOfParameters() > (mtd.isStatic() ? 0 : 1)
                  && !mtd.isPrivate()
                  && !mtd.isAbstract()
                  && !mtd.isNative()
                  && !isAllPrimitiveTypes(mtd)
                  && !mtd.getDeclaringClass()
                      .getClassLoader()
                      .getName()
                      .toString()
                      .equals("Primordial")) {
                Preconditions.checkNotNull(mtd, "method not found");
                // Skip methods by looking at bytecode
                try {
                  if (CodeScanner.getFieldsRead(mtd).isEmpty()
                      && CodeScanner.getFieldsWritten(mtd).isEmpty()
                      && CodeScanner.getCallSites(mtd).isEmpty()) {
                    continue;
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                }
                // Make CFG
                IR ir =
                    cache
                        .getIRFactory()
                        .makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
                // Analyze parameters
                DefinitelyDerefedParams analysisDriver =
                    new DefinitelyDerefedParams(mtd, ir, cfg, cha);
                Set<Integer> result = analysisDriver.analyze();
                String sign = getSignature(mtd);
                if (!result.isEmpty() || DEBUG) {
                  map_result.put(sign, result);
                }
                // Analyze return value
                if (analysisDriver.analyzeReturnType()
                    == DefinitelyDerefedParams.NullnessHint.NULLABLE) {
                  nullableReturns.add(sign);
                }
              }
            }
          }
        }
      }
      long end = System.currentTimeMillis();
      if (VERBOSE) {
        System.out.println("-----\ndone\ntook " + (end - start) + "ms");
      }
      if (DEBUG) {
        System.out.println("definitely-dereferenced parameters: ");
        for (Map.Entry<String, Set<Integer>> resultEntry : map_result.entrySet()) {
          System.out.println(
              "@ method: " + resultEntry.getKey() + " = " + resultEntry.getValue().toString());
        }
      }
    }
    if (inPath.endsWith(".jar")) {
      writeProcessedJAR(inPath, outPath);
    } else if (inPath.endsWith(".aar")) {
      writeProcessedAAR(inPath, outPath);
    }
    lastOutPath = outPath;
    return map_result;
  }

  /**
   * Checks if all parameters and return value of a method have primitive types.
   *
   * @param mtd Method.
   * @return boolean True if all parameters and return value are of primitive type, otherwise false.
   */
  private static boolean isAllPrimitiveTypes(IMethod mtd) {
    if (!mtd.getReturnType().isPrimitiveType()) return false;
    for (int i = (mtd.isStatic() ? 0 : 1); i < mtd.getNumberOfParameters(); i++) {
      if (!mtd.getParameterType(i).isPrimitiveType()) return false;
    }
    return true;
  }

  /**
   * Get InputStream of the jar of class files to be analyzed.
   *
   * @param libPath Path to input jar / aar file to be analyzed.
   * @return InputStream InputStream for the jar.
   */
  private static InputStream getInputStream(String libPath) throws IOException {
    Preconditions.checkArgument(
        (libPath.endsWith(".jar") || libPath.endsWith(".aar")) && Files.exists(Paths.get(libPath)),
        "invalid library path! " + libPath);
    if (VERBOSE) {
      System.out.println("opening " + libPath + "...");
    }
    InputStream jarIS = null;
    if (libPath.endsWith(".jar")) {
      jarIS = new FileInputStream(libPath);
    } else if (libPath.endsWith(".aar")) {
      ZipFile aar = new ZipFile(libPath);
      ZipEntry jarEntry = aar.getEntry("classes.jar");
      jarIS = (jarEntry == null ? null : aar.getInputStream(jarEntry));
    }
    return jarIS;
  }

  /**
   * Write processed jar with nullability model in jar -> META-INF/nullaway/jarinfer.astubx
   *
   * @param inJarPath Path of input jar file.
   * @param outJarPath Path of output jar file.
   */
  private static void writeProcessedJAR(String inJarPath, String outJarPath) throws IOException {
    Preconditions.checkArgument(
        inJarPath.endsWith(".jar") && Files.exists(Paths.get(inJarPath)),
        "invalid jar file! " + inJarPath);
    writeModelToJarStream(
        new ZipInputStream(new FileInputStream(inJarPath)),
        new ZipOutputStream(new FileOutputStream(outJarPath)));
    if (VERBOSE) {
      System.out.println("processed jar to: " + outJarPath);
    }
  }

  /**
   * Write processed aar with nullability model in aar -> classes.jar ->
   * META-INF/nullaway/jarinfer.astubx
   *
   * @param inAarPath Path of input aar file.
   * @param outAarPath Path of output aar file.
   */
  private static void writeProcessedAAR(String inAarPath, String outAarPath) throws IOException {
    Preconditions.checkArgument(
        inAarPath.endsWith(".aar") && Files.exists(Paths.get(inAarPath)),
        "invalid aar file! " + inAarPath);
    ZipFile zip = new ZipFile(inAarPath);
    ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outAarPath));
    for (Enumeration zes = zip.entries(); zes.hasMoreElements(); ) {
      ZipEntry ze = (ZipEntry) zes.nextElement();
      zos.putNextEntry(new ZipEntry(ze.getName()));
      if (ze.getName().endsWith("classes.jar")) {
        writeModelToJarStream(new ZipInputStream(zip.getInputStream(ze)), new ZipOutputStream(zos));
      } else {
        IOUtils.copy(zip.getInputStream(ze), zos);
      }
      zos.closeEntry();
    }
    zip.close();
    zos.close();
    if (VERBOSE) {
      System.out.println("processed aar to: " + outAarPath);
    }
  }
  /**
   * Copy Jar Input Stream to Jar Output Stream and add nullability model.
   *
   * @param zis Jar Input Stream.
   * @param zos Jar Output Stream.
   */
  private static void writeModelToJarStream(ZipInputStream zis, ZipOutputStream zos)
      throws IOException {
    for (ZipEntry ze; (ze = zis.getNextEntry()) != null; ) {
      zos.putNextEntry(ze);
      IOUtils.copy(zis, zos);
      zos.closeEntry();
    }
    zis.close();
    if (!map_result.isEmpty()) {
      zos.putNextEntry(new ZipEntry(DEFAULT_ASTUBX_LOCATION));
      writeModel(new DataOutputStream(zos));
      zos.closeEntry();
    }
    zos.finish();
  }

  /**
   * Write inferred nullability model in astubx format to the JarOutputStream for the processed
   * jar/aar.
   *
   * @param out JarOutputStream for writing the astubx
   */
  //  Note: Need version compatibility check between generated stub files and when reading models
  //    StubxWriter.VERSION_0_FILE_MAGIC_NUMBER (?)
  private static void writeModel(DataOutputStream out) throws IOException {
    Map<String, String> importedAnnotations =
        new HashMap<String, String>() {
          {
            put("Nonnull", "javax.annotation.Nonnull");
            put("Nullable", "javax.annotation.Nullable");
          }
        };
    Map<String, Set<String>> packageAnnotations = new HashMap<>();
    Map<String, Set<String>> typeAnnotations = new HashMap<>();
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();

    for (Map.Entry<String, Set<Integer>> entry : map_result.entrySet()) {
      String sign = entry.getKey();
      Set<Integer> ddParams = entry.getValue();
      if (ddParams.isEmpty() && !nullableReturns.contains(sign)) continue;
      Map<Integer, ImmutableSet<String>> argAnnotation =
          new HashMap<Integer, ImmutableSet<String>>();
      for (Integer param : ddParams) {
        argAnnotation.put(param, ImmutableSet.of("Nonnull"));
      }
      methodRecords.put(
          sign,
          new MethodAnnotationsRecord(
              nullableReturns.contains(sign) ? ImmutableSet.of("Nullable") : ImmutableSet.of(),
              ImmutableMap.copyOf(argAnnotation)));
      nullableReturns.remove(sign);
    }
    for (String nullableReturnMethodSign : Iterator2Iterable.make(nullableReturns.iterator())) {
      methodRecords.put(
          nullableReturnMethodSign,
          new MethodAnnotationsRecord(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    }
    StubxWriter.write(out, importedAnnotations, packageAnnotations, typeAnnotations, methodRecords);
  }

  /**
   * Get astubx style method signature. {FullyQualifiedEnclosingType}: {UnqualifiedMethodReturnType}
   * {methodName} ([{UnqualifiedArgumentType}*])
   *
   * @param mtd Method reference.
   * @return String Method signature.
   */
  // TODO: handle generics and inner classes
  private static String getSignature(IMethod mtd) {
    String classType =
        mtd.getDeclaringClass().getName().toString().replaceAll("/", "\\.").substring(1);
    classType = classType.replaceAll("\\$", "\\."); // handle inner class
    String returnType = mtd.isInit() ? null : getSimpleTypeName(mtd.getReturnType());
    String strArgTypes = "";
    int argi = mtd.isStatic() ? 0 : 1; // Skip 'this' parameter
    for (; argi < mtd.getNumberOfParameters(); argi++) {
      strArgTypes += getSimpleTypeName(mtd.getParameterType(argi));
      if (argi < mtd.getNumberOfParameters() - 1) strArgTypes += ", ";
    }
    return classType
        + ":"
        + (returnType == null ? "void " : returnType + " ")
        + mtd.getName().toString()
        + "("
        + strArgTypes
        + ")";
  }
  /**
   * Get simple unqualified type name.
   *
   * @param typ Type Reference.
   * @return String Unqualified type name.
   */
  private static String getSimpleTypeName(TypeReference typ) {
    final Map<String, String> mapFullTypeName =
        new HashMap<String, String>() {
          {
            put("B", "byte");
            put("C", "char");
            put("D", "double");
            put("F", "float");
            put("I", "int");
            put("J", "long");
            put("S", "short");
            put("Z", "boolean");
          }
        };
    if (typ.isArrayType()) return "Array";
    String typName = typ.getName().toString();
    if (typName.startsWith("L")) {
      typName = typName.split("<")[0].substring(1); // handle generics
      typName = typName.substring(typName.lastIndexOf('/') + 1); // get unqualified name
      typName = typName.substring(typName.lastIndexOf('$') + 1); // handle inner classes
    } else {
      typName = mapFullTypeName.get(typName);
    }
    return typName;
  }
}
