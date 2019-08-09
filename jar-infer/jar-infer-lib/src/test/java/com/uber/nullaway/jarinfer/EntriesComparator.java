/*
 * Copyright (C) 2019. Uber Technologies
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EntriesComparator {
  private static String classesJarInAar = "classes.jar";

  /**
   * Compares the entries in the given 2 jar files. However, this function does not compare the
   * contents of the entries themselves.
   *
   * @param jarFile1 Path to the first jar file.
   * @param jarFile2 Path to the second jar file.
   * @return True iff the entries present in the two jar files are the same.
   * @throws IOException
   */
  public static boolean compareEntriesInJars(String jarFile1, String jarFile2) throws IOException {
    Preconditions.checkArgument(jarFile1.endsWith(".jar"), "invalid jar file: " + jarFile1);
    Preconditions.checkArgument(jarFile2.endsWith(".jar"), "invalid jar file: " + jarFile2);
    JarFile jar1 = new JarFile(jarFile1);
    Set<String> jar1Entries = new HashSet<>();
    for (Enumeration<JarEntry> entries = jar1.entries(); entries.hasMoreElements(); ) {
      jar1Entries.add(entries.nextElement().getName());
    }
    JarFile jar2 = new JarFile(jarFile2);
    Set<String> jar2Entries = new HashSet<>();
    for (Enumeration<JarEntry> entries = jar2.entries(); entries.hasMoreElements(); ) {
      jar2Entries.add(entries.nextElement().getName());
    }
    return jar1Entries.equals(jar2Entries);
  }

  /**
   * Compares the entries in the given 2 aar files and the entries in "classes.jar" in them.
   * However, this function does not compare the contents of the entries themselves.
   *
   * @param aarFile1 Path to the first aar file.
   * @param aarFile2 Path to the second aar file.
   * @return True iff the entries present in the two aar files are the same and entries in
   *     "classes.jar" in the two aar files are the same.
   * @throws IOException
   */
  public static boolean compareEntriesInAars(String aarFile1, String aarFile2) throws IOException {
    Preconditions.checkArgument(aarFile1.endsWith(".aar"), "invalid aar file: " + aarFile1);
    Preconditions.checkArgument(aarFile2.endsWith(".aar"), "invalid aar file: " + aarFile2);
    ZipFile zip1 = new ZipFile(aarFile1);
    Set<String> zip1Entries = new HashSet<>();
    for (Enumeration<? extends ZipEntry> entries = zip1.entries(); entries.hasMoreElements(); ) {
      zip1Entries.add(entries.nextElement().getName());
    }
    ZipFile zip2 = new ZipFile(aarFile2);
    Set<String> zip2Entries = new HashSet<>();
    for (Enumeration<? extends ZipEntry> entries = zip2.entries(); entries.hasMoreElements(); ) {
      zip2Entries.add(entries.nextElement().getName());
    }
    if (!zip1Entries.equals(zip2Entries)) {
      return false;
    }

    // Check if all the entries in "classes.jar" in both aar files are the same.
    // We expect to find a classes.jar entry in the aar files.
    ZipEntry zip1Jar = zip1.getEntry(classesJarInAar);
    ZipEntry zip2Jar = zip2.getEntry(classesJarInAar);
    if (zip1Jar == null || zip2Jar == null) {
      return false;
    }
    JarInputStream jarIS1 = new JarInputStream(zip1.getInputStream(zip1Jar));
    JarInputStream jarIS2 = new JarInputStream(zip2.getInputStream(zip2Jar));
    Set<String> jar1Entries = new HashSet<>();
    JarEntry jar1Entry = jarIS1.getNextJarEntry();
    while (jar1Entry != null) {
      jar1Entries.add(jar1Entry.getName());
      jar1Entry = jarIS1.getNextJarEntry();
    }
    Set<String> jar2Entries = new HashSet<>();
    JarEntry jar2Entry = jarIS2.getNextJarEntry();
    while (jar2Entry != null) {
      jar2Entries.add(jar2Entry.getName());
      jar2Entry = jarIS2.getNextJarEntry();
    }
    return jar1Entries.equals(jar2Entries);
  }

  private static String readManifestFromJar(String jarfile) throws IOException {
    JarFile jar = new JarFile(jarfile);
    ZipEntry manifestEntry = jar.getEntry("META-INF/MANIFEST.SF");
    if (manifestEntry == null) {
      return "";
    }
    StringBuilder stringBuilder = new StringBuilder();
    BufferedReader bufferedReader =
        new BufferedReader(new InputStreamReader(jar.getInputStream(manifestEntry), "UTF-8"));
    String currentLine;
    while ((currentLine = bufferedReader.readLine()) != null) {
      // Ignore empty new lines
      if (currentLine.trim().length() > 0) {
        stringBuilder.append(currentLine);
      }
    }
    return stringBuilder.toString();
  }

  /**
   * Compares the META-INF/MANIFEST.SF file in the given 2 jar files. We ignore empty newlines.
   *
   * @param jarFile1 Path to the first jar file.
   * @param jarFile2 Path to the second jar file.
   * @return True iff the MANIFEST.SF files in the two jar files exist and are the same.
   * @throws IOException
   */
  public static boolean compareManifestContents(String jarFile1, String jarFile2)
      throws IOException {
    Preconditions.checkArgument(jarFile1.endsWith(".jar"), "invalid jar file: " + jarFile1);
    Preconditions.checkArgument(jarFile2.endsWith(".jar"), "invalid jar file: " + jarFile2);
    String manifest1 = readManifestFromJar(jarFile1);
    String manifest2 = readManifestFromJar(jarFile2);
    return manifest1.equals(manifest2);
  }
}
