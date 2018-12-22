package com.uber.myapplication;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class CoreFragmentWithoutOnCreate extends Fragment {
  // BUG: Diagnostic contains: @NonNull field mOnCreateInitialisedField not initialized
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
