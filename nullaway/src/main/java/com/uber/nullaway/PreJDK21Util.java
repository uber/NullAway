package com.uber.nullaway;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.TypeMetadata;

public class PreJDK21Util {

  public static TypeMetadata createTypeMetadata(
      com.sun.tools.javac.util.List<Attribute.TypeCompound> attrs) {
    return new TypeMetadata(new TypeMetadata.Annotations(attrs));
  }
}
