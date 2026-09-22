

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
import java.util.function.Function;

/**
 * Click and text-read helpers for Selenium {@link WebElement}s that are
 * resilient to staleness, timing, and "invisible until interacted with"
 * (opacity 0) elements. All public methods are fail-safe: they never throw
 * for ordinary Selenium/timing failures - they return {@code false} /
 * {@link Optional#empty()} and log diagnostics instead, so callers can rely
 * on the return value without wrapping every call in try/catch.
 *
 * <p>{@link IllegalArgumentException}/{@link NullPointerException} are
 * thrown for genuine programming errors (null driver/element), since those
 * indicate a bug in the calling test code rather than a flaky browser
 * condition. The element-scoped wait methods additionally throw
 * {@link ElementNotFoundException} (unchecked) when the element could never
 * be located at all for the entire wait - as opposed to being found but
 * never reaching the desired state - since that combination usually means a
 * wrong locator or missing page section rather than ordinary timing
 * flakiness, and returning {@code false} for it tends to hide real bugs
 * behind an innocuous-looking assertion failure.
 *
 * <h2>Method index</h2>
 * <ul>
 *   <li>{@link #waitAndClick} - <b>default choice for clicking anything.</b>
 *       Works for buttons, inputs, checkboxes/radios, anchors, and
 *       non-form clickable elements (custom {@code role="button"}
 *       components).</li>
 *   <li>{@link #safeClick} - plain native click, waits for enabled only;
 *       used internally by {@link #waitAndClick} as a stale-element retry.</li>
 *   <li>{@link #waitForVisibleText} - wait for an element to show text, read it.</li>
 *   <li>{@link #waitForTextToContain} - wait for/assert an element's text.</li>
 *   <li>{@link #waitForTitleToContain} - wait for/assert the page title.</li>
 *   <li>{@link #waitForUrlToContain} - wait for/assert the URL.</li>
 *   <li>{@link #waitForPageLoadComplete} - wait for navigation to finish loading.</li>
 *   <li>{@link #waitForCondition} - generic driver-level wait for anything
 *       not covered above (window count, alert present, etc.).</li>
 * </ul>
 *
 * <p>See the team guide (WaitUtil-AwaitUtil-Guide.md) for when to use this
 * class vs {@link AwaitUtil}.
 */
public final class WaitUtil {

    private static final Duration WAIT_TIME = Duration.ofSeconds(30);
    // Floored at 200ms so a future change to WAIT_TIME can't silently divide
    // down to a zero/near-zero polling interval (which some WebDriverWait
    // versions reject outright, and which would otherwise hot-loop).
    private static final Duration POLL_INTERVAL =
            Duration.ofMillis(Math.max(WAIT_TIME.toMillis() / 30, 200)); // ~1s

    private WaitUtil() {
        // Utility class - no instances.
    }

    /**
     * Thrown by the element-scoped wait methods ({@link #safeClick},
     * {@link #waitAndClick}, {@link #waitForVisibleText},
     * {@link #waitForTextToContain}) when the wait timed out because the
     * element could never be located at all (a {@link NoSuchElementException}
     * recurred for the whole wait), as opposed to being found but never
     * reaching the desired state (enabled, visible, non-stale, etc.). This
     * is intentionally unchecked: it signals a likely genuine bug (wrong
     * locator, element removed from the page, wrong page entirely) rather
     * than the ordinary timing flakiness the rest of this class absorbs, so
     * it is deliberately NOT swallowed into a {@code false} return.
     */
    public static final class ElementNotFoundException extends RuntimeException {
        public ElementNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * True if {@code e}, or any exception in its cause chain, is a
     * {@link NoSuchElementException}. {@link WebDriverWait#until} wraps
     * whatever kept recurring during polling as the {@link TimeoutException}
     * cause, so this is how a genuinely-missing element is distinguished
     * from every other timeout reason (staleness, not-yet-enabled,
     * not-yet-visible) that this class treats as ordinary flakiness.
     */
    private static boolean isGenuinelyNotFound(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NoSuchElementException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the standard element-scoped wait: {@link #WAIT_TIME} budget,
     * polling every {@link #POLL_INTERVAL}, transparently retrying through
     * {@link NoSuchElementException} and {@link StaleElementReferenceException}
     * rather than failing on the first flaky poll. Shared by every wait
     * method below so the polling policy lives in exactly one place.
     */
    private static WebDriverWait newElementWait(WebDriver driver) {
        return new WebDriverWait(driver, WAIT_TIME)
                .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                .pollingEvery(POLL_INTERVAL);
    }

    // =================================================================
    // Clicking
    // =================================================================

    /**
     * Waits (up to {@link #WAIT_TIME}) for the element to be enabled, then
     * performs a native Selenium click. Retries transparently through
     * {@link NoSuchElementException} / {@link StaleElementReferenceException}
     * while polling.
     *
     * @return true if the click was performed; false if the element never
     *         became clickable in time, or any error occurred
     */
    public static boolean safeClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        try {
            return Boolean.TRUE.equals(newElementWait(driver).until(d -> attemptClick(element)));
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

    private static Boolean attemptClick(WebElement element) {
        if (!element.isEnabled()) {
            return false; // tells WebDriverWait to keep polling
        }
        element.click();
        return true;
    }

    /**
     * Waits for the element to be genuinely interactable, then clicks it.
     * This is the default, do-everything click method - it always attempts
     * a real native Selenium click first, on every browser, and only
     * escalates to a JavaScript-forced click (which bypasses occlusion,
     * opacity, and CSS {@code pointer-events}) when Selenium reports the
     * click was genuinely blocked ({@link ElementClickInterceptedException}
     * / {@link ElementNotInteractableException}) or the element handling
     * relies on a known opacity-hidden pattern. Logs elapsed time
     * regardless of outcome.
     *
     * <p><b>Works uniformly across element types</b> - buttons, inputs,
     * checkboxes/radios, anchors, and non-form clickable elements (a
     * {@code <div>}/{@code <span>}/{@code role="button"} element driven by
     * a JS click handler, as most component-library buttons are) - via one
     * readiness check applied to every element, regardless of tag:
     * <ul>
     *   <li>{@code isEnabled()}. For native form controls (button, input,
     *       select, textarea, fieldset, optgroup) this reflects the HTML
     *       {@code disabled} attribute per the WebDriver spec. Deliberately
     *       does NOT also require {@code isDisplayed()} - custom
     *       checkbox/radio styling (including GOV.UK Design System)
     *       commonly keeps the real {@code <input>} visually hidden while a
     *       styled label represents it, and that input is still fully
     *       clickable.</li>
     *   <li>{@code aria-disabled="true"} or a computed {@code pointer-events:
     *       none}. Per the WebDriver spec, {@code isEnabled()} is only
     *       {@code false}-able for the native form controls listed above -
     *       for every other element (anchors, and especially the
     *       {@code div}/{@code span}/{@code role="button"} elements common
     *       in modern component libraries) it is unconditionally
     *       {@code true} no matter how the element visually or functionally
     *       signals "disabled." Checking these two universally, rather than
     *       only for anchors, is what makes this readiness check actually
     *       tag-agnostic; it's a no-op for a genuinely-enabled native
     *       control, since those rarely carry a contradicting
     *       {@code aria-disabled}.</li>
     * </ul>
     *
     * <p><b>Click strategy, in order:</b>
     * <ol>
     *   <li>Real native {@code element.click()} - respects genuine
     *       interactability (occlusion, CSS), so a blocked click fails
     *       here rather than silently "succeeding."</li>
     *   <li>If that specific click attempt goes stale
     *       ({@link StaleElementReferenceException}), retry once via
     *       {@link #safeClick} (which has its own polling loop) rather
     *       than failing outright on a one-off timing race.</li>
     *   <li>If Selenium reports the click was genuinely intercepted/blocked,
     *       fall back to {@link #jsClick} - a JS click, escalating to a
     *       temporary opacity override for elements deliberately hidden
     *       via {@code opacity:0} tricks.</li>
     * </ol>
     *
     * @return true if the click was performed via any strategy above;
     *         false if the element never became ready, or every strategy failed
     */
    public static boolean waitAndClick(final WebDriver driver, final WebElement element) {
        requireNonNull(driver, element);
        long startNanos = System.nanoTime();
        boolean clicked;
        try {
            clicked = performClick(driver, element);
        } catch (ElementNotFoundException e) {
            // Genuinely missing element: don't swallow, let the caller see it.
            throw e;
        } catch (Exception e) {
            log("waitAndClick-exception", element, e);
            clicked = false;
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            System.out.printf("[WaitUtil] waitAndClick took %d ms%n", elapsedMs);
        }
        return clicked;
    }

    private static boolean performClick(final WebDriver driver, final WebElement element) {
        try {
            newElementWait(driver).until(d -> isGenuinelyReady(element));
        } catch (TimeoutException e) {
            log("waitAndClick-not-ready-timeout", element, e);
            if (isGenuinelyNotFound(e)) {
                throw new ElementNotFoundException("Element never present during waitAndClick wait", e);
            }
            return false;
        }

        try {
            element.click();
            return true;
        } catch (StaleElementReferenceException e) {
            log("waitAndClick-stale-retrying-via-safeClick", element, e);
            return safeClick(driver, element);
        } catch (ElementClickInterceptedException | ElementNotInteractableException e) {
            log("waitAndClick-intercepted-falling-back-to-js", element, e);
            return jsClick(driver, element);
        }
    }

    /**
     * Readiness check backing {@link #waitAndClick}, applied uniformly to
     * every element regardless of tag: {@code isEnabled()} plus an
     * {@code aria-disabled}/{@code pointer-events} check for the
     * "disabled" signals {@code isEnabled()} can't see.
     *
     * <p>The {@code aria-disabled}/{@code pointer-events} check is NOT
     * restricted to anchors. Per the WebDriver spec, {@code isEnabled()}
     * only reflects the HTML {@code disabled} attribute for native form
     * controls (button, input, select, textarea, fieldset, optgroup); for
     * everything else - anchors, and just as importantly the
     * {@code div}/{@code span}/{@code role="button"} elements most
     * component-library buttons actually render as - it is unconditionally
     * {@code true}. Checking it everywhere costs nothing for a genuinely
     * enabled native control (which won't carry a contradicting
     * {@code aria-disabled}) and is the only way to catch a disabled
     * custom element at all.
     *
     * <p><b>Deliberately does NOT require {@code isDisplayed()}.</b> Many
     * accessible custom checkbox/radio implementations (including the GOV.UK
     * Design System's) keep the real {@code <input>} visually hidden via CSS
     * (e.g. {@code opacity: 0} or off-screen positioning) while a styled
     * {@code <label>}/pseudo-element is what the user actually sees and
     * clicks. Requiring {@code isDisplayed()} here would wait forever on
     * those inputs, since Selenium correctly reports them as not displayed
     * even though they're fully clickable. Genuine invisibility/occlusion is
     * instead caught by the real click attempt itself
     * ({@link ElementNotInteractableException} / {@link ElementClickInterceptedException}
     * in {@link #performClick}), which correctly triggers the JS fallback.
     */
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

    // =================================================================
    // Reading element text
    // =================================================================

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
            String text = newElementWait(driver).until(d -> nonBlankVisibleText(element));
            return Optional.ofNullable(text);
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

    // =================================================================
    // Driver-level conditions (not scoped to a single WebElement)
    // =================================================================

    /**
     * Waits (up to {@link #WAIT_TIME}) for an arbitrary DRIVER-level
     * condition to become true - i.e. anything that isn't scoped to a
     * single {@link WebElement}, such as page title, URL, window/tab count,
     * or alert presence. {@code safeClick}/{@code waitForVisibleText} etc.
     * all require a {@link WebElement}; use this method instead when your
     * condition only needs the {@link WebDriver} itself.
     *
     * @param description short label for logging/failure context, e.g. "title contains 'Dashboard'"
     * @param condition    predicate evaluated against the driver on each poll;
     *                     should be side-effect-free and cheap to call repeatedly
     * @return true if the condition became true within the wait; false on
     *         timeout or any error - never throws
     */
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

    /**
     * Waits (up to {@link #WAIT_TIME}) for the page title to contain
     * {@code expectedTitle} (case-insensitive). Common after a navigation
     * or redirect where the page load itself is async.
     *
     * <pre>{@code
     * @Then("the dashboard page eventually loads")
     * public void dashboardPageEventuallyLoads() {
     *     assertThat(WaitUtil.waitForTitleToContain(driver, "Dashboard")).isTrue();
     * }
     * }</pre>
     *
     * @return true if the title matched within the wait; false on timeout
     *         or any error - never throws
     */
    public static boolean waitForTitleToContain(final WebDriver driver, final String expectedTitle) {
        Objects.requireNonNull(expectedTitle, "expectedTitle must not be null");
        String needle = expectedTitle.toLowerCase(Locale.ROOT);
        return waitForCondition(driver, "title contains '" + expectedTitle + "'",
                d -> {
                    String title = d.getTitle();
                    return title != null && title.toLowerCase(Locale.ROOT).contains(needle);
                });
    }

    /**
     * Waits (up to {@link #WAIT_TIME}) for the current URL to contain
     * {@code expectedFragment} (case-sensitive - URLs are case-sensitive by
     * spec). Common after a client-side route change or redirect chain.
     *
     * @return true if the URL matched within the wait; false on timeout or
     *         any error - never throws
     */
    public static boolean waitForUrlToContain(final WebDriver driver, final String expectedFragment) {
        Objects.requireNonNull(expectedFragment, "expectedFragment must not be null");
        return waitForCondition(driver, "URL contains '" + expectedFragment + "'",
                d -> {
                    String url = d.getCurrentUrl();
                    return url != null && url.contains(expectedFragment);
                });
    }

    /**
     * Waits (up to {@link #WAIT_TIME}) for the browser to report the page
     * as fully loaded ({@code document.readyState === "complete"}).
     *
     * <p>Use this instead of a fixed {@code sleep(...)} after
     * {@code driver.navigate()}/{@code driver.get()} - a sleep either wastes
     * time on a fast page or isn't long enough on a slow one; this returns
     * as soon as the page is actually ready, and still gives it up to
     * {@link #WAIT_TIME} on a slow one.
     *
     * <pre>{@code
     * // Before (flaky - fixed guess at how long navigation takes):
     * Navigation.navigateToURL(startPageURL);
     * sleep(500);
     * BrowserDriver.getCurrentDriver().manage().deleteAllCookies();
     *
     * // After (polls the actual condition):
     * Navigation.navigateToURL(startPageURL);
     * WaitUtil.waitForPageLoadComplete(driver);
     * BrowserDriver.getCurrentDriver().manage().deleteAllCookies();
     * }</pre>
     *
     * @return true if the page reported "complete" within the wait; false
     *         on timeout or any error (e.g. driver doesn't support JS) -
     *         never throws
     */
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
    // Internal: JavaScript click machinery (used by waitAndClick's fallback)
    // =================================================================

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

    // =================================================================
    // Internal: shared helpers (null-checks, logging)
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
