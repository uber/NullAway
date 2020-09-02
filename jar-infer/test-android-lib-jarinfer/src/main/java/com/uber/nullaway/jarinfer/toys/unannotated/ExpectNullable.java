package com.uber.nullaway.jarinfer.toys.unannotated;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@interface ExpectNullable {}
