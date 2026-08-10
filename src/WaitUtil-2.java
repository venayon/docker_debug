
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Click and text-read helpers for Selenium {@link WebElement}s that are
 * resilient to staleness, timing, and "invisible until interacted with"
 * (opacity 0) elements. All public methods are fail-safe: they never throw
 * for ordinary Selenium/timing failures - they return {@code false} /
 * {@link Optional#empty()} and log diagnostics instead, so callers can rely
 * on the return value without wrapping every call in try/catch.
 *
 * <p>Only {@link IllegalArgumentException}/{@link NullPointerException} are
 * thrown, and only for genuine programming errors (null driver/element),
 * since those indicate a bug in the calling test code rather than a
 * flaky browser condition.
 */
public final class WaitUtil {

    private static final java.time.Duration WAIT_TIME = java.time.Duration.ofSeconds(30);
    private static final java.time.Duration POLL_INTERVAL = WAIT_TIME.dividedBy(30); // ~1s

    private WaitUtil() {
    }

    /**
     * Waits (up to {@link #WAIT_TIME}) for the element to be enabled, then
     * performs a native Selenium click. Retries transparently through
     * while polling.
     * @return true if the click was performed; 
     *         false if the element never became clickable in time, or any error occurred
     */
    public static boolean safeClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        try {
            return Boolean.TRUE.equals(new WebDriverWait(driver, WAIT_TIME)
                    .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                    .pollingEvery(POLL_INTERVAL)
                    .until(d -> attemptClick(element)));
        } catch (TimeoutException e) {
            log("safeClick-timeout", element, e);
            return false;
        } catch (Exception e) {
            log("safeClick-exception", element, e);
            return false;
        }
    }

    private static Boolean attemptClick(WebElement element) {
        if (!element.isEnabled()) {
            return false; // tells WebDriverWait to keep polling
        }
        element.click();
        return true;
    }

    /**
     * Waits for the element to become clickable and clicks it, choosing a
     * browser-appropriate strategy: a JavaScript click for Chrome (handles
     * elements with opacity-based visibility tricks), a native Selenium
     * click otherwise. Logs elapsed time regardless of outcome.
     *
     * @return true if the click succeeded via either strategy; false otherwise
     */
    public static boolean waitAndClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        long startNanos = System.nanoTime();
        boolean clicked;
        try {
            clicked = resolveBrowserName(driver).contains("chrome")
                    ? chromeClick(driver, element)
                    : safeClick(driver, element);
        } catch (Exception e) {
            log("waitAndClick-exception", element, e);
            clicked = false;
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            System.out.printf("[WaitUtil] waitAndClick took %d ms%n", elapsedMs);
        }
        return clicked;
    }

    /**
     * Waits (up to {@link #WAIT_TIME}) for the element to be visible, then
     * returns its trimmed visible text. Use this for read-only elements
     * (banners, toasts, labels) that aren't meant to be clicked.
     *
     * @return the trimmed text once the element is visible, or
     *         {@link Optional#empty()} if it never became visible, its text
     *         stayed blank for the full wait, or any error occurred
     */
    public static Optional<String> waitForVisibleText(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        try {
            String text = new WebDriverWait(driver, WAIT_TIME)
                    .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                    .pollingEvery(POLL_INTERVAL)
                    .until(d -> nonBlankVisibleText(element));
            return Optional.ofNullable(text);
        } catch (TimeoutException e) {
            log("waitForVisibleText-timeout", element, e);
            return Optional.empty();
        } catch (Exception e) {
            log("waitForVisibleText-exception", element, e);
            return Optional.empty();
        }
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

    /**
     * Waits (up to {@link #WAIT_TIME}) for the element's visible text to
     * contain {@code expectedText} (case-insensitive). Useful for asserting
     * a banner/toast eventually shows the message you expect, without caring
     * about the element's exact wording or surrounding whitespace.
     *
     * @return true if the expected text appeared within the wait; false if
     *         it never appeared, or any error occurred
     */
    public static boolean waitForTextToContain(final WebDriver driver, final WebElement element,
                                                final String expectedText) {
        requireNonNull(driver, element);
        Objects.requireNonNull(expectedText, "expectedText must not be null");
        try {
            return Boolean.TRUE.equals(new WebDriverWait(driver, WAIT_TIME)
                    .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                    .pollingEvery(POLL_INTERVAL)
                    .until(d -> textContains(element, expectedText)));
        } catch (TimeoutException e) {
            log("waitForTextToContain-timeout", element, e);
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

    /**
     * Best-effort browser name lookup. Falls back to an empty string (which
     * routes to {@link #safeClick}) rather than throwing, so an unusual or
     * wrapped {@link WebDriver} implementation never breaks the click.
     * Optional<String> banner = WaitUtil.waitForVisibleText(driver, bannerElement);
     * banner.ifPresentOrElse(
     *     text -> System.out.println("Banner said: " + text),
     *     () -> System.out.println("Banner never appeared")
     * );
     *
     * boolean shown = WaitUtil.waitForTextToContain(driver, bannerElement, "payment successful");
     */
    private static String resolveBrowserName(WebDriver driver) {
        try {
            if (driver instanceof HasCapabilities hasCapabilities) {
                String name = hasCapabilities.getCapabilities().getBrowserName();
                return name == null ? "" : name.toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            System.out.printf("[WaitUtil] WARN unable to resolve browser name, defaulting to safeClick: %s%n",
                    e.getMessage());
        }
        return "";
    }

    /**
     * Waits for the element to report enabled + a resolvable tag name, then
     * delegates to {@link #jsClick}.
     */
    private static boolean chromeClick(final WebDriver driver, final WebElement element) {
        try {
            new WebDriverWait(driver, WAIT_TIME)
                    .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                    .pollingEvery(POLL_INTERVAL)
                    .until(d -> isReadyForClick(element));
            log("chromeClick-ready", element, null);
            return jsClick(driver, element);
        } catch (TimeoutException e) {
            log("chromeClick-timeout", element, e);
            return false;
        } catch (Exception e) {
            log("chromeClick-exception", element, e);
            return false;
        }
    }

    private static boolean isReadyForClick(WebElement element) {
        try {
            return element.isEnabled() && element.getTagName() != null;
        } catch (StaleElementReferenceException | NoSuchElementException e) {
            return false; // let WebDriverWait retry rather than propagate
        }
    }

    /**
     * Clicks via JavaScript. Tries a plain {@code element.click()} first;
     * if that doesn't report success, retries by temporarily forcing
     * {@code opacity:1} (common cause of Selenium treating a real,
     * interactable element as "not visible"), then ALWAYS restores the
     * original opacity value, even if the click itself fails.
     */
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

    private static boolean tryOpacityForcedClick(JavascriptExecutor javascriptExecutor, WebElement element) {
        String originalOpacity = null;
        try {
            originalOpacity = element.getCssValue("opacity");
            javascriptExecutor.executeScript("arguments[0].style.opacity='1';", element);
            sleepBriefly(50);
            element.click();
            return true;
        } catch (Exception e) {
            log("jsClick-opacity-fallback-failed", element, e);
            return false;
        } finally {
            restoreOpacity(javascriptExecutor, element, originalOpacity);
        }
    }

    private static void restoreOpacity(JavascriptExecutor javascriptExecutor, WebElement element, String originalOpacity) {
        if (originalOpacity == null || originalOpacity.isBlank()) {
            return; // nothing to restore
        }
        try {
            javascriptExecutor.executeScript("arguments[0].style.opacity='" + originalOpacity + "';", element);
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
                    safely(element::getText),
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
}
