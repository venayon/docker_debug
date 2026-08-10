

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Utility that inspects the feature files wired to the currently running
 * Cucumber JUnit-Platform runner and reports how many of them (and how many
 * scenarios / example rows within them) match the runner's tag filter.
 *
 * <p>Typical use: called once at suite start-up to log a quick summary of
 * what is about to execute, e.g. "Runner X will execute 12 features / 47
 * scenarios matching tag @regression (scanned in 184ms)".
 *
 * <p>This class is deliberately defensive: any failure to introspect the
 * runner or read a feature file is turned into a clear, actionable
 * exception/log line rather than an obscure NPE, because it typically runs
 * before the test suite itself, and a silent/half-broken scan should never
 * be allowed to look like "0 matches".
 */
public final class FeatureScanner {

    private static final Logger LOG = LoggerFactory.getLogger(FeatureScanner.class);

    private static final String RUNNER_PACKAGE = "uk.gov.dwp.gysp.acceptancetests.";
    private static final String FEATURE_FILE_SUFFIX = ".feature";

    private FeatureScanner() {
        // Utility class - no instances.
    }

    /**
     * Scans the feature folder configured on the currently executing runner
     * class and returns a summary of how many features/scenarios match the
     * runner's tag filter. Logs the elapsed time regardless of outcome.
     *
     * @return summary of the scan (never null)
     * @throws IOException           if the configured feature folder cannot be read
     * @throws IllegalStateException if the runner cannot be resolved or is missing configuration
     */
    public static FeatureScanResult scanCurrentRunner() throws IOException {
        Instant startedAt = Instant.now();
        try {
            Class<?> runnerClass = getCurrentRunnerClass();
            RunnerConfig config = readRunnerConfig(runnerClass);

            Path featureFolder = Path.of(config.featurePath());
            if (!Files.exists(featureFolder)) {
                throw new IllegalStateException(
                        "Configured feature path does not exist: " + featureFolder.toAbsolutePath());
            }

            List<Path> allFeatureFiles = listFeatureFiles(featureFolder);
            List<Path> matchingFeatureFiles = new ArrayList<>();
            int matchingScenarioCount = 0;

            for (Path featureFile : allFeatureFiles) {
                try {
                    FeatureAnalysisResult analysis = analyseFeatureFile(featureFile, config.tagFilter());
                    if (analysis.isFeatureMatched()) {
                        matchingFeatureFiles.add(featureFile);
                        matchingScenarioCount += analysis.getScenarioCount();
                    }
                } catch (IOException e) {
                    // Don't let one unreadable file abort the whole scan - log and carry on,
                    // but make sure it's visible rather than silently skipped.
                    LOG.warn("Skipping unreadable feature file [{}]: {}", featureFile, e.getMessage());
                }
            }

            FeatureScanResult result = new FeatureScanResult(
                    matchingFeatureFiles.size(), matchingFeatureFiles, matchingScenarioCount);

            Duration elapsed = Duration.between(startedAt, Instant.now());
            LOG.info("Feature scan complete: {} feature file(s), {} scenario(s) matched tag [{}] "
                            + "out of {} feature file(s) scanned (elapsed {} ms).",
                    result.getFeatureCount(), matchingScenarioCount, config.tagFilter(),
                    allFeatureFiles.size(), elapsed.toMillis());

            return result;
        } catch (RuntimeException | IOException e) {
            Duration elapsed = Duration.between(startedAt, Instant.now());
            LOG.error("Feature scan FAILED after {} ms: {}", elapsed.toMillis(), e.getMessage());
            throw e;
        }
    }

    /**
     * Walks the feature folder and collects every {@code .feature} file.
     * Uses try-with-resources so the underlying directory stream is always
     * closed, even if filtering/collection throws.
     */
    private static List<Path> listFeatureFiles(Path featureFolder) throws IOException {
        try (Stream<Path> walk = Files.walk(featureFolder)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(FEATURE_FILE_SUFFIX))
                    .toList();
        }
    }

    /**
     * Reads the {@code cucumber.features} and {@code cucumber.filter.tags}
     * configuration parameters off the runner class's annotations.
     *
     * @throws IllegalStateException if the feature path is not configured
     */
    private static RunnerConfig readRunnerConfig(Class<?> runnerClass) {
        String featurePath = null;
        String tagFilter = null;

        ConfigurationParameter[] parameters =
                runnerClass.getAnnotationsByType(ConfigurationParameter.class);

        for (ConfigurationParameter parameter : parameters) {
            if (Constants.FEATURES_PROPERTY_NAME.equals(parameter.key())) {
                featurePath = parameter.value();
            }
            if (Constants.FILTER_TAGS_PROPERTY_NAME.equals(parameter.key())) {
                tagFilter = parameter.value();
            }
        }

        if (featurePath == null || featurePath.isBlank()) {
            throw new IllegalStateException(
                    "Feature path not found in runner " + runnerClass.getSimpleName()
                            + " - expected a @ConfigurationParameter with key ["
                            + Constants.FEATURES_PROPERTY_NAME + "]");
        }

        if (tagFilter == null || tagFilter.isBlank()) {
            // Not fatal: log so it's obvious every scenario will be treated as "matching".
            LOG.warn("No tag filter ([{}]) configured on runner {} - all scenarios will be counted as matching.",
                    Constants.FILTER_TAGS_PROPERTY_NAME, runnerClass.getSimpleName());
        }

        return new RunnerConfig(featurePath, tagFilter);
    }

    /**
     * Resolves the currently executing runner class from the {@code sun.java.command}
     * system property (falling back to the {@code test} system property), by
     * taking the last dotted/qualified token on the command line and assuming
     * it lives under {@link #RUNNER_PACKAGE}.
     *
     * <p>This is inherently a best-effort heuristic (there is no official API
     * for "which class did the JVM start with"), so failures are wrapped with
     * a message that tells the caller exactly what was found and expected.
     */
    private static Class<?> getCurrentRunnerClass() {
        String javaCommand = System.getProperty("sun.java.command", "");

        String runnerName = Arrays.stream(javaCommand.split("\\s+"))
                .filter(token -> token.contains("."))
                .reduce((first, second) -> second) // last qualified token wins
                .map(token -> token.substring(token.lastIndexOf('.') + 1))
                .orElse(System.getProperty("test", ""));

        LOG.debug("Resolved candidate runner name [{}] from sun.java.command=[{}]", runnerName, javaCommand);

        if (runnerName == null || runnerName.isBlank()) {
            throw new IllegalStateException(
                    "Unable to determine runner: neither 'sun.java.command' nor the 'test' "
                            + "system property yielded a class name.");
        }

        String fqcn = RUNNER_PACKAGE + runnerName;
        try {
            return Class.forName(fqcn);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Unable to resolve current runner class [" + fqcn + "]. "
                            + "Checked package [" + RUNNER_PACKAGE + "] for name derived from "
                            + "sun.java.command/test system properties.", e);
        }
    }

    /**
     * Reads a single feature file line-by-line and works out:
     * <ul>
     *   <li>whether the feature (or any scenario within it) carries the runner's tag, and</li>
     *   <li>how many scenarios / example rows count as "matching" that tag.</li>
     * </ul>
     *
     * <p>Matching rules:
     * <ul>
     *   <li>A tag line ({@code @...}) immediately preceding a {@code Feature:} line
     *       makes the whole feature "feature-level tagged" - every scenario in it counts.</li>
     *   <li>A tag line immediately preceding a {@code Scenario:} counts just that scenario.</li>
     *   <li>A tag line immediately preceding a {@code Scenario Outline:} / {@code Examples:}
     *       block counts every data row in that Examples table (excluding the header row).</li>
     * </ul>
     */
    private static FeatureAnalysisResult analyseFeatureFile(Path featureFile, String runnerTag)
            throws IOException {

        List<String> lines = Files.readAllLines(featureFile);

        boolean featureTagMatched = false;   // true once we've seen the tag anywhere relevant to the feature
        boolean featureLevelTag = false;     // true if the tag sat directly above "Feature:"
        boolean currentScenarioMatched = false; // true if the tag sat directly above the current Scenario/Outline
        boolean exampleSection = false;      // true while inside a matched Examples: table
        boolean exampleHeaderSeen = false;   // true once the Examples: header row has been consumed
        int matchingScenarios = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("@")) {
                for (String token : trimmed.split("\\s+")) {
                    if (token.equalsIgnoreCase(runnerTag)) {
                        featureTagMatched = true;
                        currentScenarioMatched = true;
                    }
                }
                continue; // a tag line can't also be a Feature:/Scenario:/Examples:/table row
            }

            if (trimmed.startsWith("Feature:") && featureTagMatched) {
                featureLevelTag = true;
            }

            if (trimmed.startsWith("Scenario Outline:") || trimmed.startsWith("Scenario:")) {
                // A brand new scenario (outline or plain) starts here - reset per-scenario state.
                // Note: the tag that applies to *this* scenario was already captured into
                // currentScenarioMatched by the "@" branch immediately above, on the previous line.
                boolean scenarioCounts = featureLevelTag || currentScenarioMatched;

                if (trimmed.startsWith("Scenario:") && scenarioCounts) {
                    matchingScenarios++;
                }
                // Scenario Outline itself isn't a runnable scenario - its rows in Examples: are,
                // so we don't increment here; instead we remember scenarioCounts for the Examples check.

                currentScenarioMatched = scenarioCounts;
                exampleSection = false;
                exampleHeaderSeen = false;
                continue;
            }

            if (trimmed.startsWith("Examples:")) {
                exampleSection = featureLevelTag || currentScenarioMatched;
                exampleHeaderSeen = false;
                continue;
            }

            if (exampleSection && trimmed.startsWith("|")) {
                if (exampleHeaderSeen) {
                    matchingScenarios++;
                } else {
                    exampleHeaderSeen = true; // first row after Examples: is the column header, don't count it
                }
            }
        }

        boolean featureMatched = featureTagMatched || matchingScenarios > 0;
        return new FeatureAnalysisResult(featureMatched, matchingScenarios);
    }

    /** Immutable holder for the two configuration values read off the runner. */
    private record RunnerConfig(String featurePath, String tagFilter) {
    }

    /** Result of analysing a single feature file. */
    private static final class FeatureAnalysisResult {

        private final boolean featureMatched;
        private final int scenarioCount;

        private FeatureAnalysisResult(boolean featureMatched, int scenarioCount) {
            this.featureMatched = featureMatched;
            this.scenarioCount = scenarioCount;
        }

        public boolean isFeatureMatched() {
            return featureMatched;
        }

        public int getScenarioCount() {
            return scenarioCount;
        }
    }
}
