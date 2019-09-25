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
