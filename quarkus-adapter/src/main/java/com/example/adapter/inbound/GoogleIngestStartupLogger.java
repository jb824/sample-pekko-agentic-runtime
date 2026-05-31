package com.example.adapter.inbound;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class GoogleIngestStartupLogger {
    private static final Logger LOG = Logger.getLogger(GoogleIngestStartupLogger.class);

    @Inject
    GoogleCredentialsProvider credentialsProvider;

    @ConfigProperty(name = "google.gbp.poll.enabled", defaultValue = "false")
    boolean gbpPollEnabled;

    @ConfigProperty(name = "google.youtube.poll.enabled", defaultValue = "false")
    boolean youtubePollEnabled;

    @ConfigProperty(name = "google.gbp.account-id", defaultValue = "")
    Optional<String> gbpAccountId;

    @ConfigProperty(name = "google.gbp.location-id", defaultValue = "")
    Optional<String> gbpLocationId;

    @ConfigProperty(name = "google.youtube.channel-id", defaultValue = "")
    Optional<String> youtubeChannelId;

    void onStart(@Observes StartupEvent event) {
        boolean credentialsAvailable = credentialsProvider.credentialsAvailable();
        if (!credentialsAvailable) {
            LOG.warnf(
                    "google_ingest event=mock_only reason=missing_credentials credential_source=%s gbp_poll_enabled=%s youtube_poll_enabled=%s mock_endpoints=/mock/google/gbp-review,/mock/google/youtube-comment",
                    credentialsProvider.credentialSourceDescription(),
                    gbpPollEnabled,
                    youtubePollEnabled
            );
            return;
        }

        LOG.infof(
                "google_ingest event=credentials_available credential_source=%s gbp_poll_enabled=%s youtube_poll_enabled=%s gbp_configured=%s youtube_configured=%s",
                credentialsProvider.credentialSourceDescription(),
                gbpPollEnabled,
                youtubePollEnabled,
                configured(gbpAccountId) && configured(gbpLocationId),
                configured(youtubeChannelId)
        );
    }

    private static boolean configured(Optional<String> value) {
        return value.isPresent() && !value.get().isBlank();
    }
}
