package com.uber.nullaway.handlers;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the JSpecify annotations from the class files of a <a
 * href="https://github.com/vlsi/jspecify-jdk-stubs">jspecify-jdk-stubs</a> jar into a {@link
 * StubxCacheUtil}, with the keys and values that an {@code .astubx} file would give it.
 *
 * <p>A method is keyed by {@code class:returnType <typeParameters>name(parameterTypes)}, with each
 * type rendered as javac's {@code MethodSymbol.toString()} renders it, so the reader parses the
 * generic signature of every annotated method. Nested annotations are converted to {@link
 * NestedAnnotationInfo} type paths; a class-file type path does not say whether it enters {@code ?
 * extends} or {@code ? super}, so the reader takes that from the signature too.
 *
 * <p>The reader skips method bodies and every attribute other than {@code Signature}, {@code
 * InnerClasses}, {@code RuntimeVisibleAnnotations}, and {@code RuntimeVisibleTypeAnnotations}. It
 * caches only what {@code .astubx} can represent, so it skips {@code @NullUnmarked}, annotations on
 * supertypes, annotations below the top level of a field or a type-parameter bound, and annotations
 * on an enclosing type of an inner class, such as {@code Outer<@Nullable String>.Inner}.
 */
final class JSpecifyJdkStubsReader {
  private static final String NULLABLE_DESCRIPTOR = "Lorg/jspecify/annotations/Nullable;";
  private static final String NON_NULL_DESCRIPTOR = "Lorg/jspecify/annotations/NonNull;";
  private static final String NULL_MARKED_DESCRIPTOR = "Lorg/jspecify/annotations/NullMarked;";
  private static final String NULLABLE = "org.jspecify.annotations.Nullable";
  private static final String NON_NULL = "org.jspecify.annotations.NonNull";

  private static final int ACC_STATIC = 0x0008;
  private static final int ACC_BRIDGE = 0x0040;
  private static final int ACC_VARARGS = 0x0080;
  private static final int ACC_SYNTHETIC = 0x1000;

  // target_type values of a type annotation (JVMS 4.7.20.1)
  private static final int CLASS_TYPE_PARAMETER_BOUND = 0x11;
  private static final int METHOD_TYPE_PARAMETER_BOUND = 0x12;
  private static final int FIELD = 0x13;
  private static final int METHOD_RETURN = 0x14;
  private static final int METHOD_FORMAL_PARAMETER = 0x16;

  private final StubxCacheUtil cache;

  /** The class file being read. */
  private byte[] bytes = new byte[0];

  /** Read position in {@link #bytes}. */
  private int pos;

  /** Offset of each {@code CONSTANT_Utf8} entry in {@link #bytes}, or 0 for other entries. */
  private int[] utf8Offset = new int[0];

  /** Decoded {@code CONSTANT_Utf8} entries, filled on first use. */
  private @Nullable String[] utf8 = new String[0];

  /** The index each {@code CONSTANT_Class} entry points to. */
  private int[] classNameIndex = new int[0];

  /**
   * The enclosing class of each non-static inner class the class file names, by internal name, as
   * its {@code InnerClasses} attribute lists them.
   */
  private final Map<String, String> enclosingOfInnerClass = new HashMap<>();

  private JSpecifyJdkStubsReader(StubxCacheUtil cache) {
    this.cache = cache;
  }

  /**
   * Reads every class file in a jspecify-jdk-stubs jar into {@code cache}.
   *
   * @param jar the jar contents, closed when the method returns
   * @param location where the jar comes from, for error messages
   * @throws IOException if {@code jar} cannot be read, or holds a malformed class file
   */
  static void read(InputStream jar, String location, StubxCacheUtil cache) throws IOException {
    JSpecifyJdkStubsReader reader = new JSpecifyJdkStubsReader(cache);
    try (ZipInputStream zip = new ZipInputStream(jar)) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        String name = entry.getName();
        if (!name.endsWith(".class") || name.endsWith("module-info.class")) {
          continue;
        }
        try {
          reader.readClass(zip.readAllBytes());
        } catch (IOException | RuntimeException e) {
          throw new IOException("Cannot read " + name + " in " + location + ": " + e, e);
        }
      }
    }
  }

  /**
   * A type annotation as the class file stores it. {@code index} is the parameter index for {@code
   * target} {@link #METHOD_FORMAL_PARAMETER}, the type-parameter index for a type parameter or its
   * bound, the {@code supertype_index} or {@code throws_type_index} for those targets, and {@code
   * -1} for a field, a return type, or a receiver. {@code boundIndex} is the {@code bound_index} of
   * a bound, where 0 is the class bound, and {@code -1} for other targets. {@code path} holds pairs
   * of {@code type_path_kind} and {@code type_argument_index}.
   */
  private static final class TypeAnnotation {
    final int target;
    final int index;
    final int boundIndex;
    final int[] path;
    final String descriptor;

    TypeAnnotation(int target, int index, int boundIndex, int[] path, String descriptor) {
      this.target = target;
      this.index = index;
      this.boundIndex = boundIndex;
      this.path = path;
      this.descriptor = descriptor;
    }
  }

  private record FieldInfo(
      String name,
      String descriptor,
      @Nullable String signature,
      List<TypeAnnotation> typeAnnotations) {}

  private record MethodInfo(
      int access,
      String name,
      String descriptor,
      @Nullable String signature,
      List<TypeAnnotation> typeAnnotations) {}

  /**
   * Reads one class file and caches its null-marking and the annotations of its fields, methods,
   * and type parameters.
   */
  private void readClass(byte[] classFile) throws IOException {
    bytes = classFile;
    pos = 0;
    if (u4() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    pos += 4; // minor_version, major_version
    readConstantPool();
    pos += 2; // access_flags
    int thisClass = u2();
    String flatName = utf8(classNameIndex[thisClass]).replace('/', '.');
    String className = flatName.replace('$', '.');
    pos += 2; // super_class
    int interfaces = u2();
    pos += 2 * interfaces;

    // The class attributes, InnerClasses among them, follow the fields and methods, so field and
    // method annotations are cached after the attribute loop.
    List<FieldInfo> fields = new ArrayList<>();
    for (int count = u2(); count > 0; count--) {
      pos += 2; // access_flags
      int name = u2();
      int descriptor = u2();
      int signature = 0;
      List<TypeAnnotation> typeAnnotations = List.of();
      for (int attributes = u2(); attributes > 0; attributes--) {
        String attribute = utf8(u2());
        int end = u4() + pos;
        switch (attribute) {
          case "Signature" -> signature = u2();
          case "RuntimeVisibleTypeAnnotations" -> typeAnnotations = readTypeAnnotations();
          default -> {}
        }
        pos = end;
      }
      if (!typeAnnotations.isEmpty()) {
        fields.add(
            new FieldInfo(
                utf8(name),
                utf8(descriptor),
                signature == 0 ? null : utf8(signature),
                typeAnnotations));
      }
    }

    List<MethodInfo> methods = new ArrayList<>();
    for (int count = u2(); count > 0; count--) {
      int access = u2();
      int name = u2();
      int descriptor = u2();
      int signature = 0;
      List<TypeAnnotation> typeAnnotations = List.of();
      for (int attributes = u2(); attributes > 0; attributes--) {
        String attribute = utf8(u2());
        int end = u4() + pos;
        switch (attribute) {
          case "Signature" -> signature = u2();
          case "RuntimeVisibleTypeAnnotations" -> typeAnnotations = readTypeAnnotations();
          default -> {}
        }
        pos = end;
      }
      if ((access & (ACC_SYNTHETIC | ACC_BRIDGE)) == 0 && !typeAnnotations.isEmpty()) {
        methods.add(
            new MethodInfo(
                access,
                utf8(name),
                utf8(descriptor),
                signature == 0 ? null : utf8(signature),
                typeAnnotations));
      }
    }

    String classSignature = null;
    List<TypeAnnotation> classTypeAnnotations = List.of();
    enclosingOfInnerClass.clear();
    for (int attributes = u2(); attributes > 0; attributes--) {
      String attribute = utf8(u2());
      int end = u4() + pos;
      switch (attribute) {
        case "Signature" -> classSignature = utf8(u2());
        case "RuntimeVisibleAnnotations" -> {
          if (readAnnotationDescriptors().contains(NULL_MARKED_DESCRIPTOR)) {
            cache.cacheNullMarkedClass(className);
          }
        }
        case "RuntimeVisibleTypeAnnotations" -> classTypeAnnotations = readTypeAnnotations();
        case "InnerClasses" -> {
          for (int classes = u2(); classes > 0; classes--) {
            int inner = u2();
            int outer = u2();
            pos += 2; // inner_name_index
            int flags = u2();
            if (outer != 0 && (flags & ACC_STATIC) == 0) {
              enclosingOfInnerClass.put(utf8(classNameIndex[inner]), utf8(classNameIndex[outer]));
            }
          }
        }
        default -> {}
      }
      pos = end;
    }

    List<TypeParameter> classTypeParameters =
        classSignature == null ? List.of() : new SignatureParser(classSignature).typeParameters();
    for (TypeAnnotation a : classTypeAnnotations) {
      if (a.target == CLASS_TYPE_PARAMETER_BOUND
          && a.descriptor.equals(NULLABLE_DESCRIPTOR)
          && isTopLevel(bound(classTypeParameters, a), a.path)) {
        cache.cacheUpperBounds(className, a.index);
      }
    }
    for (FieldInfo field : fields) {
      Type type =
          new SignatureParser(field.signature != null ? field.signature : field.descriptor).type();
      for (TypeAnnotation a : field.typeAnnotations) {
        if (a.target == FIELD
            && a.descriptor.equals(NULLABLE_DESCRIPTOR)
            && isTopLevel(type, a.path)) {
          // NullAway looks a field up by ClassSymbol.flatName()
          cache.cacheNullableField(flatName, field.name);
        }
      }
    }
    boolean innerClass = enclosingOfInnerClass.containsKey(utf8(classNameIndex[thisClass]));
    for (MethodInfo method : methods) {
      readMethod(className, innerClass, method);
    }
  }

  /** Caches the annotations of one method, keyed the way {@code .astubx} keys it. */
  private void readMethod(String className, boolean innerClass, MethodInfo method) {
    boolean constructor = method.name.equals("<init>");
    SignatureParser parser =
        new SignatureParser(method.signature != null ? method.signature : method.descriptor);
    List<TypeParameter> typeParameters = parser.typeParameters();
    List<Type> parameters = parser.parameters();
    Type returnType = parser.type();
    if (constructor && innerClass && method.signature == null) {
      // The descriptor of an inner class constructor starts with the enclosing instance, which
      // javac leaves out of the method symbol.
      parameters = parameters.subList(1, parameters.size());
    }

    StringBuilder key = new StringBuilder(className).append(':');
    if (!constructor) {
      key.append(returnType);
    }
    key.append(' ');
    if (!typeParameters.isEmpty()) {
      key.append('<');
      for (int i = 0; i < typeParameters.size(); i++) {
        if (i > 0) {
          key.append(',');
        }
        key.append(typeParameters.get(i).name());
      }
      key.append('>');
    }
    key.append(constructor ? className.substring(className.lastIndexOf('.') + 1) : method.name);
    key.append('(');
    for (int i = 0; i < parameters.size(); i++) {
      Type parameter = parameters.get(i);
      if (i > 0) {
        key.append(',');
      }
      if (i == parameters.size() - 1 && (method.access & ACC_VARARGS) != 0) {
        key.append(component(parameter)).append("...");
      } else {
        key.append(parameter);
      }
    }
    String methodSig = key.append(')').toString();

    for (TypeAnnotation a : method.typeAnnotations) {
      String annotation =
          a.descriptor.equals(NULLABLE_DESCRIPTOR)
              ? NULLABLE
              : a.descriptor.equals(NON_NULL_DESCRIPTOR) ? NON_NULL : null;
      if (annotation == null) {
        continue;
      }
      switch (a.target) {
        case METHOD_TYPE_PARAMETER_BOUND -> {
          if (annotation.equals(NULLABLE) && isTopLevel(bound(typeParameters, a), a.path)) {
            cache.cacheMethodTypeParamNullableUpperBound(methodSig, a.index);
          }
        }
        case METHOD_RETURN, METHOD_FORMAL_PARAMETER -> {
          int index = a.index;
          if (constructor && index == -1) {
            // javac stores an annotation on a constructor declaration as one on its return type,
            // which is the constructed class and has no model
            continue;
          }
          ImmutableList<TypePathEntry> steps =
              typePath(index == -1 ? returnType : parameters.get(index), a.path);
          if (steps == null) {
            continue;
          }
          if (steps.isEmpty()) {
            cache.cacheAnnotation(methodSig, index, annotation);
          } else {
            cache.cacheNestedAnnotation(
                methodSig,
                index,
                new NestedAnnotationInfo(
                    annotation.equals(NULLABLE) ? Annotation.NULLABLE : Annotation.NONNULL, steps));
          }
        }
        default -> {}
      }
    }
  }

  /**
   * Converts a class-file type path (JVMS 4.7.20.2) into {@link TypePathEntry} steps.
   *
   * <p>At a class type, a class-file path first takes one step into a nested type per enclosing
   * type, and a {@link TypePathEntry} path starts at the innermost class, so a path that stops at
   * an enclosing type or indexes its type arguments has no {@link TypePathEntry} form.
   *
   * @param type the annotated type, which the path walks
   * @param path pairs of {@code type_path_kind} and {@code type_argument_index}
   * @return the steps, empty for an annotation on {@code type} itself, or {@code null} when the
   *     annotation is on an enclosing type of an inner class
   */
  private @Nullable ImmutableList<TypePathEntry> typePath(Type type, int[] path) {
    ImmutableList.Builder<TypePathEntry> steps = ImmutableList.builder();
    int i = 0;
    while (true) {
      if (type.kind == 'C') {
        int nestedSteps = 0;
        for (; i < path.length && path[i] == 1; i += 2) {
          nestedSteps++;
        }
        if (nestedSteps != enclosingTypes(type.binaryName)) {
          return null;
        }
        while (type.inner != null) {
          type = type.inner;
        }
      }
      if (i == path.length) {
        return steps.build();
      }
      int kind = path[i];
      int argument = path[i + 1];
      i += 2;
      if (kind == 0 && type.kind == '[') { // deeper in an array type
        steps.add(new TypePathEntry(Kind.ARRAY_ELEMENT, -1));
        type = component(type);
      } else if (kind == 2 && (type.kind == '+' || type.kind == '-')) { // the bound of a wildcard
        steps.add(new TypePathEntry(Kind.WILDCARD_BOUND, type.kind == '+' ? 0 : 1));
        type = component(type);
      } else if (kind == 3 && type.kind == 'C') { // a type argument
        steps.add(new TypePathEntry(Kind.TYPE_ARGUMENT, argument));
        type = type.arguments.get(argument);
      } else {
        throw new IllegalArgumentException("type_path_kind " + kind + " cannot enter " + type);
      }
    }
  }

  /** Returns whether a path leads to {@code type} itself rather than to a type inside it. */
  private boolean isTopLevel(Type type, int[] path) {
    ImmutableList<TypePathEntry> steps = typePath(type, path);
    return steps != null && steps.isEmpty();
  }

  /**
   * Returns how many enclosing types a class type has: one per enclosing class, counted outward
   * from {@code binaryName} up to the first class that is not an inner class.
   */
  private int enclosingTypes(String binaryName) {
    int count = 0;
    for (String c = enclosingOfInnerClass.get(binaryName);
        c != null;
        c = enclosingOfInnerClass.get(c)) {
      count++;
    }
    return count;
  }

  /** Returns the bound a type-parameter bound annotation is on. */
  private static Type bound(List<TypeParameter> typeParameters, TypeAnnotation a) {
    Type bound = typeParameters.get(a.index).bounds().get(a.boundIndex);
    if (bound == null) {
      throw new IllegalArgumentException(
          "type parameter " + a.index + " has no bound " + a.boundIndex);
    }
    return bound;
  }

  /** Returns the element type of an array, or the bound of a wildcard. */
  private static Type component(Type type) {
    Type component = type.component;
    if (component == null) {
      throw new IllegalArgumentException("type path enters " + type + ", which has no component");
    }
    return component;
  }

  private int u1() {
    return bytes[pos++] & 0xff;
  }

  private int u2() {
    int value = ((bytes[pos] & 0xff) << 8) | (bytes[pos + 1] & 0xff);
    pos += 2;
    return value;
  }

  private int u4() {
    return (u2() << 16) | u2();
  }

  /** Records where each string and class entry is, decoding no string yet. */
  private void readConstantPool() throws IOException {
    int count = u2();
    utf8Offset = new int[count];
    utf8 = new String[count];
    classNameIndex = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = u1();
      switch (tag) {
        case 1 -> { // Utf8
          utf8Offset[i] = pos;
          int length = u2();
          pos += length;
        }
        case 7 -> classNameIndex[i] = u2(); // Class
        case 8, 16, 19, 20 -> pos += 2; // String, MethodType, Module, Package
        case 15 -> pos += 3; // MethodHandle
        case 3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4;
        case 5, 6 -> { // Long, Double
          pos += 8;
          i++; // they take two entries
        }
        default -> throw new IOException("unknown constant pool tag " + tag + " at entry " + i);
      }
    }
  }

  /** Decodes a {@code CONSTANT_Utf8} entry, once. */
  private String utf8(int index) throws IOException {
    String value = utf8[index];
    if (value != null) {
      return value;
    }
    int offset = utf8Offset[index];
    if (offset == 0) {
      throw new IOException("constant pool entry " + index + " is not a Utf8 entry");
    }
    int length = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    int start = offset + 2;
    boolean ascii = true;
    for (int i = start; i < start + length && ascii; i++) {
      ascii = bytes[i] > 0; // modified UTF-8 encodes U+0000 in two bytes
    }
    value =
        ascii
            ? new String(bytes, start, length, StandardCharsets.ISO_8859_1)
            : new DataInputStream(new ByteArrayInputStream(bytes, offset, length + 2)).readUTF();
    utf8[index] = value;
    return value;
  }

  /** Reads a {@code RuntimeVisibleAnnotations} attribute and returns the annotation types. */
  private List<String> readAnnotationDescriptors() throws IOException {
    List<String> descriptors = new ArrayList<>();
    for (int count = u2(); count > 0; count--) {
      descriptors.add(utf8(u2()));
      skipElementValuePairs();
    }
    return descriptors;
  }

  /** Reads a {@code RuntimeVisibleTypeAnnotations} attribute (JVMS 4.7.20). */
  private List<TypeAnnotation> readTypeAnnotations() throws IOException {
    List<TypeAnnotation> annotations = new ArrayList<>();
    for (int count = u2(); count > 0; count--) {
      int target = u1();
      int index = -1;
      int boundIndex = -1;
      switch (target) {
        case 0x00, 0x01, METHOD_FORMAL_PARAMETER -> index = u1();
        case 0x10, 0x17 -> index = u2(); // supertype, throws
        case CLASS_TYPE_PARAMETER_BOUND, METHOD_TYPE_PARAMETER_BOUND -> {
          index = u1();
          boundIndex = u1();
        }
        case FIELD, METHOD_RETURN, 0x15 -> {} // 0x15: receiver
        default ->
            throw new IOException(
                "unexpected type annotation target 0x" + Integer.toHexString(target));
      }
      int[] path = new int[2 * u1()];
      for (int i = 0; i < path.length; i++) {
        path[i] = u1();
      }
      annotations.add(new TypeAnnotation(target, index, boundIndex, path, utf8(u2())));
      skipElementValuePairs();
    }
    return annotations;
  }

  /** Moves past the {@code element_value_pairs} of an annotation. */
  private void skipElementValuePairs() {
    for (int count = u2(); count > 0; count--) {
      pos += 2; // element_name_index
      skipElementValue();
    }
  }

  /** Moves past one {@code element_value} (JVMS 4.7.16.1). */
  private void skipElementValue() {
    switch (u1()) {
      case 'e' -> pos += 4;
      case '@' -> {
        pos += 2;
        skipElementValuePairs();
      }
      case '[' -> {
        for (int count = u2(); count > 0; count--) {
          skipElementValue();
        }
      }
      default -> pos += 2;
    }
  }

  /**
   * A type from a signature or a descriptor, rendered by {@link #toString()} as javac renders it.
   * {@code kind} is {@code P} for a primitive or void, {@code C} for a class, {@code T} for a type
   * variable, {@code [} for an array, {@code *} for an unbounded wildcard, and {@code +} or {@code
   * -} for a wildcard with an upper or a lower bound.
   */
  private static final class Type {
    final char kind;
    final String name;
    List<Type> arguments = List.of();

    /** The element type of an array, or the bound of a wildcard. */
    @Nullable Type component;

    /** The next segment of {@code Outer<T>.Inner}. */
    @Nullable Type inner;

    /**
     * The internal name of the innermost class, such as {@code java/util/Map$Entry}, on the first
     * segment of a class type; empty elsewhere.
     */
    String binaryName = "";

    Type(char kind, String name) {
      this.kind = kind;
      this.name = name;
    }

    @Override
    public String toString() {
      StringBuilder result = new StringBuilder();
      appendTo(result);
      return result.toString();
    }

    private void appendTo(StringBuilder out) {
      switch (kind) {
        case '[' -> {
          component(this).appendTo(out);
          out.append("[]");
        }
        case '*' -> out.append('?');
        case '+' -> component(this).appendTo(out.append("? extends "));
        case '-' -> component(this).appendTo(out.append("? super "));
        default -> {
          out.append(name);
          if (!arguments.isEmpty()) {
            out.append('<');
            for (int i = 0; i < arguments.size(); i++) {
              if (i > 0) {
                out.append(',');
              }
              arguments.get(i).appendTo(out);
            }
            out.append('>');
          }
          if (inner != null) {
            inner.appendTo(out.append('.'));
          }
        }
      }
    }
  }

  /**
   * A type parameter of a signature. {@code bounds} starts with the class bound, {@code null} when
   * the signature leaves it empty, so that a {@code bound_index} indexes it directly.
   */
  private record TypeParameter(String name, List<@Nullable Type> bounds) {}

  /**
   * Parses a class, method, or field signature (JVMS 4.7.9.1), or a descriptor, which is a subset
   * of one.
   */
  private static final class SignatureParser {
    private final String s;
    private int pos;

    SignatureParser(String signature) {
      this.s = signature;
    }

    /** Returns the type parameters, and moves past them. */
    List<TypeParameter> typeParameters() {
      List<TypeParameter> typeParameters = new ArrayList<>();
      if (s.charAt(pos) == '<') {
        pos++;
        while (s.charAt(pos) != '>') {
          int colon = s.indexOf(':', pos);
          String name = s.substring(pos, colon);
          pos = colon;
          List<@Nullable Type> bounds = new ArrayList<>();
          while (s.charAt(pos) == ':') {
            pos++;
            bounds.add(s.charAt(pos) == ':' ? null : type()); // the class bound may be empty
          }
          typeParameters.add(new TypeParameter(name, bounds));
        }
        pos++;
      }
      return typeParameters;
    }

    /** Returns the parameter types of a method signature, and moves past them. */
    List<Type> parameters() {
      List<Type> parameters = new ArrayList<>();
      pos++; // '('
      while (s.charAt(pos) != ')') {
        parameters.add(type());
      }
      pos++;
      return parameters;
    }

    /**
     * Returns the type at the current position, and moves past it. A class type is returned as its
     * first segment, which holds {@link Type#binaryName}, with the later segments chained through
     * {@link Type#inner}.
     */
    Type type() {
      char c = s.charAt(pos++);
      switch (c) {
        case 'L' -> {
          int start = pos;
          Type outer = classSegment(true);
          int end = start;
          while ("<.;".indexOf(s.charAt(end)) < 0) {
            end++;
          }
          StringBuilder binaryName = new StringBuilder(s.substring(start, end));
          Type last = outer;
          while (s.charAt(pos) == '.') {
            pos++;
            Type inner = classSegment(false);
            binaryName.append('$').append(inner.name);
            last.inner = inner;
            last = inner;
          }
          pos++; // ';'
          outer.binaryName = binaryName.toString();
          return outer;
        }
        case 'T' -> {
          int end = s.indexOf(';', pos);
          Type variable = new Type('T', s.substring(pos, end));
          pos = end + 1;
          return variable;
        }
        case '[' -> {
          Type array = new Type('[', "");
          array.component = type();
          return array;
        }
        default -> {
          return new Type('P', primitive(c));
        }
      }
    }

    /**
     * Returns one segment of a class type, with its type arguments, and moves past it.
     *
     * @param first whether this is the first segment, whose name is a binary name to render with
     *     dots; a later segment has a simple name
     */
    private Type classSegment(boolean first) {
      int start = pos;
      while ("<.;".indexOf(s.charAt(pos)) < 0) {
        pos++;
      }
      String name = s.substring(start, pos);
      Type type = new Type('C', first ? name.replace('/', '.').replace('$', '.') : name);
      if (s.charAt(pos) == '<') {
        pos++;
        List<Type> arguments = new ArrayList<>();
        while (s.charAt(pos) != '>') {
          char c = s.charAt(pos);
          if (c == '*') {
            pos++;
            arguments.add(new Type('*', ""));
          } else if (c == '+' || c == '-') {
            pos++;
            Type wildcard = new Type(c, "");
            wildcard.component = type();
            arguments.add(wildcard);
          } else {
            arguments.add(type());
          }
        }
        pos++;
        type.arguments = arguments;
      }
      return type;
    }

    private static String primitive(char c) {
      return switch (c) {
        case 'B' -> "byte";
        case 'C' -> "char";
        case 'D' -> "double";
        case 'F' -> "float";
        case 'I' -> "int";
        case 'J' -> "long";
        case 'S' -> "short";
        case 'Z' -> "boolean";
        case 'V' -> "void";
        default -> throw new IllegalArgumentException("unknown descriptor character " + c);
      };
    }
  }
}
