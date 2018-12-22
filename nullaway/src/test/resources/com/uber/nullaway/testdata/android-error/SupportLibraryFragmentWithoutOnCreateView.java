package com.uber.myapplication;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;

public class SupportLibraryFragmentWithoutOnCreateView extends Fragment {

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
