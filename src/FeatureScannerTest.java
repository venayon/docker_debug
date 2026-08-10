package uk.gov.dwp.gysp.acceptancetests.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link FeatureScanner}'s feature-file parsing purely against files on
 * disk - no Cucumber runner, no reflection-based class loading, no JVM launch
 * command. {@code scanCurrentRunner()} is intentionally NOT exercised here
 * because it depends on {@code sun.java.command}/an actual runner class on
 * the classpath; that's integration-test territory, not unit-test territory.
 *
 * <p>{@code analyseFeatureFile} and {@code listFeatureFiles} are private, so
 * they're invoked via reflection. This keeps {@link FeatureScanner}'s public
 * surface minimal (it's a utility class) while still letting the actual
 * parsing rules be verified directly and independently of any JVM/runner
 * plumbing.
 */
@DisplayName("FeatureScanner feature-file parsing")
class FeatureScannerTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------
    // Reflection helpers - keep the test bodies free of boilerplate.
    // ---------------------------------------------------------------

    private Path writeFeatureFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    /** Invokes the private {@code analyseFeatureFile(Path, String)} method and unpacks the result. */
    private AnalysisView analyse(Path featureFile, String tag) throws Exception {
        Method method = FeatureScanner.class.getDeclaredMethod("analyseFeatureFile", Path.class, String.class);
        method.setAccessible(true);
        Object result = method.invoke(null, featureFile, tag);

        Method matched = result.getClass().getDeclaredMethod("featureMatched");
        Method count = result.getClass().getDeclaredMethod("scenarioCount");
        matched.setAccessible(true);
        count.setAccessible(true);

        return new AnalysisView((boolean) matched.invoke(result), (int) count.invoke(result));
    }

    /** Invokes the private {@code listFeatureFiles(Path)} method. */
    @SuppressWarnings("unchecked")
    private List<Path> listFeatureFiles(Path folder) throws Exception {
        Method method = FeatureScanner.class.getDeclaredMethod("listFeatureFiles", Path.class);
        method.setAccessible(true);
        return (List<Path>) method.invoke(null, folder);
    }

    /** Plain DTO mirroring the private FeatureAnalysisResult record, for readable assertions. */
    private record AnalysisView(boolean featureMatched, int scenarioCount) {
    }

    // ---------------------------------------------------------------
    // Feature-level tag
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("feature-level tag")
    class FeatureLevelTag {

        @Test
        @DisplayName("tag above Feature: matches every scenario in the file")
        void featureTagCoversAllScenarios() throws Exception {
            String feature = """
                    @regression
                    Feature: Sample feature

                      Scenario: First scenario
                        Given a precondition
                        Then an outcome

                      Scenario: Second scenario
                        Given another precondition
                        Then another outcome
                    """;
            Path file = writeFeatureFile("feature_level.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            assertEquals(2, result.scenarioCount());
        }

        @Test
        @DisplayName("no matching tag anywhere means feature is not matched")
        void noTagMatchYieldsNoMatch() throws Exception {
            String feature = """
                    @smoke
                    Feature: Sample feature

                      Scenario: First scenario
                        Given a precondition
                    """;
            Path file = writeFeatureFile("no_match.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertFalse(result.featureMatched());
            assertEquals(0, result.scenarioCount());
        }
    }

    // ---------------------------------------------------------------
    // Scenario-level tag
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("scenario-level tag")
    class ScenarioLevelTag {

        @Test
        @DisplayName("tag above one Scenario: only counts that scenario")
        void onlyTaggedScenarioCounted() throws Exception {
            String feature = """
                    Feature: Sample feature

                      @regression
                      Scenario: Tagged scenario
                        Given a precondition

                      Scenario: Untagged scenario
                        Given another precondition
                    """;
            Path file = writeFeatureFile("scenario_level.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            assertEquals(1, result.scenarioCount());
        }

        @Test
        @DisplayName("tag matching is case-insensitive")
        void tagMatchIsCaseInsensitive() throws Exception {
            String feature = """
                    Feature: Sample feature

                      @Regression
                      Scenario: Tagged scenario
                        Given a precondition
                    """;
            Path file = writeFeatureFile("case_insensitive.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            assertEquals(1, result.scenarioCount());
        }

        @Test
        @DisplayName("multiple tags on one line are all considered")
        void multipleTagsOnOneLine() throws Exception {
            String feature = """
                    Feature: Sample feature

                      @smoke @regression @slow
                      Scenario: Multi-tagged scenario
                        Given a precondition
                    """;
            Path file = writeFeatureFile("multi_tag.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            assertEquals(1, result.scenarioCount());
        }
    }

    // ---------------------------------------------------------------
    // Scenario Outline / Examples
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("scenario outline with examples")
    class OutlineWithExamples {

        @Test
        @DisplayName("data rows are counted, header row is not")
        void examplesDataRowsCountedExcludingHeader() throws Exception {
            String feature = """
                    Feature: Sample feature

                      @regression
                      Scenario Outline: Outline scenario
                        Given a value <value>

                        Examples:
                          | value |
                          | 1     |
                          | 2     |
                          | 3     |
                    """;
            Path file = writeFeatureFile("outline.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            assertEquals(3, result.scenarioCount());
        }

        @Test
        @DisplayName("untagged outline contributes nothing")
        void untaggedOutlineNotCounted() throws Exception {
            String feature = """
                    Feature: Sample feature

                      Scenario Outline: Outline scenario
                        Given a value <value>

                        Examples:
                          | value |
                          | 1     |
                          | 2     |
                    """;
            Path file = writeFeatureFile("untagged_outline.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertFalse(result.featureMatched());
            assertEquals(0, result.scenarioCount());
        }

        @Test
        @DisplayName("mixed file: plain scenario + outline are counted independently")
        void mixedScenarioAndOutline() throws Exception {
            String feature = """
                    Feature: Sample feature

                      @regression
                      Scenario: Plain scenario
                        Given a precondition

                      @regression
                      Scenario Outline: Outline scenario
                        Given a value <value>

                        Examples:
                          | value |
                          | 1     |
                          | 2     |
                    """;
            Path file = writeFeatureFile("mixed.feature", feature);

            AnalysisView result = analyse(file, "@regression");

            assertTrue(result.featureMatched());
            // 1 plain scenario + 2 example rows
            assertEquals(3, result.scenarioCount());
        }
    }

    // ---------------------------------------------------------------
    // Directory scanning
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("listFeatureFiles")
    class ListFeatureFiles {

        @BeforeEach
        void setUp() throws IOException {
            writeFeatureFile("a.feature", "Feature: A\n");
            writeFeatureFile("b.feature", "Feature: B\n");
            writeFeatureFile("ignore.txt", "not a feature file\n");
            Files.createDirectories(tempDir.resolve("nested"));
            Files.writeString(tempDir.resolve("nested/c.feature"), "Feature: C\n");
        }

        @Test
        @DisplayName("finds .feature files recursively and ignores everything else")
        void findsOnlyFeatureFilesRecursively() throws Exception {
            List<Path> files = listFeatureFiles(tempDir);

            assertEquals(3, files.size());
            assertTrue(files.stream().allMatch(p -> p.toString().endsWith(".feature")));
            assertTrue(files.stream().anyMatch(p -> p.toString().endsWith("nested/c.feature")
                    || p.toString().endsWith("nested\\c.feature"))); // Windows path separator safety
        }

        @Test
        @DisplayName("empty folder yields empty list, not an error")
        void emptyFolderYieldsEmptyList() throws Exception {
            Path emptyDir = Files.createDirectory(tempDir.resolve("empty"));

            List<Path> files = listFeatureFiles(emptyDir);

            assertTrue(files.isEmpty());
        }
    }

    // ---------------------------------------------------------------
    // Robustness
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("blank/empty feature file does not match and does not throw")
        void blankFileIsHandledGracefully() throws Exception {
            Path file = writeFeatureFile("blank.feature", "");

            AnalysisView result = analyse(file, "@regression");

            assertFalse(result.featureMatched());
            assertEquals(0, result.scenarioCount());
        }

        @Test
        @DisplayName("null tag filter never throws, simply matches nothing")
        void nullTagFilterDoesNotThrow() throws Exception {
            String feature = """
                    @regression
                    Feature: Sample feature

                      Scenario: First scenario
                        Given a precondition
                    """;
            Path file = writeFeatureFile("null_tag.feature", feature);

            AnalysisView result = analyse(file, null);

            assertFalse(result.featureMatched());
            assertEquals(0, result.scenarioCount());
        }
    }
}
