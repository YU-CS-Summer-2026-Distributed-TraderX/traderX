package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import finos.traderx.tradeprocessor.model.EodQuality;
import finos.traderx.tradeprocessor.service.PriceHistoryStore.PriceSample;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * WHICH ENVIRONMENT VARIABLE ACTUALLY SETS THE STALENESS THRESHOLD.
 *
 * <p>The deployed manifests set {@code EOD_QUALITY_STALENESS_SECONDS} while
 * {@code application.properties} spells the placeholder {@code ${EOD_STALENESS_SECONDS:300}}. A
 * spelling mismatch LOOKS like an inert knob, and that reading was reported before it was measured.
 * It is wrong: Spring's {@link SystemEnvironmentPropertySource} resolves a dotted property name
 * against the uppercase underscored environment form, and the environment outranks the properties
 * file, so {@code EOD_QUALITY_STALENESS_SECONDS} binds {@code eod.quality.staleness-seconds}
 * directly and the file's placeholder is never consulted.
 *
 * <p>Both names are therefore live, by two different routes, and this pins both so a future rename
 * cannot quietly disable the operational knob. Behaviour is asserted through {@code classify}
 * rather than by reading a private field: what matters is the threshold the gate actually applies.
 */
class EodQualityStalenessBindingTest {

    private static final String STALENESS_PROPERTY = "eod.quality.staleness-seconds";
    private static final long CLOSE = 1_758_000_000_000L;

    @Test
    void theManifestSpellingBindsThroughRelaxedEnvironmentResolution() {
        EodQualityChecker checker = checkerWithEnvironment(Map.of("EOD_QUALITY_STALENESS_SECONDS", "17"));

        assertEquals(EodQuality.OK, qualityForAge(checker, 17_000L), "17.000s must still be fresh at a 17s threshold");
        assertEquals(EodQuality.STALE, qualityForAge(checker, 17_001L), "17.001s must be stale at a 17s threshold");
        // Proves the value came from the environment and not from the 300 default.
        assertEquals(EodQuality.STALE, qualityForAge(checker, 60_000L));
    }

    @Test
    void theDocumentedPlaceholderSpellingAlsoBinds() {
        EodQualityChecker checker = checkerWithEnvironment(Map.of("EOD_STALENESS_SECONDS", "17"));

        assertEquals(EodQuality.OK, qualityForAge(checker, 17_000L));
        assertEquals(EodQuality.STALE, qualityForAge(checker, 17_001L));
    }

    @Test
    void theManifestSpellingWinsWhenBothAreSet() {
        // The environment's direct hit on the property name outranks the properties file's
        // placeholder, so a rename that dropped the manifest spelling would silently change the
        // threshold rather than fail loudly.
        EodQualityChecker checker = checkerWithEnvironment(Map.of(
            "EOD_QUALITY_STALENESS_SECONDS", "17",
            "EOD_STALENESS_SECONDS", "600"));

        assertEquals(EodQuality.STALE, qualityForAge(checker, 17_001L), "17s (manifest) must win over 600s (file)");
    }

    @Test
    void neitherSetFallsBackToThreeHundredSeconds() {
        EodQualityChecker checker = checkerWithEnvironment(Map.of());

        assertEquals(EodQuality.OK, qualityForAge(checker, 300_000L));
        assertEquals(EodQuality.STALE, qualityForAge(checker, 300_001L));
    }

    /**
     * The SHIPPED properties file, chosen by content rather than by classpath order.
     *
     * <p>The test classpath carries two {@code application.properties}: the module's test copy
     * shadows the shipped one, and it has no EOD keys at all. Binding
     * {@code classpath:application.properties} therefore loads the test copy and every placeholder
     * silently falls back to its default — a probe that reports "unset" for a property that is very
     * much set in production. Measured here before it could become a wrong conclusion: with the
     * shadowing copy bound, the file-placeholder route reads 300 no matter what the environment
     * says. Selecting the file that actually declares the key, and failing loudly when none does,
     * is what makes this test evidence rather than a coincidence.
     */
    private static ResourcePropertySource shippedApplicationProperties() {
        try {
            for (URL url : Collections.list(
                    EodQualityStalenessBindingTest.class.getClassLoader().getResources("application.properties"))) {
                Properties candidate = new Properties();
                try (InputStream in = url.openStream()) {
                    candidate.load(in);
                }
                if (candidate.containsKey(STALENESS_PROPERTY)) {
                    return new ResourcePropertySource(new UrlResource(url));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read application.properties from the classpath", e);
        }
        throw new IllegalStateException(
            "no application.properties on the classpath declares " + STALENESS_PROPERTY
            + " — this test binds the shipped file by content, so a rename there must fail here");
    }

    private static EodQuality qualityForAge(EodQualityChecker checker, long ageMillis) {
        PriceSample sample = new PriceSample(new BigDecimal("100.000000"), CLOSE - ageMillis);
        return checker.classify("IBM", Optional.of(sample), Optional.empty(), CLOSE).quality();
    }

    /**
     * A real Spring environment: the genuine {@link SystemEnvironmentPropertySource} (the class that
     * implements relaxed name resolution) over the checked-in {@code application.properties}, in the
     * same precedence order Boot uses. Process environment variables cannot be set from inside a
     * JVM, so the source is substituted rather than the process mutated; the resolution logic under
     * test is the real one.
     */
    private static EodQualityChecker checkerWithEnvironment(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        environment.getPropertySources().addLast(shippedApplicationProperties());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.register(EodQualityChecker.class);
            context.refresh();
            return context.getBean(EodQualityChecker.class);
        }
    }
}
