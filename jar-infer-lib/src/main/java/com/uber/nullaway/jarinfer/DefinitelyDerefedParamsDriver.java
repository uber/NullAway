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
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
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
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.warnings.Warnings;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

class Result extends HashMap<IMethod, Set<Integer>> {}

/** Driver for running {@link DefinitelyDerefedParams} */
public class DefinitelyDerefedParamsDriver {
  /**
   * Driver for the analysis. {@link DefinitelyDerefedParams} Usage: DefinitelyDerefedParamsDriver (
   * jar/aar_path, package_name, [output_path])
   *
   * @param inPath Path to input jar/aar file to be analyzed.
   * @param pkgName Qualified package name.
   * @param outPath Path to output processed jar/aar file. default outPath for 'a/b/c/x.jar' is
   *     'a/b/c/x-ji.jar'.
   * @return HashMap<String, Set<Integer>> Map of 'method signatures' to their 'list of NonNull
   *     parameters'.
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws IllegalArgumentException
   */
  private static final String DEFAULT_ASTUBX_LOACTION = "META-INF/nullaway/jarinfer.astubx";

  public static HashMap<String, Set<Integer>> run(String inPath, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String outPath = "";
    if (inPath.endsWith(".jar") || inPath.endsWith(".aar")) {
      outPath =
          FilenameUtils.getFullPath(inPath)
              + FilenameUtils.getBaseName(inPath)
              + "-ji."
              + FilenameUtils.getExtension(inPath);
    }
    return run(inPath, pkgName, outPath);
  }

  public static HashMap<String, Set<Integer>> run(String inPath, String pkgName, String outPath)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String workDir = inPath;
    long start = System.currentTimeMillis();
    if (inPath.endsWith(".jar")) {
      workDir = extractJARClasses(inPath);
    } else if (inPath.endsWith(".aar")) {
      workDir = extractAARClasses(inPath);
    } else {
      Preconditions.checkArgument(Files.isDirectory(Paths.get(inPath)), "invalid path!");
    }

    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(workDir, scope, ClassLoaderReference.Application);
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    Warnings.clear();
    Result map_mtd_result = new Result();
    HashMap<String, Set<Integer>> map_str_result = new HashMap<String, Set<Integer>>();

    // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
    for (IClassLoader cldr : cha.getLoaders()) {
      if (!cldr.getName().toString().equals("Primordial")) {
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
          // Only process classes in specified classpath and not its dependencies.
          // TODO: figure the right way to do this
          if (!pkgName.isEmpty() && !cls.getName().toString().startsWith(pkgName)) continue;
          for (IMethod mtd : Iterator2Iterable.make(cls.getAllMethods().iterator())) {
            if (!mtd.getDeclaringClass()
                .getClassLoader()
                .getName()
                .toString()
                .equals("Primordial")) {
              // some Application classes are Primordial (why?)
              Preconditions.checkNotNull(mtd, "method not found");
              IR ir =
                  cache.getIRFactory().makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
              Set<Integer> result = new DefinitelyDerefedParams(mtd, ir, cfg, cha).analyze();
              if (!result.isEmpty()) {
                map_mtd_result.put(mtd, result);
                map_str_result.put(mtd.getSignature(), result);
              }
            }
          }
        }
      }
    }
    long end = System.currentTimeMillis();
    System.out.println("-----\ndone\ntook " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + map_str_result.toString());

    if (inPath.endsWith(".jar")) {
      writeProcessedJAR(inPath, outPath, map_mtd_result);
    } else if (inPath.endsWith(".aar")) {
      writeProcessedAAR(inPath, outPath, map_mtd_result);
    }
    FileUtils.deleteDirectory(new File(workDir));
    return map_str_result;
  }

  /**
   * Extract class files from JAR archive and return directory path.
   *
   * @param jarPath Path to input jar file to be analyzed.
   * @return String Path to temporary directory containing the class files.
   */
  private static String extractJARClasses(String jarPath) {
    Preconditions.checkArgument(
        jarPath.endsWith(".jar") && Files.exists(Paths.get(jarPath)),
        "invalid jar path! " + jarPath);
    System.out.println("extracting " + jarPath + "...");
    String classDir = FilenameUtils.getFullPath(jarPath) + FilenameUtils.getBaseName(jarPath);
    try {
      JarFile jar = new JarFile(jarPath);
      Enumeration enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry jarEntry = (JarEntry) enumEntries.nextElement();
        if (jarEntry.getName().endsWith(DEFAULT_ASTUBX_LOACTION)) {
          throw new Error("jar-infer called on already processed library: " + jarPath);
        }
        if (jarEntry.isDirectory() || !jarEntry.getName().endsWith(".class")) continue;
        File f = new File(classDir + File.separator + jarEntry.getName());
        f.getParentFile().mkdirs();
        InputStream jis = jar.getInputStream(jarEntry);
        FileOutputStream fos = new FileOutputStream(f);
        IOUtils.copy(jis, fos);
        fos.close();
        jis.close();
      }
      jar.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return classDir;
  }

  /**
   * Extract class files from AAR archive and return directory path.
   *
   * @param aarPath Path to input aar file to be analyzed.
   * @return String Path to temporary directory containing the class files.
   */
  private static String extractAARClasses(String aarPath) {
    Preconditions.checkArgument(
        aarPath.endsWith(".aar") && Files.exists(Paths.get(aarPath)),
        "invalid aar path! " + aarPath);
    System.out.println("extracting " + aarPath + "...");
    String classDir = FilenameUtils.getFullPath(aarPath) + FilenameUtils.getBaseName(aarPath);
    try {
      JarFile aar = new JarFile(aarPath);
      Enumeration enumEntries = aar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry aarEntry = (JarEntry) enumEntries.nextElement();
        if (aarEntry.getName().endsWith("classes.jar")) {
          JarInputStream jis = new JarInputStream(aar.getInputStream(aarEntry));
          JarEntry jarEntry = null;
          while ((jarEntry = jis.getNextJarEntry()) != null) {
            if (jarEntry.getName().endsWith(DEFAULT_ASTUBX_LOACTION)) {
              throw new Error("jar-infer called on already processed library: " + aarPath);
            }
            if (jarEntry.isDirectory() || !jarEntry.getName().endsWith(".class")) continue;
            File f = new File(classDir + File.separator + jarEntry.getName());
            f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            IOUtils.copy(jis, fos);
            fos.close();
          }
          jis.close();
        }
      }
      aar.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return classDir;
  }

  /**
   * Write processed jar with nullability model in jar -> META-INF/nullaway/jarinfer.astubx
   *
   * @param inJarPath Path of input jar file.
   * @param outJarPath Path of output jar file.
   * @param map_mtd_result Map of 'method references' to their 'list of NonNull parameters'.
   */
  private static void writeProcessedJAR(
      String inJarPath, String outJarPath, Result map_mtd_result) {
    Preconditions.checkArgument(
        inJarPath.endsWith(".jar") && Files.exists(Paths.get(inJarPath)),
        "invalid jar file! " + inJarPath);
    try {
      JarFile jar = new JarFile(inJarPath);
      JarOutputStream jos = new JarOutputStream(new FileOutputStream(outJarPath));
      Enumeration enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry jarEntry = (JarEntry) enumEntries.nextElement();
        InputStream jis = jar.getInputStream(jarEntry);
        jos.putNextEntry(jarEntry);
        IOUtils.copy(jis, jos);
        jis.close();
      }
      jos.putNextEntry(new JarEntry(DEFAULT_ASTUBX_LOACTION));
      writeModel(new DataOutputStream(jos), map_mtd_result);
      jos.close();
      jar.close();
      System.out.println("processed jar to: " + outJarPath);
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  /**
   * Write processed aar with nullability model in aar -> classes.jar ->
   * META-INF/nullaway/jarinfer.astubx
   *
   * @param inAarPath Path of input aar file.
   * @param outAarPath Path of output aar file.
   * @param map_mtd_result Map of 'method references' to their 'list of NonNull parameters'.
   */
  private static void writeProcessedAAR(
      String inAarPath, String outAarPath, Result map_mtd_result) {
    Preconditions.checkArgument(
        inAarPath.endsWith(".aar") && Files.exists(Paths.get(inAarPath)),
        "invalid aar file! " + inAarPath);
    try {
      JarFile aar = new JarFile(inAarPath);
      JarOutputStream aos = new JarOutputStream(new FileOutputStream(outAarPath));
      Enumeration enumEntries = aar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry aarEntry = (JarEntry) enumEntries.nextElement();
        if (aarEntry.getName().endsWith("classes.jar")) {
          aos.putNextEntry(new JarEntry("classes.jar"));
          JarInputStream jis = new JarInputStream(aar.getInputStream(aarEntry));
          JarOutputStream jos = new JarOutputStream(aos);
          JarEntry jarEntry = null;
          while ((jarEntry = jis.getNextJarEntry()) != null) {
            jos.putNextEntry(jarEntry);
            IOUtils.copy(jis, jos);
          }
          jis.close();
          jos.putNextEntry(new JarEntry(DEFAULT_ASTUBX_LOACTION));
          writeModel(new DataOutputStream(jos), map_mtd_result);
          jos.finish();
        } else {
          InputStream ais = aar.getInputStream(aarEntry);
          aos.putNextEntry(aarEntry);
          IOUtils.copy(ais, aos);
          ais.close();
        }
      }
      aos.close();
      aar.close();
      System.out.println("processed aar to: " + outAarPath);
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  /**
   * Write inferred nullability model in astubx format to the JarOutputStream for the processed
   * jar/aar.
   *
   * @param out JarOutputStream for writing the astubx
   * @param map_mtd_result Map of 'method references' to their 'list of NonNull parameters'.
   */
  //  Note: Need version compatibility check between generated stub files and when reading models
  //    StubxWriter.VERSION_0_FILE_MAGIC_NUMBER (?)
  private static void writeModel(DataOutputStream out, Result map_mtd_result) {
    try {
      Map<String, String> importedAnnotations =
          new HashMap<String, String>() {
            {
              put("Nonnull", "javax.annotation.Nonnull");
            }
          };
      Map<String, Set<String>> packageAnnotations = new HashMap<>();
      Map<String, Set<String>> typeAnnotations = new HashMap<>();
      Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();

      for (Map.Entry<IMethod, Set<Integer>> entry : map_mtd_result.entrySet()) {
        IMethod mtd = entry.getKey();
        Set<Integer> ddParams = entry.getValue();
        if (ddParams.isEmpty()) continue;
        Map<Integer, ImmutableSet<String>> argAnnotation =
            new HashMap<Integer, ImmutableSet<String>>();
        for (Integer param : ddParams) {
          argAnnotation.put(param, ImmutableSet.of("Nonnull"));
        }
        methodRecords.put(
            getSignature(mtd),
            new MethodAnnotationsRecord(ImmutableSet.of(), ImmutableMap.copyOf(argAnnotation)));
      }
      StubxWriter.write(
          out, importedAnnotations, packageAnnotations, typeAnnotations, methodRecords);
    } catch (Exception e) {
      e.printStackTrace();
    }
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
    String classType =
        mtd.getDeclaringClass().getName().toString().replaceAll("/", "\\.").substring(1);
    String returnType = mtd.isInit() ? "" : mtd.getReturnType().getName().toString().split("<")[0];
    if (returnType.startsWith("L")) {
      returnType = returnType.replaceAll("/", "\\.").substring(1);
    } else {
      returnType = mapFullTypeName.get(returnType);
    }
    String argTypes = "";
    for (int argi = 0; argi < mtd.getNumberOfParameters(); argi++) {
      if (mtd.getParameterType(argi).isArrayType()) {
        argTypes += "Array";
      } else {
        String argType = mtd.getParameterType(argi).getName().toString().split("<")[0].substring(1);
        argTypes += argType.substring(argType.lastIndexOf('/') + 1);
      }
      if (argi < mtd.getNumberOfParameters() - 1) {
        argTypes += ", ";
      }
    }
    return classType
        + ":"
        + (returnType == null ? "" : returnType + " ")
        + mtd.getName().toString()
        + "("
        + argTypes
        + ")";
  }
}
