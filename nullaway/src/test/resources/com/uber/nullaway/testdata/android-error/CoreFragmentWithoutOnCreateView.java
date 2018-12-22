package com.uber.myapplication;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;

public class CoreFragmentWithoutOnCreateView extends Fragment {

  private Object mOnCreateInitialisedField;
  // BUG: Diagnostic contains: @NonNull field mOnCreateViewInitialisedField not initialized
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
