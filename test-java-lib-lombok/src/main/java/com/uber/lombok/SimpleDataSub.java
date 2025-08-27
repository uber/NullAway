package com.uber.lombok;

import javax.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class SimpleDataSub extends SimpleDataSuper {
  @Nullable private String field2;
}
