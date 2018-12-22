package com.uber.myapplication;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class CoreFragmentWithoutOnAttach extends Fragment {

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
