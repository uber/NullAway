package com.uber.nullaway.dataflow;

import javax.lang.model.element.Element;

public interface AccessPathElement {
  Element getJavaElement();

  @Override
  String toString();

  @Override
  boolean equals(Object obj);

  @Override
  int hashCode();
}
