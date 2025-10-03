import org.jspecify.annotations.NullMarked;

@NullMarked
module com.uber.test.java.module {
  requires java.base;
  requires static org.jspecify;

  exports com.example.nullmarked;
  exports com.example.nullunmarked;
}
