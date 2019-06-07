package com.uber.nullaway.jarinfer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simple writer for the astubx format. */
final class StubxWriter {
  /**
   * The file magic number for version 0 .astubx files. It should be the first four bytes of any
   * compatible .astubx file.
   */
  private static final int VERSION_0_FILE_MAGIC_NUMBER = 691458791;

  /**
   * This method writes the provided list of annotations to a DataOutputStream in the astubx format.
   *
   * @param out Output stream.
   * @param importedAnnotations Mapping of 'custom annotations' to their 'definition classes'.
   * @param packageAnnotations Map of 'package names' to their 'list of package-level annotations'.
   * @param typeAnnotations Map of 'type names' to their 'list of type annotations'.
   * @param methodRecords Map of 'method signatures' to their 'method annotations record'. Method
   *     annotations record consists of return value annotations and argument annotations. {@link
   *     MethodAnnotationsRecord}
   * @exception IOException On output error.
   */
  static void write(
      DataOutputStream out,
      Map<String, String> importedAnnotations,
      Map<String, Set<String>> packageAnnotations,
      Map<String, Set<String>> typeAnnotations,
      Map<String, MethodAnnotationsRecord> methodRecords)
      throws IOException {
    // File format version/magic number
    out.writeInt(VERSION_0_FILE_MAGIC_NUMBER);
    // Followed by the number of string dictionary entries
    int numStringEntries = 0;
    Map<String, Integer> encodingDictionary = new LinkedHashMap<>();
    List<String> strings = new ArrayList<String>();
    List<Collection<String>> keysets =
        ImmutableList.of(
            importedAnnotations.values(),
            packageAnnotations.keySet(),
            typeAnnotations.keySet(),
            methodRecords.keySet());
    for (Collection<String> keyset : keysets) {
      for (String key : keyset) {
        assert !encodingDictionary.containsKey(key);
        strings.add(key);
        encodingDictionary.put(key, numStringEntries);
        ++numStringEntries;
      }
    }
    out.writeInt(numStringEntries);
    // Followed by the entries themselves
    for (String s : strings) {
      out.writeUTF(s);
    }
    // Followed by the number of encoded package annotation records
    int packageAnnotationSize = 0;
    for (Map.Entry<String, Set<String>> entry : packageAnnotations.entrySet()) {
      packageAnnotationSize += entry.getValue().size();
    }
    out.writeInt(packageAnnotationSize);
    // Followed by those records as pairs of ints pointing into the dictionary
    for (Map.Entry<String, Set<String>> entry : packageAnnotations.entrySet()) {
      for (String annot : entry.getValue()) {
        out.writeInt(encodingDictionary.get(entry.getKey()));
        out.writeInt(encodingDictionary.get(importedAnnotations.get(annot)));
      }
    }
    // Followed by the number of encoded type annotation records
    int typeAnnotationSize = 0;
    for (Map.Entry<String, Set<String>> entry : typeAnnotations.entrySet()) {
      typeAnnotationSize += entry.getValue().size();
    }
    out.writeInt(typeAnnotationSize);
    // Followed by those records as pairs of ints pointing into the dictionary
    for (Map.Entry<String, Set<String>> entry : typeAnnotations.entrySet()) {
      for (String annot : entry.getValue()) {
        out.writeInt(encodingDictionary.get(entry.getKey()));
        out.writeInt(encodingDictionary.get(importedAnnotations.get(annot)));
      }
    }
    // Followed by the number of encoded method return/declaration annotation records
    int methodAnnotationSize = 0;
    int methodArgumentRecordsSize = 0;
    for (Map.Entry<String, MethodAnnotationsRecord> entry : methodRecords.entrySet()) {
      methodAnnotationSize += entry.getValue().getMethodAnnotations().size();
      methodArgumentRecordsSize += entry.getValue().getArgumentAnnotations().size();
    }
    out.writeInt(methodAnnotationSize);
    // Followed by those records as pairs of ints pointing into the dictionary
    for (Map.Entry<String, MethodAnnotationsRecord> entry : methodRecords.entrySet()) {
      for (String annot : entry.getValue().getMethodAnnotations()) {
        out.writeInt(encodingDictionary.get(entry.getKey()));
        out.writeInt(encodingDictionary.get(importedAnnotations.get(annot)));
      }
    }
    // Followed by the number of encoded method argument annotation records
    out.writeInt(methodArgumentRecordsSize);
    // Followed by those records as a triplet of ints ( 0 and 2 point in the dictionary, 1 is the
    //  argument position)
    for (Map.Entry<String, MethodAnnotationsRecord> entry : methodRecords.entrySet()) {
      for (Map.Entry<Integer, ImmutableSet<String>> argEntry :
          entry.getValue().getArgumentAnnotations().entrySet()) {
        for (String annot : argEntry.getValue()) {
          out.writeInt(encodingDictionary.get(entry.getKey()));
          out.writeInt(argEntry.getKey());
          out.writeInt(encodingDictionary.get(importedAnnotations.get(annot)));
        }
      }
    }
  }
}
