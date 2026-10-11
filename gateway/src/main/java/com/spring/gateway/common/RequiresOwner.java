package com.spring.gateway.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 站长专属接口注解。
 *
 * @author hanbing
 * @since 2026-10-11
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresOwner {

    /** 拒绝时的错误信息 */
    String message() default "仅站长可执行此操作";
}
