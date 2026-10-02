import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.ElementNotInteractableException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Selenium/WebElement wait helpers.
 *
 * <p>
 * Failure contract (kept deliberately lenient so tests can decide what to do):
 * <ul>
 *     <li>Timeouts and unexpected driver errors are logged and reported as
 *         {@code false} / {@link Optional#empty()}.</li>
 *     <li>The one exception: if the element was <em>never present</em> during
 *         the whole wait (i.e. the last swallowed error was a
 *         {@link NoSuchElementException}), an {@link ElementNotFoundException}
 *         is thrown, because that is almost always a locator bug rather than a
 *         timing problem. ({@link #waitForCondition} and the title/URL/page-load
 *         helpers never throw it.)</li>
 * </ul>
 *
 * <p>
 * Backend/service polling that is not DOM related belongs in {@code AwaitUtil}.
 *
 * <p>
 * Note: a plain {@link WebElement} that has gone stale cannot recover by
 * retrying, so callers should re-locate elements rather than rely on these
 * waits to heal a stale reference.
 */
public final class WaitUtil {

    private static final Duration WAIT_TIME = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final int MAX_LOGGED_TEXT_LENGTH = 80;

    private WaitUtil() {
        // Utility class - no instances.
    }

    public static final class ElementNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ElementNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static boolean isGenuinelyNotFound(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NoSuchElementException) {
                return true;
            }
        }
        return false;
    }

    private static WebDriverWait newElementWait(WebDriver driver) {
        return new WebDriverWait(driver, WAIT_TIME)
                .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                .pollingEvery(POLL_INTERVAL);
    }

    // =================================================================
    // Clicking
    // =================================================================

    /**
     * Clicks the element with the native WebDriver click, retrying while the
     * element is not ready or the click is intercepted/not interactable
     * (e.g. an overlay or spinner is still on screen). No JavaScript fallback.
     *
     * @return true if the click was performed, false on timeout or error
     * @throws ElementNotFoundException if the element was never present
     */
    public static boolean safeClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        try {
            return Boolean.TRUE.equals(
                    newElementWait(driver)
                            .ignoring(ElementClickInterceptedException.class,
                                    ElementNotInteractableException.class)
                            .until(d -> attemptClick(element)));
        } catch (TimeoutException e) {
            log("safeClick-timeout", element, e);
            if (isGenuinelyNotFound(e)) {
                throw new ElementNotFoundException("Element never present during safeClick wait", e);
            }
            return false;
        } catch (Exception e) {
            log("safeClick-exception", element, e);
            return false;
        }
    }

    /**
     * Waits for the element to be ready and clicks it. If the native click is
     * intercepted or the element is not interactable, falls back to a
     * JavaScript click.
     *
     * @return true if the click was performed, false on timeout or error
     * @throws ElementNotFoundException if the element was never present
     */
    public static boolean waitAndClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        long startNanos = System.nanoTime();
        try {
            return Boolean.TRUE.equals(
                    newElementWait(driver).until(d -> attemptClick(driver, element)));
        } catch (TimeoutException e) {
            log("waitAndClick-timeout", element, e);
            if (isGenuinelyNotFound(e)) {
                throw new ElementNotFoundException("Element never present during waitAndClick wait", e);
            }
            return false;
        } catch (Exception e) {
            log("waitAndClick-exception", element, e);
            return false;
        } finally {
            System.out.printf("[WaitUtil] waitAndClick took %d ms%n", elapsedMillis(startNanos));
        }
    }

    /** Native click only. Interception exceptions propagate so the caller's wait can retry. */
    private static Boolean attemptClick(WebElement element) {
        if (!isGenuinelyReady(element)) {
            return false;
        }
        // Deliberately no "did the selected state change?" check for checkboxes/radios:
        // clicks are not idempotent, so retrying a click that already worked would
        // toggle the control back (and an already-selected radio never changes state).
        element.click();
        return true;
    }

    /** Native click with a JavaScript fallback when the native click is blocked. */
    private static Boolean attemptClick(final WebDriver driver, WebElement element) {
        if (!isGenuinelyReady(element)) {
            return false;
        }
        try {
            element.click();
            return true;
        } catch (ElementClickInterceptedException | ElementNotInteractableException e) {
            log("click-intercepted-falling-back-to-js", element, e);
            return jsClick(driver, element);
        }
    }

    private static boolean isGenuinelyReady(WebElement element) {
        try {
            if (!element.isEnabled()) {
                return false;
            }
            if ("true".equalsIgnoreCase(element.getAttribute("aria-disabled"))) {
                return false;
            }
            if ("none".equalsIgnoreCase(element.getCssValue("pointer-events"))) {
                return false;
            }
            return true;
        } catch (StaleElementReferenceException | NoSuchElementException e) {
            return false; // let WebDriverWait retry rather than propagate
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    // =================================================================
    // Text / conditions
    // =================================================================

    public static Optional<String> waitForVisibleText(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        try {
            String text = newElementWait(driver).until(d -> nonBlankVisibleText(element));
            return Optional.of(text);
        } catch (TimeoutException e) {
            log("waitForVisibleText-timeout", element, e);
            if (isGenuinelyNotFound(e)) {
                throw new ElementNotFoundException("Element never present during waitForVisibleText wait", e);
            }
            return Optional.empty();
        } catch (Exception e) {
            log("waitForVisibleText-exception", element, e);
            return Optional.empty();
        }
    }

    /**
     * Runs {@code action}; on failure refreshes the page and tries again, up to
     * {@code max(maxAttempts, 2)} attempts. The action must re-locate its
     * elements, because references found before a refresh are stale afterwards.
     *
     * @throws ElementNotFoundException if the final attempt ended with the element not found
     */
    private static boolean retryWithRefresh(
            final WebDriver driver,
            final int maxAttempts,
            final BooleanSupplier action) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(action, "action must not be null");

        final int attempts = Math.max(maxAttempts, 2);
        ElementNotFoundException lastNotFound = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            lastNotFound = null;
            try {
                if (attempt > 1) {
                    driver.navigate().refresh();
                    if (!waitForPageLoadComplete(driver)) {
                        System.out.printf("[WaitUtil] attempt %d/%d: page not fully loaded, trying anyway%n",
                                attempt, attempts);
                    }
                }
                if (action.getAsBoolean()) {
                    return true;
                }
            } catch (ElementNotFoundException e) {
                lastNotFound = e;
                System.out.printf("[WaitUtil] attempt %d/%d: element not found%n", attempt, attempts);
            } catch (TimeoutException | StaleElementReferenceException | NoSuchElementException e) {
                System.out.printf("[WaitUtil] attempt %d/%d failed: %s%n",
                        attempt, attempts, e.getClass().getSimpleName());
            }
        }

        if (lastNotFound != null) {
            throw lastNotFound;
        }
        return false;
    }

    /** Returns trimmed text if the element is visible and non-blank, else null (keeps WebDriverWait polling). */
    private static String nonBlankVisibleText(WebElement element) {
        if (!element.isDisplayed()) {
            return null;
        }
        String text = element.getText();
        if (text == null) {
            return null;
        }
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

    /** Case-insensitive "contains" on the element's visible text. */
    public static boolean waitForTextToContain(final WebDriver driver, final WebElement element,
                                               final String expectedText) {
        requireNonNull(driver, element);
        Objects.requireNonNull(expectedText, "expectedText must not be null");
        try {
            return Boolean.TRUE.equals(newElementWait(driver).until(d -> textContains(element, expectedText)));
        } catch (TimeoutException e) {
            log("waitForTextToContain-timeout", element, e);
            if (isGenuinelyNotFound(e)) {
                throw new ElementNotFoundException("Element never present during waitForTextToContain wait", e);
            }
            return false;
        } catch (Exception e) {
            log("waitForTextToContain-exception", element, e);
            return false;
        }
    }

    private static boolean textContains(WebElement element, String expectedText) {
        String actual = nonBlankVisibleText(element);
        return actual != null && actual.toLowerCase(Locale.ROOT).contains(expectedText.toLowerCase(Locale.ROOT));
    }

    public static boolean waitForCondition(final WebDriver driver, final String description,
                                           final Function<WebDriver, Boolean> condition) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        try {
            return Boolean.TRUE.equals(newElementWait(driver).until(condition));
        } catch (TimeoutException e) {
            System.out.printf("[WaitUtil] waitForCondition-timeout - %s%n", description);
            return false;
        } catch (Exception e) {
            System.out.printf("[WaitUtil] waitForCondition-exception - %s: %s%n", description, e.getMessage());
            return false;
        }
    }

    /** Case-insensitive "contains" on the page title. */
    public static boolean waitForTitleToContain(final WebDriver driver, final String expectedTitle) {
        Objects.requireNonNull(expectedTitle, "expectedTitle must not be null");
        String needle = expectedTitle.toLowerCase(Locale.ROOT);
        return waitForCondition(driver, "title contains '" + expectedTitle + "'",
                d -> {
                    String title = d.getTitle();
                    return title != null && title.toLowerCase(Locale.ROOT).contains(needle);
                });
    }

    /** Case-sensitive "contains" on the current URL. */
    public static boolean waitForUrlToContain(final WebDriver driver, final String expectedFragment) {
        Objects.requireNonNull(expectedFragment, "expectedFragment must not be null");
        return waitForCondition(driver, "URL contains '" + expectedFragment + "'",
                d -> {
                    String url = d.getCurrentUrl();
                    return url != null && url.contains(expectedFragment);
                });
    }

    /** Waits for {@code document.readyState == "complete"} (does not cover SPA/AJAX rendering). */
    public static boolean waitForPageLoadComplete(final WebDriver driver) {
        return waitForCondition(driver, "page load complete (document.readyState)",
                d -> {
                    if (!(d instanceof JavascriptExecutor js)) {
                        return true; // can't check readyState - don't block forever on a driver that can't tell us
                    }
                    Object state = js.executeScript("return document.readyState;");
                    return "complete".equals(state);
                });
    }

    // =================================================================
    // JavaScript click fallback
    // =================================================================

    private static boolean jsClick(final WebDriver driver, final WebElement element) {
        if (!(driver instanceof JavascriptExecutor javascriptExecutor)) {
            log("jsClick-unsupported-driver", element, null);
            return false;
        }

        if (tryDirectJsClick(javascriptExecutor, element)) {
            return true;
        }

        return tryOpacityForcedClick(javascriptExecutor, element);
    }

    private static boolean tryDirectJsClick(JavascriptExecutor javascriptExecutor, WebElement element) {
        try {
            // Explicit "return true" - element.click() itself yields no value,
            // so without this the script always evaluates to null/false.
            Object result = javascriptExecutor.executeScript("arguments[0].click(); return true;", element);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log("jsClick-direct-failed", element, e);
            return false;
        }
    }

    /**
     * Last resort for elements hidden via opacity: temporarily forces the inline
     * opacity to 1, clicks natively, then restores the ORIGINAL INLINE value
     * (not the computed one, which would leave a stray inline style behind).
     */
    private static boolean tryOpacityForcedClick(JavascriptExecutor javascriptExecutor, WebElement element) {
        String originalInlineOpacity = null; // null => we never changed anything
        try {
            Object previous = javascriptExecutor.executeScript(
                    "var o = arguments[0].style.opacity; arguments[0].style.opacity = '1'; return o;",
                    element);
            originalInlineOpacity = previous == null ? "" : previous.toString();
            sleepBriefly(50);
            element.click();
            return true;
        } catch (Exception e) {
            log("jsClick-opacity-fallback-failed", element, e);
            return false;
        } finally {
            restoreOpacity(javascriptExecutor, element, originalInlineOpacity);
        }
    }

    private static void restoreOpacity(JavascriptExecutor javascriptExecutor, WebElement element,
                                       String originalInlineOpacity) {
        if (originalInlineOpacity == null) {
            return; // nothing was changed
        }
        try {
            // Empty string removes the inline declaration entirely.
            javascriptExecutor.executeScript(
                    "arguments[0].style.opacity = arguments[1];", element, originalInlineOpacity);
        } catch (Exception e) {
            log("jsClick-opacity-restore-failed", element, e);
        }
    }

    private static void sleepBriefly(long millis) throws InterruptedException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // preserve interrupt status
            throw e;
        }
    }

    // =================================================================
    // Internal helpers
    // =================================================================

    private static void requireNonNull(WebDriver driver, WebElement element) {
        Objects.requireNonNull(driver, "driver must not be null");
        Objects.requireNonNull(element, "element must not be null");
    }

    /**
     * Best-effort diagnostic log. Deliberately swallows any failure raised
     * while *reading* element state (e.g. a stale element mid-log) so that
     * logging can never itself throw and mask the real failure being logged.
     */
    private static void log(String msg, WebElement element, Exception e) {
        try {
            System.out.printf(
                    "[WaitUtil] %s - Displayed: %s, Enabled: %s, Location: %s, TagName: %s, Text: %s, Opacity: %s, Exception: %s%n",
                    msg,
                    safely(element::isDisplayed),
                    safely(element::isEnabled),
                    safely(element::getLocation),
                    safely(element::getTagName),
                    abbreviate(safely(element::getText)),
                    safely(() -> element.getCssValue("opacity")),
                    e == null ? "none" : e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception loggingFailure) {
            System.out.printf("[WaitUtil] %s - (unable to capture element diagnostics: %s)%n",
                    msg, loggingFailure.getMessage());
        }
    }

    private static Object safely(Callable<?> supplier) {
        try {
            return supplier.call();
        } catch (Exception e) {
            return "n/a";
        }
    }

    private static String abbreviate(Object value) {
        String s = String.valueOf(value);
        return s.length() <= MAX_LOGGED_TEXT_LENGTH ? s : s.substring(0, MAX_LOGGED_TEXT_LENGTH) + "...";
    }
}
