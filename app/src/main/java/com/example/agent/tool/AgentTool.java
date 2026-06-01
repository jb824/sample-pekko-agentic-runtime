package com.example.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentTool {
    String name();
    boolean sourceCapable() default false;
    int timeoutSeconds() default 30;
    int retryAttempts() default 0;
}
