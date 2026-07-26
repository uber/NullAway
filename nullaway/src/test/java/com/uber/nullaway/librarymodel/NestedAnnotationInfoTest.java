package com.uber.nullaway.librarymodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.Annotation;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.TypePathEntry;
import com.uber.nullaway.librarymodel.NestedAnnotationInfo.TypePathEntry.Kind;
import org.junit.Test;

public class NestedAnnotationInfoTest {

  @Test
  public void rejectsEmptyTypePath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NestedAnnotationInfo(Annotation.NULLABLE, ImmutableList.of()));
  }

  @Test
  public void acceptsNonEmptyTypePath() {
    ImmutableList<TypePathEntry> typePath =
        ImmutableList.of(new TypePathEntry(Kind.TYPE_ARGUMENT, 0));

    NestedAnnotationInfo info = new NestedAnnotationInfo(Annotation.NULLABLE, typePath);

    assertEquals(typePath, info.typePath());
  }
}
