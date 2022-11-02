package com.uber.nullaway;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import javax.annotation.Generated;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_NullAway_FieldInitEntities extends NullAway.FieldInitEntities {

  private final Symbol.ClassSymbol classSymbol;

  private final ImmutableSet<Symbol> nonnullInstanceFields;

  private final ImmutableSet<Symbol> nonnullStaticFields;

  private final ImmutableList<BlockTree> instanceInitializerBlocks;

  private final ImmutableList<BlockTree> staticInitializerBlocks;

  private final ImmutableSet<MethodTree> constructors;

  private final ImmutableSet<MethodTree> instanceInitializerMethods;

  private final ImmutableSet<MethodTree> staticInitializerMethods;

  AutoValue_NullAway_FieldInitEntities(
      Symbol.ClassSymbol classSymbol,
      ImmutableSet<Symbol> nonnullInstanceFields,
      ImmutableSet<Symbol> nonnullStaticFields,
      ImmutableList<BlockTree> instanceInitializerBlocks,
      ImmutableList<BlockTree> staticInitializerBlocks,
      ImmutableSet<MethodTree> constructors,
      ImmutableSet<MethodTree> instanceInitializerMethods,
      ImmutableSet<MethodTree> staticInitializerMethods) {
    if (classSymbol == null) {
      throw new NullPointerException("Null classSymbol");
    }
    this.classSymbol = classSymbol;
    if (nonnullInstanceFields == null) {
      throw new NullPointerException("Null nonnullInstanceFields");
    }
    this.nonnullInstanceFields = nonnullInstanceFields;
    if (nonnullStaticFields == null) {
      throw new NullPointerException("Null nonnullStaticFields");
    }
    this.nonnullStaticFields = nonnullStaticFields;
    if (instanceInitializerBlocks == null) {
      throw new NullPointerException("Null instanceInitializerBlocks");
    }
    this.instanceInitializerBlocks = instanceInitializerBlocks;
    if (staticInitializerBlocks == null) {
      throw new NullPointerException("Null staticInitializerBlocks");
    }
    this.staticInitializerBlocks = staticInitializerBlocks;
    if (constructors == null) {
      throw new NullPointerException("Null constructors");
    }
    this.constructors = constructors;
    if (instanceInitializerMethods == null) {
      throw new NullPointerException("Null instanceInitializerMethods");
    }
    this.instanceInitializerMethods = instanceInitializerMethods;
    if (staticInitializerMethods == null) {
      throw new NullPointerException("Null staticInitializerMethods");
    }
    this.staticInitializerMethods = staticInitializerMethods;
  }

  @Override
  Symbol.ClassSymbol classSymbol() {
    return classSymbol;
  }

  @Override
  ImmutableSet<Symbol> nonnullInstanceFields() {
    return nonnullInstanceFields;
  }

  @Override
  ImmutableSet<Symbol> nonnullStaticFields() {
    return nonnullStaticFields;
  }

  @Override
  ImmutableList<BlockTree> instanceInitializerBlocks() {
    return instanceInitializerBlocks;
  }

  @Override
  ImmutableList<BlockTree> staticInitializerBlocks() {
    return staticInitializerBlocks;
  }

  @Override
  ImmutableSet<MethodTree> constructors() {
    return constructors;
  }

  @Override
  ImmutableSet<MethodTree> instanceInitializerMethods() {
    return instanceInitializerMethods;
  }

  @Override
  ImmutableSet<MethodTree> staticInitializerMethods() {
    return staticInitializerMethods;
  }

  @Override
  public String toString() {
    return "FieldInitEntities{"
        + "classSymbol="
        + classSymbol
        + ", "
        + "nonnullInstanceFields="
        + nonnullInstanceFields
        + ", "
        + "nonnullStaticFields="
        + nonnullStaticFields
        + ", "
        + "instanceInitializerBlocks="
        + instanceInitializerBlocks
        + ", "
        + "staticInitializerBlocks="
        + staticInitializerBlocks
        + ", "
        + "constructors="
        + constructors
        + ", "
        + "instanceInitializerMethods="
        + instanceInitializerMethods
        + ", "
        + "staticInitializerMethods="
        + staticInitializerMethods
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof NullAway.FieldInitEntities) {
      NullAway.FieldInitEntities that = (NullAway.FieldInitEntities) o;
      return this.classSymbol.equals(that.classSymbol())
          && this.nonnullInstanceFields.equals(that.nonnullInstanceFields())
          && this.nonnullStaticFields.equals(that.nonnullStaticFields())
          && this.instanceInitializerBlocks.equals(that.instanceInitializerBlocks())
          && this.staticInitializerBlocks.equals(that.staticInitializerBlocks())
          && this.constructors.equals(that.constructors())
          && this.instanceInitializerMethods.equals(that.instanceInitializerMethods())
          && this.staticInitializerMethods.equals(that.staticInitializerMethods());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= classSymbol.hashCode();
    h$ *= 1000003;
    h$ ^= nonnullInstanceFields.hashCode();
    h$ *= 1000003;
    h$ ^= nonnullStaticFields.hashCode();
    h$ *= 1000003;
    h$ ^= instanceInitializerBlocks.hashCode();
    h$ *= 1000003;
    h$ ^= staticInitializerBlocks.hashCode();
    h$ *= 1000003;
    h$ ^= constructors.hashCode();
    h$ *= 1000003;
    h$ ^= instanceInitializerMethods.hashCode();
    h$ *= 1000003;
    h$ ^= staticInitializerMethods.hashCode();
    return h$;
  }
}
