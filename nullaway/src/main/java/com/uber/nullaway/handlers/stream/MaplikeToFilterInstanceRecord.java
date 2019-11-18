package com.uber.nullaway.handlers.stream;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;

/**
 * Internal bookeeping record that keeps track of the model of a map-like method and the previous
 * filter method's inner method tree. See RxNullabilityPropagator documentation and diagram.
 */
public class MaplikeToFilterInstanceRecord {

  private final MaplikeMethodRecord mapMR;

  public MaplikeMethodRecord getMaplikeMethodRecord() {
    return mapMR;
  }

  private final Tree filter;

  public Tree getFilter() {
    return filter;
  }

  public MaplikeToFilterInstanceRecord(MaplikeMethodRecord mapMR, Tree filter) {
    assert (filter instanceof MethodTree || filter instanceof LambdaExpressionTree);
    this.mapMR = mapMR;
    this.filter = filter;
  }
}
