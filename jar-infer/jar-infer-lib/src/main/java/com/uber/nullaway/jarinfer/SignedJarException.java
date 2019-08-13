package com.uber.nullaway.jarinfer;

public class SignedJarException extends SecurityException {

  public SignedJarException(String message) {
    super(message);
  }
}
