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
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CodeScanner;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.PhantomClass;
import com.ibm.wala.classLoader.ShrikeCTMethod;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.core.util.strings.StringStuff;
import com.ibm.wala.core.util.warnings.Warnings;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.generics.MethodTypeSignature;
import com.ibm.wala.types.generics.TypeSignature;
import com.ibm.wala.types.generics.TypeVariableSignature;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.config.FileOfClasses;
import com.uber.nullaway.libmodel.MethodAnnotationsRecord;
import com.uber.nullaway.libmodel.StubxWriter;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FilenameUtils;

/** Driver for running {@link DefinitelyDerefedParams} */
public class DefinitelyDerefedParamsDriver {
  private static boolean DEBUG = false;
  private static boolean VERBOSE = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) {
      System.out.println("[JI " + tag + "] " + msg);
    }
  }

  String lastOutPath = "";
  private long analyzedBytes = 0;
  private long analysisStartTime = 0;
  private MethodParamAnnotations nonnullParams = new MethodParamAnnotations();
  private MethodReturnAnnotations nullableReturns = new MethodReturnAnnotations();

  private boolean annotateBytecode = false;
  private boolean stripJarSignatures = false;

  private static final String DEFAULT_ASTUBX_LOCATION = "META-INF/nullaway/jarinfer.astubx";
  private static final String ASTUBX_JAR_SUFFIX = ".astubx.jar";
  // TODO: Exclusions-
  // org.ow2.asm : InvalidBytecodeException on
  // com.ibm.wala.classLoader.ShrikeCTMethod.makeDecoder:110
  private static final String DEFAULT_EXCLUSIONS = "org\\/objectweb\\/asm\\/.*";

  /**
   * Accounts the bytecode size of analyzed method for statistics.
   *
   * @param mtd Analyzed method.
   */
  private void accountCodeBytes(IMethod mtd) {
    // Get method bytecode size
    if (mtd instanceof ShrikeCTMethod) {
      analyzedBytes += ((ShrikeCTMethod) mtd).getBytecodes().length;
    }
  }

  private DefinitelyDerefedParams getAnalysisDriver(
      IMethod mtd, AnalysisOptions options, AnalysisCache cache) {
    IR ir = cache.getIRFactory().makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
    accountCodeBytes(mtd);
    return new DefinitelyDerefedParams(mtd, ir, cfg);
  }

  MethodParamAnnotations run(String inPaths, String pkgName, boolean includeNonPublicClasses)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    String outPath = "";
    String firstInPath = inPaths.split(",")[0];
    if (firstInPath.endsWith(".jar") || firstInPath.endsWith(".aar")) {
      outPath =
          FilenameUtils.getFullPath(firstInPath)
              + FilenameUtils.getBaseName(firstInPath)
              + ASTUBX_JAR_SUFFIX;
    } else if (new File(firstInPath).exists()) {
      outPath = FilenameUtils.getFullPath(firstInPath) + DEFAULT_ASTUBX_LOCATION;
    }
    return run(inPaths, pkgName, outPath, false, false, includeNonPublicClasses, DEBUG, VERBOSE);
  }

  MethodParamAnnotations run(String inPaths, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    return run(inPaths, pkgName, false);
  }

  MethodParamAnnotations runAndAnnotate(
      String inPaths, String pkgName, String outPath, boolean stripJarSignatures)
      throws IOException, ClassHierarchyException {
    return run(inPaths, pkgName, outPath, true, stripJarSignatures, false, DEBUG, VERBOSE);
  }

  MethodParamAnnotations runAndAnnotate(String inPaths, String pkgName, String outPath)
      throws IOException, ClassHierarchyException {
    return runAndAnnotate(inPaths, pkgName, outPath, false);
  }

  /**
   * Driver for the analysis. {@link DefinitelyDerefedParams} Usage: DefinitelyDerefedParamsDriver (
   * jar/aar_path, package_name, [output_path])
   *
   * @param inPaths Comma separated paths to input jar/aar file to be analyzed.
   * @param pkgName Qualified package name.
   * @param outPath Path to output processed jar/aar file. Default outPath for 'a/b/c/x.jar' is
   *     'a/b/c/x-ji.jar'. When 'annotatedBytecode' is enabled, this should refer to the directory
   *     that should contain all the output jars.
   * @param annotateBytecode Perform bytecode transformation
   * @param stripJarSignatures Remove jar cryptographic signatures
   * @param includeNonPublicClasses Include non-public/ABI classes (e.g. for testing)
   * @param dbg Output debug level logs
   * @param vbs Output verbose level logs
   * @return MethodParamAnnotations Map of 'method signatures' to their 'list of NonNull
   *     parameters'.
   * @throws IOException on IO error.
   * @throws ClassHierarchyException on Class Hierarchy factory error.
   * @throws IllegalArgumentException on illegal argument to WALA API.
   */
  public MethodParamAnnotations run(
      String inPaths,
      String pkgName,
      String outPath,
      boolean annotateBytecode,
      boolean stripJarSignatures,
      boolean includeNonPublicClasses,
      boolean dbg,
      boolean vbs)
      throws IOException, ClassHierarchyException {
    DEBUG = dbg;
    VERBOSE = vbs;
    this.annotateBytecode = annotateBytecode;
    this.stripJarSignatures = stripJarSignatures;
    Set<String> setInPaths = new HashSet<>(Arrays.asList(inPaths.split(",")));
    analysisStartTime = System.currentTimeMillis();
    for (String inPath : setInPaths) {
      analyzeFile(pkgName, inPath, includeNonPublicClasses);
      if (this.annotateBytecode) {
        String outFile = outPath;
        if (setInPaths.size() > 1) {
          outFile =
              outPath
                  + "/"
                  + FilenameUtils.getBaseName(inPath)
                  + "-annotated."
                  + FilenameUtils.getExtension(inPath);
        }
        writeAnnotations(inPath, outFile);
      }
    }
    if (!this.annotateBytecode) {
      new File(outPath).getParentFile().mkdirs();
      if (outPath.endsWith(".astubx")) {
        writeModel(new DataOutputStream(new FileOutputStream(outPath)));
      } else {
        writeModelJAR(outPath);
      }
    }
    lastOutPath = outPath;
    return nonnullParams;
  }

  // Check if a method includes any dereferences at all at the bytecode level
  private boolean bytecodeHasAnyDereferences(IMethod mtd) throws InvalidClassFileException {
    // A dereference is either a field access (o.f) or a method call (o.m())
    return !CodeScanner.getFieldsRead(mtd).isEmpty()
        || !CodeScanner.getFieldsWritten(mtd).isEmpty()
        || !CodeScanner.getCallSites(mtd).isEmpty();
  }

  private void analyzeFile(String pkgName, String inPath, boolean includeNonPublicClasses)
      throws IOException, ClassHierarchyException {
    InputStream jarIS = null;
    if (inPath.endsWith(".jar") || inPath.endsWith(".aar")) {
      jarIS = getInputStream(inPath);
      if (jarIS == null) {
        return;
      }
    } else if (!new File(inPath).exists()) {
      return;
    }
    AnalysisScope scope = AnalysisScopeReader.instance.makeBasePrimordialScope(null);
    scope.setExclusions(
        new FileOfClasses(
            new ByteArrayInputStream(DEFAULT_EXCLUSIONS.getBytes(StandardCharsets.UTF_8))));
    if (jarIS != null) {
      scope.addInputStreamForJarToScope(ClassLoaderReference.Application, jarIS);
    } else {
      AnalysisScopeReader.instance.addClassPathToScope(
          inPath, scope, ClassLoaderReference.Application);
    }
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
    Warnings.clear();

    // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
    for (IClassLoader cldr : cha.getLoaders()) {
      if (!cldr.getName().toString().equals("Primordial")) {
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
          if (cls instanceof PhantomClass) {
            continue;
          }
          // Only process classes in specified classpath and not its dependencies.
          // TODO: figure the right way to do this
          if (!pkgName.isEmpty() && !cls.getName().toString().startsWith(pkgName)) {
            continue;
          }
          // Skip non-public / ABI classes
          if (!cls.isPublic() && !includeNonPublicClasses) {
            continue;
          }
          LOG(DEBUG, "DEBUG", "analyzing class: " + cls.getName().toString());
          for (IMethod mtd : Iterator2Iterable.make(cls.getDeclaredMethods().iterator())) {
            // Skip methods without parameters, abstract methods, native methods
            // some Application classes are Primordial (why?)
            if (shouldCheckMethod(mtd)) {
              Preconditions.checkNotNull(mtd, "method not found");
              DefinitelyDerefedParams analysisDriver = null;
              String sign = "";
              try {
                // Parameter analysis
                boolean isStatic = mtd.isStatic();
                if (mtd.getNumberOfParameters() > (isStatic ? 0 : 1)) {
                  // For inferring parameter nullability, our criteria is based on finding
                  // unchecked dereferences of that parameter. We perform a quick bytecode
                  // check and skip methods containing no dereferences (i.e. method calls
                  // or field accesses) at all, avoiding the expensive IR/CFG generation
                  // step for these methods.
                  // Note that this doesn't apply to inferring return value nullability.
                  if (bytecodeHasAnyDereferences(mtd)) {
                    analysisDriver = getAnalysisDriver(mtd, options, cache);
                    Set<Integer> result = analysisDriver.analyze();
                    if (!isStatic) {
                      // subtract 1 from each parameter index to account for 'this' parameter
                      result =
                          result.stream().map(i -> i - 1).collect(ImmutableSet.toImmutableSet());
                    }
                    sign = getSignature(mtd);
                    LOG(DEBUG, "DEBUG", "analyzed method: " + sign);
                    if (!result.isEmpty() || DEBUG) {
                      nonnullParams.put(sign, result);
                      LOG(
                          DEBUG,
                          "DEBUG",
                          "Inferred Nonnull param for method: " + sign + " = " + result.toString());
                    }
                  }
                }
                // Return value analysis
                analyzeReturnValue(options, cache, mtd, analysisDriver, sign);
              } catch (Exception e) {
                LOG(
                    DEBUG,
                    "DEBUG",
                    "Exception while scanning bytecodes for " + mtd + " " + e.getMessage());
              }
            }
          }
        }
      }
    }
    long endTime = System.currentTimeMillis();
    LOG(
        VERBOSE,
        "Stats",
        inPath
            + " >> time(ms): "
            + (endTime - analysisStartTime)
            + ", bytecode size: "
            + analyzedBytes
            + ", rate (ms/KB): "
            + (analyzedBytes > 0 ? (((endTime - analysisStartTime) * 1000) / analyzedBytes) : 0));
  }

  private void analyzeReturnValue(
      AnalysisOptions options,
      AnalysisCache cache,
      IMethod mtd,
      DefinitelyDerefedParams analysisDriver,
      String sign) {
    if (!mtd.getReturnType().isPrimitiveType()) {
      if (analysisDriver == null) {
        analysisDriver = getAnalysisDriver(mtd, options, cache);
      }
      if (analysisDriver.analyzeReturnType() == DefinitelyDerefedParams.NullnessHint.NULLABLE) {
        if (sign.isEmpty()) {
          sign = getSignature(mtd);
        }
        nullableReturns.add(sign);
        LOG(DEBUG, "DEBUG", "Inferred Nullable method return: " + sign);
      }
    }
  }

  private boolean shouldCheckMethod(IMethod mtd) {
    return !mtd.isPrivate()
        && !mtd.isAbstract()
        && !mtd.isNative()
        && !isAllPrimitiveTypes(mtd)
        && !mtd.getDeclaringClass().getClassLoader().getName().toString().equals("Primordial");
  }

  /**
   * Checks if all parameters and return value of a method have primitive types.
   *
   * @param mtd Method.
   * @return boolean True if all parameters and return value are of primitive type, otherwise false.
   */
  private static boolean isAllPrimitiveTypes(IMethod mtd) {
    if (!mtd.getReturnType().isPrimitiveType()) {
      return false;
    }
    for (int i = (mtd.isStatic() ? 0 : 1); i < mtd.getNumberOfParameters(); i++) {
      if (!mtd.getParameterType(i).isPrimitiveType()) {
        return false;
      }
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
    LOG(VERBOSE, "Info", "opening library: " + libPath + "...");
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
   * Write model jar file with nullability model at DEFAULT_ASTUBX_LOCATION
   *
   * @param outPath Path of output model jar file.
   */
  private void writeModelJAR(String outPath) throws IOException {
    Preconditions.checkArgument(
        outPath.endsWith(ASTUBX_JAR_SUFFIX), "invalid model file path! " + outPath);
    ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outPath));
    if (!nonnullParams.isEmpty()) {
      ZipEntry entry = new ZipEntry(DEFAULT_ASTUBX_LOCATION);
      // Set the modification/creation time to 0 to ensure that this jars always have the same
      // checksum
      entry.setTime(0);
      entry.setCreationTime(FileTime.fromMillis(0));
      zos.putNextEntry(entry);
      writeModel(new DataOutputStream(zos));
      zos.closeEntry();
    }
    zos.close();
    LOG(VERBOSE, "Info", "wrote model to: " + outPath);
  }

  /**
   * Write inferred nullability model in astubx format to the JarOutputStream for the processed
   * jar/aar.
   *
   * @param out JarOutputStream for writing the astubx
   */
  //  Note: Need version compatibility check between generated stub files and when reading models
  //    StubxWriter.VERSION_0_FILE_MAGIC_NUMBER (?)
  private void writeModel(DataOutputStream out) throws IOException {
    ImmutableMap<String, String> importedAnnotations =
        ImmutableMap.<String, String>builder()
            .put("Nonnull", "javax.annotation.Nonnull")
            .put("Nullable", "javax.annotation.Nullable")
            .build();
    Map<String, Set<String>> packageAnnotations = new HashMap<>();
    Map<String, Set<String>> typeAnnotations = new HashMap<>();
    Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();

    for (Map.Entry<String, Set<Integer>> entry : nonnullParams.entrySet()) {
      String sign = entry.getKey();
      Set<Integer> ddParams = entry.getValue();
      if (ddParams.isEmpty()) {
        continue;
      }
      Map<Integer, ImmutableSet<String>> argAnnotation = new HashMap<>();
      for (Integer param : ddParams) {
        argAnnotation.put(param, ImmutableSet.of("Nonnull"));
      }
      methodRecords.put(
          sign,
          MethodAnnotationsRecord.create(
              nullableReturns.contains(sign) ? ImmutableSet.of("Nullable") : ImmutableSet.of(),
              ImmutableMap.copyOf(argAnnotation)));
      nullableReturns.remove(sign);
    }
    for (String nullableReturnMethodSign : Iterator2Iterable.make(nullableReturns.iterator())) {
      methodRecords.put(
          nullableReturnMethodSign,
          MethodAnnotationsRecord.create(ImmutableSet.of("Nullable"), ImmutableMap.of()));
    }
    StubxWriter.write(
        out,
        importedAnnotations,
        packageAnnotations,
        typeAnnotations,
        methodRecords,
        Collections.emptySet(),
        Collections.emptyMap());
  }

  private void writeAnnotations(String inPath, String outFile) throws IOException {
    Preconditions.checkArgument(
        inPath.endsWith(".jar") || inPath.endsWith(".aar") || inPath.endsWith(".class"),
        "invalid input path - " + inPath);
    LOG(DEBUG, "DEBUG", "Writing Annotations to " + outFile);

    new File(outFile).getParentFile().mkdirs();
    if (inPath.endsWith(".jar")) {
      JarFile jar = new JarFile(inPath);
      JarOutputStream jarOS = new JarOutputStream(new FileOutputStream(outFile));
      BytecodeAnnotator.annotateBytecodeInJar(
          jar, jarOS, nonnullParams, nullableReturns, stripJarSignatures, DEBUG);
      jarOS.close();
    } else if (inPath.endsWith(".aar")) {
      ZipFile zip = new ZipFile(inPath);
      ZipOutputStream zipOS = new ZipOutputStream(new FileOutputStream(outFile));
      BytecodeAnnotator.annotateBytecodeInAar(
          zip, zipOS, nonnullParams, nullableReturns, stripJarSignatures, DEBUG);
      zipOS.close();
    } else {
      InputStream is = new FileInputStream(inPath);
      OutputStream os = new FileOutputStream(outFile);
      BytecodeAnnotator.annotateBytecodeInClass(is, os, nonnullParams, nullableReturns, DEBUG);
      os.close();
    }
  }

  private String getSignature(IMethod mtd) {
    return annotateBytecode ? mtd.getSignature() : getAstubxSignature(mtd);
  }

  /**
   * Get astubx style method signature. {FullyQualifiedEnclosingType}: {UnqualifiedMethodReturnType}
   * {methodName} ([{UnqualifiedArgumentType}*])
   *
   * @param mtd Method reference.
   * @return String Method signature.
   */
  private static String getAstubxSignature(IMethod mtd) {
    Preconditions.checkArgument(
        mtd instanceof ShrikeCTMethod, "Method is not a ShrikeCTMethod from bytecodes");
    String classType = getSourceLevelQualifiedTypeName(mtd.getDeclaringClass().getReference());
    MethodTypeSignature genericSignature = null;
    try {
      genericSignature = ((ShrikeCTMethod) mtd).getMethodTypeSignature();
    } catch (InvalidClassFileException e) {
      // don't fail, just proceed without the generic signature
      LOG(DEBUG, "DEBUG", "Invalid class file exception: " + e.getMessage());
    }
    String returnType;
    int numParams = mtd.isStatic() ? mtd.getNumberOfParameters() : mtd.getNumberOfParameters() - 1;
    String[] argTypes = new String[numParams];
    if (genericSignature != null) {
      // get types that include generic type arguments
      returnType = getSourceLevelQualifiedTypeName(genericSignature.getReturnType().toString());
      TypeSignature[] argTypeSigs = genericSignature.getArguments();
      Verify.verify(
          argTypeSigs.length == numParams,
          "Mismatch in number of parameters in generic signature: %s with %s vs %s with %s",
          mtd.getSignature(),
          numParams,
          genericSignature,
          argTypeSigs.length);
      for (int i = 0; i < argTypeSigs.length; i++) {
        argTypes[i] = getSourceLevelQualifiedTypeName(argTypeSigs[i].toString());
      }
    } else { // no generics
      returnType = mtd.isInit() ? null : getSourceLevelQualifiedTypeName(mtd.getReturnType());
      int argi = mtd.isStatic() ? 0 : 1; // Skip 'this' parameter
      for (int i = 0; i < numParams; i++) {
        argTypes[i] = getSourceLevelQualifiedTypeName(mtd.getParameterType(argi++));
      }
    }
    return classType
        + ":"
        + (returnType == null ? "void " : returnType + " ")
        + mtd.getName().toString()
        + "("
        + String.join(", ", argTypes)
        + ")";
  }

  /**
   * Get the source-level qualified type name for a TypeReference.
   *
   * @param typ Type Reference.
   * @return source-level qualified type name.
   * @see #getSourceLevelQualifiedTypeName(String)
   */
  private static String getSourceLevelQualifiedTypeName(TypeReference typ) {
    String typeName = typ.getName().toString();
    return getSourceLevelQualifiedTypeName(typeName);
  }

  /**
   * Converts a JVM-level qualified type (e.g., {@code Lcom/example/Foo$Baz;}) to a source-level
   * qualified type (e.g., {@code com.example.Foo.Baz}). Nested types like generic type arguments
   * are converted recursively.
   *
   * @param typeName JVM-level qualified type name.
   * @return source-level qualified type name.
   */
  private static String getSourceLevelQualifiedTypeName(String typeName) {
    if (isWildcard(typeName)) {
      return sourceLevelWildcardType(typeName);
    }
    if (!typeName.endsWith(";")) {
      // we need the semicolon since some of WALA's TypeSignature APIs expect it
      typeName = typeName + ";";
    }
    boolean isGeneric = typeName.contains("<");
    if (!isGeneric) { // base case
      TypeSignature ts = TypeSignature.make(typeName);
      if (ts.isTypeVariable()) {
        // TypeVariableSignature's toString() returns more than just the identifier
        return ((TypeVariableSignature) ts).getIdentifier();
      } else {
        String tsStr = ts.toString();
        if (tsStr.endsWith(";")) {
          // remove trailing semicolon
          tsStr = tsStr.substring(0, tsStr.length() - 1);
        }
        return StringStuff.jvmToReadableType(tsStr);
      }
    } else { // generic type
      int idx = typeName.indexOf("<");
      String baseType = typeName.substring(0, idx);
      // generic type args are separated by semicolons in signature stored in bytecodes
      String[] genericTypeArgs = splitTypeArgs(typeName.substring(idx + 1, typeName.length() - 2));
      for (int i = 0; i < genericTypeArgs.length; i++) {
        genericTypeArgs[i] = getSourceLevelQualifiedTypeName(genericTypeArgs[i]);
      }
      return getSourceLevelQualifiedTypeName(baseType)
          + "<"
          + String.join(",", genericTypeArgs)
          + ">";
    }
  }

  /**
   * Splits out the top-level type arguments from a string representing all arguments (from the
   * bytecode-level signature)
   *
   * @param allTypeArgs string representing all type arguments
   * @return array of strings representing top-level type arguments
   */
  private static String[] splitTypeArgs(String allTypeArgs) {
    List<String> result = new ArrayList<>();
    StringBuilder currentTypeArg = new StringBuilder();
    // track angle bracket depth to handle nested generic types
    int angleBracketDepth = 0;

    for (int i = 0; i < allTypeArgs.length(); i++) {
      char c = allTypeArgs.charAt(i);
      if (c == '<') {
        angleBracketDepth++;
        currentTypeArg.append(c);
      } else if (c == '>') {
        angleBracketDepth--;
        currentTypeArg.append(c);
      } else if (c == '*' && angleBracketDepth == 0) {
        // Wildcard (not followed by semicolon)
        currentTypeArg.append(c);
        result.add(currentTypeArg.toString());
        currentTypeArg.setLength(0);
      } else if (c == ';' && angleBracketDepth == 0) {
        // Split on semicolon only if not nested within <>
        result.add(currentTypeArg.toString());
        currentTypeArg.setLength(0);
      } else {
        currentTypeArg.append(c);
      }
    }

    // there should be no extra characters left
    Verify.verify(
        currentTypeArg.length() == 0,
        "unexpected characters left in generic type args string %s: %s",
        allTypeArgs,
        currentTypeArg);

    return result.toArray(new String[0]);
  }

  private static boolean isWildcard(String typeName) {
    char firstChar = typeName.charAt(0);
    return firstChar == '*' || firstChar == '+' || firstChar == '-';
  }

  private static String sourceLevelWildcardType(String typeName) {
    switch (typeName.charAt(0)) {
      case '*':
        return "?";
      case '+':
        return "? extends " + getSourceLevelQualifiedTypeName(typeName.substring(1));
      case '-':
        return "? super " + getSourceLevelQualifiedTypeName(typeName.substring(1));
      default:
        throw new RuntimeException("unexpected wildcard type name" + typeName);
    }
  }
}
