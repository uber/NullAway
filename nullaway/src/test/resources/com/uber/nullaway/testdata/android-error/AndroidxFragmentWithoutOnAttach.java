package com.uber.myapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;

public class AndroidxFragmentWithoutOnAttach extends Fragment {

  private Object mOnCreateInitialisedField;
  private Object mOnCreateViewInitialisedField;
  // BUG: Diagnostic contains: @NonNull field mOnAttachInitialisedField not initialized
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
