package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link com.uber.nullaway.NullAway}. */
@RunWith(JUnit4.class)
public class AndroidTest {
  private static final String CORE_FRAGMENT_STUB =
      """
      package android.app;

      import android.content.Context;
      import android.os.Bundle;
      import android.view.LayoutInflater;
      import android.view.View;
      import android.view.ViewGroup;

      public class Fragment {

        public void onAttach(Context context) {}

        public void onCreate(Bundle savedInstanceState) {}

        public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
          return null;
        }
      }
      """;

  private static final String ANDROIDX_FRAGMENT_STUB =
      """
      package androidx.fragment.app;

      import android.content.Context;
      import android.os.Bundle;
      import android.view.LayoutInflater;
      import android.view.View;
      import android.view.ViewGroup;

      public class Fragment {

        public void onAttach(Context context) {}

        public void onCreate(Bundle savedInstanceState) {}

        public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
          return null;
        }

        public void onActivityCreated(Bundle savedInstanceState) {}
      }
      """;

  private static final String SUPPORTLIB_FRAGMENT_STUB =
      """
      package android.support.v4.app;

      import android.content.Context;
      import android.os.Bundle;
      import android.view.LayoutInflater;
      import android.view.View;
      import android.view.ViewGroup;

      public class Fragment {

        public void onAttach(Context context) {}

        public void onCreate(Bundle savedInstanceState) {}

        public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
          return null;
        }
      }
      """;

  private static final String CORE_ACTIVITY_STUB =
      """
      package android.app;

      import android.os.Bundle;
      import android.os.PersistableBundle;

      public class Activity {

        protected void onCreate(Bundle savedInstanceState) {}

        public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {}
      }
      """;

  private static final String ANDROIDX_ACTIVITY_COMPAT_STUB =
      """
      package androidx.core.app;

      import android.os.Bundle;
      import android.os.PersistableBundle;

      public class ActivityCompat {

        protected void onCreate(Bundle savedInstanceState) {}

        public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {}
      }
      """;

  private static final String SUPPORTLIB_ACTIVITY_COMPAT_STUB =
      """
      package android.support.v4.app;

      import android.os.Bundle;
      import android.os.PersistableBundle;

      public class ActivityCompat {

        protected void onCreate(Bundle savedInstanceState) {}

        public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {}
      }
      """;

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper compilationHelper;

  @SuppressWarnings("CheckReturnValue")
  @Before
  public void setup() {
    compilationHelper =
        NullAwayTestsBase.makeTestHelperWithArgs(
            getClass(),
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber,com.ubercab,io.reactivex"));
  }

  // Core Fragment tests

  @Test
  public void coreFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/core/Fragment.java", CORE_FRAGMENT_STUB)
        .addSourceLines(
            "android-success/CoreFragment.java",
            """
            package com.uber.myapplication;

            import android.app.Fragment;
            import android.content.Context;
            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class CoreFragment extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/core/Fragment.java", CORE_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/CoreFragmentWithoutOnAttach.java",
            """
            package com.uber.myapplication;

            import android.app.Fragment;
            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class CoreFragmentWithoutOnAttach extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnAttachInitialisedField' not initialized
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/core/Fragment.java", CORE_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/CoreFragmentWithoutOnCreate.java",
            """
            package com.uber.myapplication;

            import android.app.Fragment;
            import android.content.Context;
            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class CoreFragmentWithoutOnCreate extends Fragment {
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateInitialisedField' not initialized
              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void coreFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/core/Fragment.java", CORE_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/CoreFragmentWithoutOnCreateView.java",
            """
            package com.uber.myapplication;

            import android.app.Fragment;
            import android.content.Context;
            import android.os.Bundle;

            public class CoreFragmentWithoutOnCreateView extends Fragment {

              private Object mOnCreateInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateViewInitialisedField' not initialized
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  // AndroidX Library Fragment
  @Test
  public void androidxFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/androidx/Fragment.java", ANDROIDX_FRAGMENT_STUB)
        .addSourceLines(
            "android-success/AndroidxFragment.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;
            import androidx.fragment.app.Fragment;

            public class AndroidxFragment extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;
              private Object monActivityCreatedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }

              @Override
              public void onActivityCreated(Bundle savedInstanceState) {
                super.onActivityCreated(savedInstanceState);
                monActivityCreatedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/androidx/Fragment.java", ANDROIDX_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/AndroidxFragmentWithoutOnAttach.java",
            """
            package com.uber.myapplication;

            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;
            import androidx.fragment.app.Fragment;

            public class AndroidxFragmentWithoutOnAttach extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnAttachInitialisedField' not initialized
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/androidx/Fragment.java", ANDROIDX_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/AndroidxFragmentWithoutOnCreate.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;
            import androidx.fragment.app.Fragment;

            public class AndroidxFragmentWithoutOnCreate extends Fragment {
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateInitialisedField' not initialized
              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void androidxFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/androidx/Fragment.java", ANDROIDX_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/AndroidxFragmentWithoutOnCreateView.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import androidx.fragment.app.Fragment;

            public class AndroidxFragmentWithoutOnCreateView extends Fragment {

              private Object mOnCreateInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateViewInitialisedField' not initialized
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  // Android support library Fragment

  @Test
  public void supportLibFragmentSuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/supportlib/Fragment.java", SUPPORTLIB_FRAGMENT_STUB)
        .addSourceLines(
            "android-success/SupportLibraryFragment.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import android.support.v4.app.Fragment;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class SupportLibraryFragment extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnAttachError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/supportlib/Fragment.java", SUPPORTLIB_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/SupportLibraryFragmentWithoutOnAttach.java",
            """
            package com.uber.myapplication;

            import android.os.Bundle;
            import android.support.v4.app.Fragment;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class SupportLibraryFragmentWithoutOnAttach extends Fragment {

              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnAttachInitialisedField' not initialized
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/supportlib/Fragment.java", SUPPORTLIB_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/SupportLibraryFragmentWithoutOnCreate.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import android.support.v4.app.Fragment;
            import android.view.LayoutInflater;
            import android.view.View;
            import android.view.ViewGroup;

            public class SupportLibraryFragmentWithoutOnCreate extends Fragment {
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateInitialisedField' not initialized
              private Object mOnCreateInitialisedField;
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public View onCreateView(
                  LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
                mOnCreateViewInitialisedField = new Object();
                return super.onCreateView(inflater, container, savedInstanceState);
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void supportLibFragmentMissingOnCreateViewError() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/supportlib/Fragment.java", SUPPORTLIB_FRAGMENT_STUB)
        .addSourceLines(
            "android-error/SupportLibraryFragmentWithoutOnCreateView.java",
            """
            package com.uber.myapplication;

            import android.content.Context;
            import android.os.Bundle;
            import android.support.v4.app.Fragment;

            public class SupportLibraryFragmentWithoutOnCreateView extends Fragment {

              private Object mOnCreateInitialisedField;
              // BUG: Diagnostic contains: @NonNull field 'mOnCreateViewInitialisedField' not initialized
              private Object mOnCreateViewInitialisedField;
              private Object mOnAttachInitialisedField;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField = new Object();
              }

              @Override
              public void onAttach(Context context) {
                super.onAttach(context);
                mOnAttachInitialisedField = new Object();
              }
            }
            """)
        .doTest();
  }

  // Core Activity

  @Test
  public void coreActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/core/Activity.java", CORE_ACTIVITY_STUB)
        .addSourceLines(
            "android-success/CoreActivity.java",
            """
            package com.uber.myapplication;

            import android.app.Activity;
            import android.os.Bundle;
            import android.os.PersistableBundle;

            public class CoreActivity extends Activity {

              private Object mOnCreateInitialisedField1;
              private Object mOnCreateInitialisedField2;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField1 = new Object();
              }

              @Override
              public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField2 = new Object();
              }
            }
            """)
        .doTest();
  }

  // Support Library Activity

  @Test
  public void supportLibActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines(
            "androidstubs/supportlib/ActivityCompat.java", SUPPORTLIB_ACTIVITY_COMPAT_STUB)
        .addSourceLines(
            "android-success/SupportLibActivityCompat.java",
            """
            package com.uber.myapplication;

            import android.os.Bundle;
            import android.os.PersistableBundle;
            import android.support.v4.app.ActivityCompat;

            public class SupportLibActivityCompat extends ActivityCompat {

              private Object mOnCreateInitialisedField1;
              private Object mOnCreateInitialisedField2;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField1 = new Object();
              }

              @Override
              public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField2 = new Object();
              }
            }
            """)
        .doTest();
  }

  // AndroidX Library Activity

  @Test
  public void androidxActivitySuccess() {
    initializeAndroidCoreClasses();
    compilationHelper
        .addSourceLines("androidstubs/androidx/ActivityCompat.java", ANDROIDX_ACTIVITY_COMPAT_STUB)
        .addSourceLines(
            "android-success/AndroidxActivityCompat.java",
            """
            package com.uber.myapplication;

            import android.os.Bundle;
            import android.os.PersistableBundle;
            import androidx.core.app.ActivityCompat;

            public class AndroidxActivityCompat extends ActivityCompat {

              private Object mOnCreateInitialisedField1;
              private Object mOnCreateInitialisedField2;

              @Override
              public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField1 = new Object();
              }

              @Override
              public void onCreate(Bundle savedInstanceState, PersistableBundle persistentState) {
                super.onCreate(savedInstanceState);
                mOnCreateInitialisedField2 = new Object();
              }
            }
            """)
        .doTest();
  }

  /** Adds the Android stub sources that all the tests in this class share. */
  @SuppressWarnings("CheckReturnValue")
  private void initializeAndroidCoreClasses() {
    compilationHelper
        .addSourceLines(
            "androidstubs/core/Context.java",
            """
            package android.content;

            public class Context {}
            """)
        .addSourceLines(
            "androidstubs/core/Bundle.java",
            """
            package android.os;

            public class Bundle {}
            """)
        .addSourceLines(
            "androidstubs/core/LayoutInflater.java",
            """
            package android.view;

            public class LayoutInflater {}
            """)
        .addSourceLines(
            "androidstubs/core/PersistableBundle.java",
            """
            package android.os;

            public class PersistableBundle {}
            """)
        .addSourceLines(
            "androidstubs/core/View.java",
            """
            package android.view;

            public class View {}
            """)
        .addSourceLines(
            "androidstubs/core/ViewGroup.java",
            """
            package android.view;

            public class ViewGroup {}
            """);
  }
}
