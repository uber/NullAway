package com.uber.myapplication;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class AndroidxFragmentWithoutOnCreateView extends Fragment {

  @NonNull private Object mOnCreateInitialisedField;
  @NonNull private Object mOnCreateViewInitialisedField;
  @NonNull private Object mOnAttachInitialisedField;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mOnCreateInitialisedField = new Object();
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    mOnAttachInitialisedField = new Object();
  }
}
