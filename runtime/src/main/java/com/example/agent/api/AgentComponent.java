package com.example.agent.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentComponent {
    String id();

    String name() default "";

    String description() default "";

    String workflow() default "";
}
