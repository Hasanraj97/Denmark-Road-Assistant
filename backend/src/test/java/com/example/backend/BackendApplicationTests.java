package com.example.backend;

import com.example.backend.config.AppProperties;
import com.example.backend.security.JwtAuthenticationFilter;
import com.example.backend.support.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.crypto.SecretKey;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts, and starts with the settings the tests rely on.
 *
 * <p>This replaces the empty {@code contextLoads} test that shipped with the
 * project. That version ran under the DEFAULT profile, which points at the
 * file-based H2 database in {@code backend/data/} - so simply running the test
 * suite reached into a developer's real data. It now runs under the {@code test}
 * profile like everything else, against a throwaway in-memory database, and it
 * checks the two pieces of configuration the rest of the suite depends on.</p>
 */
class BackendApplicationTests extends AbstractApiTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private SecretKey signingKey;

    @Test
    @DisplayName("the context loads under the test profile")
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(JwtAuthenticationFilter.class)).isNotNull();
        assertThat(mockMvc).isNotNull();
    }

    @Test
    @DisplayName("the tests run on the fixed signing key, not a randomly generated one")
    void signingKeyIsTheFixedTestKey() {
        assertThat(appProperties.jwtSecret()).isEqualTo(TEST_JWT_SECRET);
        assertThat(signingKey).isNotNull();
        assertThat(signingKey.getAlgorithm()).startsWith("HmacSHA");
    }

    @Test
    @DisplayName("the tests run on a throwaway in-memory database")
    void databaseIsInMemory() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.datasource.url"))
                .as("a test run must never write to the developer's real H2 file")
                .startsWith("jdbc:h2:mem:");
    }
}
