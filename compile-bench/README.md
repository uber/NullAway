This is a basic harness to run a Java compilation task in a loop for
benchmarking / profiling purposes.  Basically, you should get the
arguments for the `javac` task you're interested in and then pass them
here.  The easiest way to run is via Gradle (from repo root):
```
./gradlew :compile-bench:run --args='javac_args'
```

The `javac` arguments should use absolute paths since you're running
from the root of the NullAway repo (and should probably include the
`-d` argument).  If that's painful you can possibly hack around things
by adding a symlink to the `compile-bench` folder.
