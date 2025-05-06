package com.uber.nullaway.javacplugin;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;

public class HelloPlugin implements Plugin {

  @Override
  public String getName() {
    return "HelloPlugin";
  }

  @Override
  public void init(JavacTask task, String... args) {
    task.addTaskListener(
        new TaskListener() {
          @Override
          public void started(TaskEvent e) {
            if (e.getKind() == TaskEvent.Kind.ANALYZE) {
              System.out.println("[HelloPlugin] Analyzing: " + e.getTypeElement());
            }
          }

          @Override
          public void finished(TaskEvent e) {
            // no-op
          }
        });
  }
}
