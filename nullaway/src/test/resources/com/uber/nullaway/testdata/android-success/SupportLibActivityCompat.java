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
