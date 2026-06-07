package com.example.agent.processor;

import com.example.agent.api.AgentComponent;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

@SupportedAnnotationTypes("com.example.agent.api.AgentComponent")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class AgentComponentProcessor extends AbstractProcessor {
    private static final String WORKFLOW_TYPE = "com.example.agent.api.AgentWorkflow";
    private static final String CONSUMER_TYPE = "com.example.agent.runtime.consumer.AgentConsumer";

    private final Set<String> workflows = new LinkedHashSet<>();
    private final Set<String> consumers = new LinkedHashSet<>();
    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
            collect(roundEnv);
            return false;
        }
        if (!generated) {
            generated = true;
            writeServiceFile(WORKFLOW_TYPE, workflows);
            writeServiceFile(CONSUMER_TYPE, consumers);
        }
        return false;
    }

    private void collect(RoundEnvironment roundEnv) {
        TypeElement workflowElement = processingEnv.getElementUtils().getTypeElement(WORKFLOW_TYPE);
        TypeElement consumerElement = processingEnv.getElementUtils().getTypeElement(CONSUMER_TYPE);
        TypeMirror workflowType = workflowElement == null ? null : workflowElement.asType();
        TypeMirror consumerType = consumerElement == null ? null : consumerElement.asType();

        for (Element element : roundEnv.getElementsAnnotatedWith(AgentComponent.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                error(element, "@AgentComponent can only be used on types");
                continue;
            }
            boolean registered = false;
            if (workflowType != null && processingEnv.getTypeUtils().isAssignable(typeElement.asType(), workflowType)) {
                workflows.add(binaryName(typeElement));
                registered = true;
            }
            if (consumerType != null && processingEnv.getTypeUtils().isAssignable(typeElement.asType(), consumerType)) {
                AgentComponent component = typeElement.getAnnotation(AgentComponent.class);
                if (component.workflow().isBlank()) {
                    error(element, "AgentConsumer @AgentComponent must declare workflow");
                }
                consumers.add(binaryName(typeElement));
                registered = true;
            }
            if (!registered) {
                error(element, "@AgentComponent type must implement AgentWorkflow or extend AgentConsumer");
            }
        }
    }

    private String binaryName(TypeElement typeElement) {
        return processingEnv.getElementUtils().getBinaryName(typeElement).toString();
    }

    private void writeServiceFile(String serviceName, Set<String> providers) {
        if (providers.isEmpty()) {
            return;
        }
        try {
            FileObject file = processingEnv.getFiler().createResource(
                    StandardLocation.CLASS_OUTPUT,
                    "",
                    "META-INF/services/" + serviceName
            );
            try (Writer writer = file.openWriter()) {
                for (String provider : providers) {
                    writer.write(provider);
                    writer.write(System.lineSeparator());
                }
            }
        } catch (IOException exception) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to generate ServiceLoader metadata for " + serviceName + ": " + exception.getMessage()
            );
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
