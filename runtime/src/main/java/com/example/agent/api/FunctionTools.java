package com.example.agent.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FunctionTools {
    private FunctionTools() {
    }

    public static List<AgentToolDefinition> from(Object source, Object... otherSources) {
        List<Object> sources = new ArrayList<>();
        if (source != null) {
            sources.add(source);
        }
        if (otherSources != null) {
            for (Object otherSource : otherSources) {
                if (otherSource != null) {
                    sources.add(otherSource);
                }
            }
        }
        return from(sources);
    }

    public static List<AgentToolDefinition> from(List<?> sources) {
        Map<String, AgentToolDefinition> definitions = new LinkedHashMap<>();
        if (sources == null) {
            return List.of();
        }
        for (Object source : sources) {
            if (source == null) {
                continue;
            }
            for (AgentToolDefinition definition : scan(source)) {
                if (definitions.containsKey(definition.name())) {
                    throw new IllegalArgumentException("Duplicate function tool: " + definition.name());
                }
                definitions.put(definition.name(), definition);
            }
        }
        return List.copyOf(definitions.values());
    }

    private static List<AgentToolDefinition> scan(Object source) {
        Class<?> toolClass = source instanceof Class<?> clazz ? clazz : source.getClass();
        Object instance = source instanceof Class<?> ? null : source;
        List<AgentToolDefinition> definitions = new ArrayList<>();
        for (Method method : annotatedMethods(toolClass)) {
            Tool annotation = method.getAnnotation(Tool.class);
            String toolName = annotation.name().isBlank() ? method.getName() : annotation.name().trim();
            method.setAccessible(true);
            definitions.add(AgentToolDefinition.named(toolName)
                    .describedAs(description(method, annotation))
                    .sourceCapable(annotation.sourceCapable())
                    .timeout(Duration.ofSeconds(Math.max(1L, annotation.timeoutSeconds())))
                    .handledBy(request -> invoke(source, instance, method, request))
                    .build());
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("No @Tool methods found in " + toolClass.getName());
        }
        return definitions;
    }

    private static List<Method> annotatedMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    methods.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static CompletionStage<AgentToolResult> invoke(
            Object source,
            Object instance,
            Method method,
            AgentToolRequest request
    ) {
        try {
            Object target = instance == null ? instantiate((Class<?>) source) : instance;
            Object value = method.invoke(target, arguments(method, method.getAnnotation(Tool.class), request));
            return resolve(value);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return CompletableFuture.completedFuture(AgentToolResult.failure(cause));
        } catch (Throwable throwable) {
            return CompletableFuture.completedFuture(AgentToolResult.failure(throwable));
        }
    }

    private static Object instantiate(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object[] arguments(Method method, Tool tool, AgentToolRequest request) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }
        if (parameters.length == 1 && parameters[0].getType().equals(AgentToolRequest.class)) {
            return new Object[]{request};
        }
        if (parameters.length == 1 && parameters[0].getType().equals(String.class) && !hasExplicitParamName(parameters[0], tool)) {
            return new Object[]{request.userInput()};
        }
        Object[] values = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            String name = parameterName(parameter, tool, index);
            String raw = request.arguments().get(name);
            if (raw == null) {
                throw new IllegalArgumentException("Missing required tool argument: " + name);
            }
            values[index] = convert(raw, parameter.getType(), name);
        }
        return values;
    }

    private static String parameterName(Parameter parameter, Tool tool, int index) {
        ToolParam annotation = parameter.getAnnotation(ToolParam.class);
        if (annotation != null && !annotation.name().isBlank()) {
            return annotation.name().trim();
        }
        ToolParam[] params = tool == null ? new ToolParam[0] : tool.params();
        if (index >= 0 && index < params.length && !params[index].name().isBlank()) {
            return params[index].name().trim();
        }
        return parameter.getName();
    }

    private static boolean hasExplicitParamName(Parameter parameter, Tool tool) {
        ToolParam annotation = parameter.getAnnotation(ToolParam.class);
        if (annotation != null && !annotation.name().isBlank()) {
            return true;
        }
        return tool != null && tool.params().length > 0 && !tool.params()[0].name().isBlank();
    }

    private static Object convert(String raw, Class<?> type, String name) {
        Objects.requireNonNull(raw, "raw");
        try {
            if (type.equals(String.class)) {
                return raw;
            }
            if (type.equals(int.class) || type.equals(Integer.class)) {
                return Integer.parseInt(raw);
            }
            if (type.equals(long.class) || type.equals(Long.class)) {
                return Long.parseLong(raw);
            }
            if (type.equals(double.class) || type.equals(Double.class)) {
                return Double.parseDouble(raw);
            }
            if (type.equals(float.class) || type.equals(Float.class)) {
                return Float.parseFloat(raw);
            }
            if (type.equals(boolean.class) || type.equals(Boolean.class)) {
                return Boolean.parseBoolean(raw);
            }
            if (type.equals(short.class) || type.equals(Short.class)) {
                return Short.parseShort(raw);
            }
            if (type.equals(byte.class) || type.equals(Byte.class)) {
                return Byte.parseByte(raw);
            }
            if (type.equals(char.class) || type.equals(Character.class)) {
                if (raw.length() != 1) {
                    throw new IllegalArgumentException("Expected one character");
                }
                return raw.charAt(0);
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid value for tool argument " + name + ": " + raw, exception);
        }
        throw new IllegalArgumentException("Unsupported tool parameter type for " + name + ": " + type.getName());
    }

    private static CompletionStage<AgentToolResult> resolve(Object value) {
        if (value instanceof CompletionStage<?> stage) {
            return stage.handle((result, failure) -> {
                if (failure != null) {
                    return AgentToolResult.failure(failure);
                }
                return toToolResult(result);
            });
        }
        return CompletableFuture.completedFuture(toToolResult(value));
    }

    private static AgentToolResult toToolResult(Object value) {
        if (value == null) {
            return AgentToolResult.success("");
        }
        if (value instanceof AgentToolResult result) {
            return result;
        }
        return AgentToolResult.success(String.valueOf(value));
    }

    private static String description(Method method, Tool annotation) {
        StringBuilder description = new StringBuilder(annotation.description());
        List<String> params = new ArrayList<>();
        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            ToolParam param = parameter.getAnnotation(ToolParam.class);
            if (param == null && index < annotation.params().length) {
                param = annotation.params()[index];
            }
            if (param != null && !param.description().isBlank()) {
                params.add(parameterName(parameter, annotation, index) + ": " + param.description());
            }
        }
        if (!params.isEmpty()) {
            description.append(" Arguments: ").append(String.join("; ", params)).append(".");
        }
        return description.toString();
    }
}
