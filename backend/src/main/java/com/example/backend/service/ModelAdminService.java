package com.example.backend.service;

import com.example.backend.dto.ModelInfoDto;
import com.example.backend.entity.AppUser;
import com.example.backend.ml.MlServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * Guards the one operation that changes the shared model for everybody.
 *
 * <p>Retraining replaces the model that grades every user's trips, so it is an
 * administrator's maintenance task, not something any account can do. Only the
 * email addresses listed in {@code app.admin-emails} (environment variable
 * {@code ADMIN_EMAILS}) may retrain. The list is empty by default, so out of the
 * box nobody can retrain through the API at all.</p>
 *
 * <p>Every attempt, allowed or refused, is written to the log with the account
 * and the time, and a successful retrain blocks the next one for
 * {@code app.retrain-min-interval-seconds} (ten minutes by default), so even an
 * administrator cannot loop it and tie up the machine.</p>
 */
@Service
public class ModelAdminService {

    private static final Logger log = LoggerFactory.getLogger(ModelAdminService.class);

    private final MlServiceClient mlServiceClient;
    private final Set<String> adminEmails;
    private final Duration minInterval;
    private final Clock clock;
    private Instant lastRetrain;

    @Autowired
    public ModelAdminService(
            MlServiceClient mlServiceClient,
            @Value("${app.admin-emails:}") String adminEmails,
            @Value("${app.retrain-min-interval-seconds:600}") long minIntervalSeconds
    ) {
        this(mlServiceClient, adminEmails, minIntervalSeconds, Clock.systemUTC());
    }

    ModelAdminService(MlServiceClient mlServiceClient, String adminEmails, long minIntervalSeconds, Clock clock) {
        this.mlServiceClient = mlServiceClient;
        this.adminEmails = Arrays.stream(adminEmails == null ? new String[0] : adminEmails.split(","))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.minInterval = Duration.ofSeconds(minIntervalSeconds);
        this.clock = clock;
    }

    public boolean isAdmin(AppUser user) {
        return user != null && user.getEmail() != null
                && adminEmails.contains(user.getEmail().trim().toLowerCase(Locale.ROOT));
    }

    public synchronized ModelInfoDto retrain(AppUser user) {
        String who = user == null ? "anonymous" : user.getEmail();
        Instant now = clock.instant();

        if (!isAdmin(user)) {
            log.warn("AUDIT retrain REFUSED for {} at {}: not an administrator", who, now);
            throw new ResponseStatusException(FORBIDDEN,
                    "Only an administrator can retrain the shared model.");
        }

        if (lastRetrain != null && Duration.between(lastRetrain, now).compareTo(minInterval) < 0) {
            log.warn("AUDIT retrain REFUSED for {} at {}: too soon after the last retrain", who, now);
            throw new ResponseStatusException(TOO_MANY_REQUESTS,
                    "The model was retrained less than " + minInterval.toMinutes()
                            + " minutes ago. Please wait before retraining again.");
        }

        log.info("AUDIT retrain STARTED by {} at {}", who, now);
        ModelInfoDto info = mlServiceClient.retrain();
        lastRetrain = now;
        log.info("AUDIT retrain FINISHED by {}: accuracy {}", who, info == null ? "unknown" : info.accuracy());
        return info;
    }
}
