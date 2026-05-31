package com.example.adapter.inbound;

import com.google.auth.oauth2.GoogleCredentials;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class GoogleCredentialsProvider {
    private static final Logger LOG = Logger.getLogger(GoogleCredentialsProvider.class);
    private static final String CLASSPATH_CREDENTIALS = "/google-credentials.json";

    @ConfigProperty(name = "google.credentials.path", defaultValue = "")
    Optional<String> credentialsPath;

    public GoogleCredentials scoped(List<String> scopes) throws Exception {
        String configuredPath = credentialsPath.orElse("");
        InputStream stream = configuredPath.isBlank()
                ? GoogleCredentialsProvider.class.getResourceAsStream(CLASSPATH_CREDENTIALS)
                : new FileInputStream(configuredPath);
        if (stream == null) {
            LOG.warnf("google_credentials event=missing source=%s", credentialSourceDescription());
            throw new IllegalStateException("Google credentials not found");
        }
        GoogleCredentials credentials = GoogleCredentials.fromStream(stream).createScoped(scopes);
        credentials.refreshIfExpired();
        return credentials;
    }

    public boolean credentialsAvailable() {
        String configuredPath = credentialsPath.orElse("");
        if (!configuredPath.isBlank()) {
            return Files.isRegularFile(Path.of(configuredPath));
        }
        return GoogleCredentialsProvider.class.getResource(CLASSPATH_CREDENTIALS) != null;
    }

    public String credentialSourceDescription() {
        String configuredPath = credentialsPath.orElse("");
        return configuredPath.isBlank() ? "classpath:" + CLASSPATH_CREDENTIALS : "path:" + configuredPath;
    }
}
