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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;

/*
 * Driver for running {@link DefinitelyDerefedParams}
 */
public class DefinitelyDerefedParamsDriver {
  /*
   * Usage: DefinitelyDerefedParamsDriver ( path, package_name, [output_jar_path])
   * path: jar file OR directory containing class files
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws IllegalArgumentException
   */

  public static HashMap<String, Set<Integer>> run(String path, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String outPath = "";
    if (path.endsWith(".jar") || path.endsWith(".aar")) {
      outPath =
          path.substring(0, path.lastIndexOf('.'))
              + ".ji."
              + path.substring(path.lastIndexOf('.') + 1);
    }
    return run(path, pkgName, outPath);
  }

  public static HashMap<String, Set<Integer>> run(String path, String pkgName, String outPath)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String workDir = path;
    long start = System.currentTimeMillis();
    if (path.endsWith(".jar")) {
      workDir = extractJAR(path);
    } else if (path.endsWith(".aar")) {
      workDir = extractAAR(path);
    } else {
      Preconditions.checkArgument(Files.isDirectory(Paths.get(path)), "invalid path!");
    }
    String aStubXPath =
        workDir
            + File.separator
            + "META-INF"
            + File.separator
            + "nullaway"
            + File.separator
            + "jarinfer.astubx";

    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(workDir, scope, ClassLoaderReference.Application);
    if (path.endsWith(".aar")) {
      AnalysisScopeReader.addClassPathToScope(
          "/Users/subarno/android-sdk/platforms/android-28/android.jar",
          scope,
          ClassLoaderReference.Extension);
      //
      // AnalysisScopeReader.addClassPathToScope("/Users/subarno/android-sdk/extras/android/m2repository/com/android/support/appcompat-v7/25.3.1/appcompat-v7-25.3.1.aar", scope, ClassLoaderReference.Extension);
    }
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    Warnings.clear();
    HashMap<IMethod, Set<Integer>> map_mtd_result = new HashMap<IMethod, Set<Integer>>();
    HashMap<String, Set<Integer>> map_str_result = new HashMap<String, Set<Integer>>();

    // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
    for (IClassLoader cldr : cha.getLoaders()) {
      if (!cldr.getName().toString().equals("Primordial")) {
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
          // Only process classes in specified classpath and not its dependencies.
          // TODO: figure the right way to do this
          System.out.println("[dbg] cls: " + cls.getName().toString());
          if (cls.getName().toString().startsWith(pkgName)) {
            for (IMethod mtd : Iterator2Iterable.make(cls.getAllMethods().iterator())) {
              System.out.println("[dbg] mtd: " + mtd.toString());
              if (!mtd.getDeclaringClass()
                  .getClassLoader()
                  .getName()
                  .toString()
                  .equals("Primordial")) {
                // some Application classes are Primordial (why?)
                Preconditions.checkNotNull(mtd, "method not found");
                IR ir =
                    cache
                        .getIRFactory()
                        .makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
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
      } else
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses()))
          System.out.println("[dbg] primordial cls: " + cls.getName().toString());
    }
    long end = System.currentTimeMillis();
    System.out.println("-----\ndone\ntook " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + map_str_result.toString());

    writeJarModel(cha, map_mtd_result, aStubXPath);

    if (path.endsWith(".jar")) {
      packJAR(workDir, outPath);
    } else if (path.endsWith(".aar")) {
      packAAR(workDir, outPath);
    }
    return map_str_result;
  }
  /*
   * Unpack JAR archive and return directory path
   *
   */
  private static String extractJAR(String jarPath) {
    Preconditions.checkArgument(
        (jarPath.endsWith(".jar") || jarPath.endsWith(".aar")) && Files.exists(Paths.get(jarPath)),
        "invalid jar path! " + jarPath);
    System.out.println("extracting " + jarPath + "...");
    String jarDir = jarPath.substring(0, jarPath.lastIndexOf('.'));
    try {
      JarFile jar = new JarFile(jarPath);
      Enumeration enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        JarEntry file = (JarEntry) enumEntries.nextElement();
        File f = new File(jarDir + File.separator + file.getName());
        if (file.isDirectory()) {
          f.mkdirs();
          continue;
        }
        f.getParentFile().mkdirs();
        InputStream is = jar.getInputStream(file);
        FileOutputStream fos = new FileOutputStream(f);
        while (is.available() > 0) {
          fos.write(is.read());
        }
        fos.close();
        is.close();
      }
      jar.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return jarDir;
  }

  /*
   * Unpack AAR archive and return 'clasees' directory path
   *
   */
  private static String extractAAR(String aarPath) {
    return extractJAR(extractJAR(aarPath) + File.separator + "classes.jar");
  }

  /*
   * Repack JAR archive
   *
   */
  private static void packJAR(String jarDir, String outPath) {
    Preconditions.checkArgument(
        Files.isDirectory(Paths.get(jarDir)), "invalid jar directory!" + jarDir);
    System.out.println("repacking " + outPath + "...");
    File jarDirFile = new File(jarDir);
    try {
      FileOutputStream fos = new FileOutputStream(outPath);
      JarOutputStream jos;
      if (outPath.endsWith(".aar")) {
        jos = new JarOutputStream(fos);
      } else {
        jos = new JarOutputStream(fos, new Manifest());
      }
      final IOFileFilter jarFilter =
          new IOFileFilter() {
            @Override
            public boolean accept(File dir, String name) {
              return !(name.endsWith(".DS_Store") || name.endsWith("MANIFEST.MF"));
            }

            @Override
            public boolean accept(File file) {
              return accept(file, file.getName());
            }
          };
      byte buffer[] = new byte[10240];
      for (File file :
          Iterator2Iterable.make(
              FileUtils.iterateFilesAndDirs(jarDirFile, jarFilter, TrueFileFilter.TRUE))) {
        if (file == null
            || !file.exists()
            || (file.isDirectory() && file.getAbsolutePath().endsWith(jarDir))) continue;
        JarEntry jarEntry =
            new JarEntry(
                file.getAbsolutePath().replace(jarDirFile.getAbsolutePath() + File.separator, "")
                    + (file.isDirectory() ? File.separator : ""));
        jos.putNextEntry(jarEntry);
        jarEntry.setTime(file.lastModified());
        if (!file.isDirectory()) {
          FileInputStream in = new FileInputStream(file);
          while (true) {
            int nRead = in.read(buffer, 0, buffer.length);
            if (nRead <= 0) break;
            jos.write(buffer, 0, nRead);
          }
          in.close();
        }
      }
      jos.close();
      fos.close();
      FileUtils.deleteDirectory(jarDirFile);
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  /*
   * Repack AAR archive
   *
   */
  private static void packAAR(String jarDir, String outPath) {
    packJAR(jarDir, jarDir + ".jar");
    packJAR(jarDir.substring(0, jarDir.lastIndexOf('/')), outPath);
  }

  /*
   * Write inferred Jar model in astubx format
   * Note: Need version compatibility check between generated stub files and when reading models
   *       StubxWriter.VERSION_0_FILE_MAGIC_NUMBER (?)
   */
  private static void writeJarModel(
      IClassHierarchy cha, HashMap<IMethod, Set<Integer>> map_mtd_result, String aStubXPath) {
    try {
      File aStubXFile = new File(aStubXPath);
      aStubXFile.getParentFile().mkdirs();
      aStubXFile.createNewFile();
      DataOutputStream out = new DataOutputStream(new FileOutputStream(aStubXFile, false));
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

  /*
   * Get astubx style method signature
   * {FullyQualifiedEnclosingType}: {UnqualifiedMethodReturnType} {methodName} ([{UnqualifiedArgumentType}*])
   * TODO: handle generics and inner classes
   */
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
