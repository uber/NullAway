package com.uber.nullaway;

import org.junit.Test;

public class NullAwayKeySetIteratorTests extends NullAwayTestsBase {

  @Test
  public void mapKeySetIteratorBasic() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "public class Test {",
            "  public void keySetStuff(Map<Object, Object> m) {",
            "    for (Object k: m.keySet()) {",
            "      m.get(k).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void mapKeySetIteratorShadowing() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "public class Test {",
            "  private final Object k = new Object();",
            "  public void keySetStuff(Map<Object, Object> m) {",
            "    for (Object k: m.keySet()) {",
            "      m.get(k).toString();",
            "    }",
            "    // BUG: Diagnostic contains: dereferenced expression m.get(k) is @Nullable",
            "    m.get(k).toString();",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void mapKeySetIteratorDeeperAccessPath() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "import java.util.HashMap;",
            "public class Test {",
            "  static class MapWrapper {",
            "    Map<Object,Object> mf = new HashMap<>();",
            "    public Map<Object,Object> getMF() { return mf; }",
            "    public Map<Object,Object> getMF2(int i) { return mf; }",
            "  }",
            "  public void keySetStuff(MapWrapper mw, int j) {",
            "    for (Object k: mw.mf.keySet()) {",
            "      mw.mf.get(k).toString();",
            "    }",
            "    for (Object k: mw.getMF().keySet()) {",
            "      mw.getMF().get(k).toString();",
            "    }",
            "    for (Object k: mw.getMF2(10).keySet()) {",
            "      mw.getMF2(10).get(k).toString();",
            "    }",
            "    for (Object k: mw.getMF2(j).keySet()) {",
            "      // Report error since we cannot represent mw.getMF2(j).get(k) with an access path",
            "      // BUG: Diagnostic contains: dereferenced expression mw.getMF2(j).get(k) is @Nullable",
            "      mw.getMF2(j).get(k).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void unhandledCases() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Iterator;",
            "import java.util.Map;",
            "public class Test {",
            "  public void keySetStuff(Map<Object, Object> m) {",
            "    // BUG: Diagnostic contains: dereferenced expression",
            "    m.get(m.keySet().iterator().next()).toString();",
            "    Iterator<Object> iter = m.keySet().iterator();",
            "    while (iter.hasNext()) {",
            "      // BUG: Diagnostic contains: dereferenced expression",
            "      m.get(iter.next()).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nestedLoops() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import java.util.Map;",
            "public class Test {",
            "  public void keySetStuff(Map<Object, Object> m, Map<Object, Object> m2) {",
            "    for (Object k: m.keySet()) {",
            "      for (Object k2: m2.keySet()) {",
            "        m.get(k).toString();",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        m.get(k2).toString();",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        m2.get(k).toString();",
            "        m2.get(k2).toString();",
            "      }",
            "    }",
            "    // nested loop over the same map",
            "    for (Object k: m.keySet()) {",
            "      for (Object k2: m.keySet()) {",
            "        m.get(k).toString();",
            "        m.get(k2).toString();",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        m2.get(k).toString();",
            "        // BUG: Diagnostic contains: dereferenced expression",
            "        m2.get(k2).toString();",
            "      }",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void declaredTypeSubtypeOfMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            "package com.uber;",
            "import com.google.common.collect.ImmutableMap;",
            "import java.util.TreeMap;",
            "import java.util.LinkedHashMap;",
            "public class Test {",
            "  public void keySetStuff1(ImmutableMap<Object, Object> m) {",
            "    for (Object k: m.keySet()) {",
            "      m.get(k).toString();",
            "    }",
            "  }",
            "  public void keySetStuff2(TreeMap<Object, Object> m) {",
            "    for (Object k: m.keySet()) {",
            "      m.get(k).toString();",
            "    }",
            "  }",
            "  public void keySetStuff3(LinkedHashMap<Object, Object> m) {",
            "    for (Object k: m.keySet()) {",
            "      m.get(k).toString();",
            "    }",
            "  }",
            "}")
        .doTest();
  }
}
