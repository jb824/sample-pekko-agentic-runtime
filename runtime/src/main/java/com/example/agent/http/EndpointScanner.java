package com.example.agent.http;

import com.example.agent.api.http.Delete;
import com.example.agent.api.http.Get;
import com.example.agent.api.http.HttpEndpoint;
import com.example.agent.api.http.Patch;
import com.example.agent.api.http.Post;
import com.example.agent.api.http.Put;
import com.example.agent.api.http.RolesAllowed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class EndpointScanner {
    private EndpointScanner() {
    }

    static EndpointDescriptor scan(Object endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint instance is required");
        }
        Class<?> endpointClass = endpoint.getClass();
        HttpEndpoint annotation = endpointClass.getAnnotation(HttpEndpoint.class);
        if (annotation == null) {
            throw new IllegalArgumentException("endpoint class is missing @HttpEndpoint: " + endpointClass.getName());
        }
        Set<String> classRoles = roles(endpointClass.getAnnotation(RolesAllowed.class));
        String prefix = normalizePrefix(annotation.value());
        List<EndpointMethodDescriptor> methods = new ArrayList<>();
        Set<String> routes = new LinkedHashSet<>();
        for (Method method : endpointClass.getMethods()) {
            RouteInfo route = route(method);
            if (route == null) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException("endpoint handler must be public: " + method);
            }
            Set<String> methodRoles = new LinkedHashSet<>(classRoles);
            methodRoles.addAll(roles(method.getAnnotation(RolesAllowed.class)));
            String path = normalizePath(route.path());
            String key = route.method() + " " + prefix + path;
            if (!routes.add(key)) {
                throw new IllegalArgumentException("duplicate endpoint route: " + key);
            }
            methods.add(new EndpointMethodDescriptor(route.method(), path, method, methodRoles));
        }
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("endpoint class has no HTTP handler methods: " + endpointClass.getName());
        }
        return new EndpointDescriptor(endpoint, prefix, methods);
    }

    private static RouteInfo route(Method method) {
        if (method.isAnnotationPresent(Get.class)) {
            return new RouteInfo(HttpMethod.GET, method.getAnnotation(Get.class).value());
        }
        if (method.isAnnotationPresent(Post.class)) {
            return new RouteInfo(HttpMethod.POST, method.getAnnotation(Post.class).value());
        }
        if (method.isAnnotationPresent(Put.class)) {
            return new RouteInfo(HttpMethod.PUT, method.getAnnotation(Put.class).value());
        }
        if (method.isAnnotationPresent(Patch.class)) {
            return new RouteInfo(HttpMethod.PATCH, method.getAnnotation(Patch.class).value());
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return new RouteInfo(HttpMethod.DELETE, method.getAnnotation(Delete.class).value());
        }
        return null;
    }

    private static Set<String> roles(RolesAllowed roles) {
        if (roles == null || roles.value().length == 0) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String role : roles.value()) {
            if (role != null && !role.isBlank()) {
                values.add(role.trim());
            }
        }
        return Set.copyOf(values);
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim();
        value = value.startsWith("/") ? value : "/" + value;
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        value = value.startsWith("/") ? value : "/" + value;
        while (value.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private record RouteInfo(HttpMethod method, String path) {
    }
}
