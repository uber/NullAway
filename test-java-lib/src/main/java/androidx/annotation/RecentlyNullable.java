package androidx.annotation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** stub for testing */
@Retention(CLASS)
@Target({METHOD, PARAMETER, FIELD})
public @interface RecentlyNullable {}
