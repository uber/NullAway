package com.uber.lib.unannotated;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Library hierarchy with inherited PolyNull models and fixed models on overrides. */
/* @NullMarked */
public final class PolyNullOverrides {
  private PolyNullOverrides() {}

  public static class Base {
    /** Returns the first argument, whose nullness is linked to the return. */
    public Object top(Object linked, Object allowed, @Nullable Object required) {
      return linked;
    }

    /** Returns the linked argument from a generic method. */
    public <T> Object generic(T ignored, Object linked) {
      return linked;
    }

    /** Links list contents while leaving the top-level return nullness independently modeled. */
    public List<Object> nested(List<Object> linked, List<Object> unrelated) {
      return linked;
    }

    /** Models only the list contents, leaving return nullness independently modeled. */
    public Object inputOnly(List<Object> linked) {
      return linked;
    }

    /** Models an input separately from a conditional return contract. */
    public Object conditional(Object linked, Object condition) {
      return condition;
    }
  }

  public static class NullableOverride extends Base {
    /** {@inheritDoc} */
    @Override
    public Object top(Object linked, Object allowed, @Nullable Object required) {
      return super.top(linked, allowed, required);
    }

    /** {@inheritDoc} */
    @Override
    public <T> Object generic(T ignored, Object linked) {
      return super.generic(ignored, linked);
    }

    /** {@inheritDoc} */
    @Override
    public List<Object> nested(List<Object> linked, List<Object> unrelated) {
      return super.nested(linked, unrelated);
    }

    /** {@inheritDoc} */
    @Override
    public Object inputOnly(List<Object> linked) {
      return super.inputOnly(linked);
    }

    /** {@inheritDoc} */
    @Override
    public Object conditional(Object linked, Object condition) {
      return super.conditional(linked, condition);
    }
  }

  public static class NonNullOverride extends Base {
    /** {@inheritDoc} */
    @Override
    public Object top(Object linked, Object allowed, @Nullable Object required) {
      return super.top(linked, allowed, required);
    }

    /** {@inheritDoc} */
    @Override
    public <T> Object generic(T ignored, Object linked) {
      return super.generic(ignored, linked);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable List<Object> nested(List<Object> linked, List<Object> unrelated) {
      return super.nested(linked, unrelated);
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Object inputOnly(List<Object> linked) {
      return super.inputOnly(linked);
    }

    /** {@inheritDoc} */
    @Override
    public Object conditional(Object linked, Object condition) {
      return super.conditional(linked, condition);
    }
  }
}
