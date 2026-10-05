# WaitUtil Guide

This guide describes the supplied [`WaitUtil.java`](sources/WaitUtil.java): helpers for Selenium clicks, text, browser conditions and page load. For backend, API, database or file polling, use [`AwaitUtil`](sources/AwaitUtil-Guide.md).

## 1. Defaults and failure handling

Waits use a **30-second timeout** and **500 ms polling interval**. These are private constants; there is no public overload for custom durations. An individual driver call can take additional time, so these settings are not a strict wall-clock limit for an entire operation.

Most helpers report failure through their return value. **Check the result when success is required by the test.**

| Outcome | Result |
|---|---|
| Click or condition succeeds | `true` |
| Visible, non-blank text is found | `Optional<String>` containing trimmed text |
| Wait times out or a caught exception occurs | Logged; returns `false` or `Optional.empty()` |
| An element helper times out with `NoSuchElementException` in the timeout's cause chain | Throws `WaitUtil.ElementNotFoundException` |
| A required argument is null | Throws `NullPointerException` before waiting |

The element helpers are `safeClick`, `waitAndClick`, `waitForVisibleText` and `waitForTextToContain`. The missing-element check inspects the timeout's cause chain; it does **not** track whether an element was ever present. In particular, click readiness checks swallow missing/stale-element exceptions and return `false`, so a missing element does not reliably produce `ElementNotFoundException`.

`waitForCondition` and the title, URL and page-load helpers catch exceptions during polling and return `false`; they do not convert missing elements into `ElementNotFoundException`. `retryWithRefresh` has separate exception handling, described below.

Examples below use JUnit Jupiter assertions:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

assertTrue(WaitUtil.safeClick(driver, saveButton), "Save was not clicked");

// An optional condition needs no timeout catch.
boolean onPromoPage = WaitUtil.waitForUrlToContain(driver, "/promo");
```

## 2. Quick reference

| Method | Result | Purpose |
|---|---|---|
| `safeClick(driver, element)` | `boolean` | Native click with retries; no JavaScript fallback |
| `waitAndClick(driver, element)` | `boolean` | Native click with JavaScript fallback when intercepted/not interactable |
| `waitForVisibleText(driver, element)` | `Optional<String>` | Read trimmed, non-blank text from a displayed element |
| `waitForTextToContain(driver, element, text)` | `boolean` | Case-insensitive substring match on visible, non-blank text |
| `waitForCondition(driver, description, condition)` | `boolean` | Poll a `Function<WebDriver, Boolean>` until it returns `true` |
| `waitForTitleToContain(driver, text)` | `boolean` | Case-insensitive title substring match |
| `waitForUrlToContain(driver, fragment)` | `boolean` | Case-sensitive URL substring match |
| `waitForPageLoadComplete(driver)` | `boolean` | Check `document.readyState` |
| `retryWithRefresh(driver, maxAttempts, action)` | `boolean` | Retry a `BooleanSupplier`, refreshing before subsequent attempts |

## 3. Clicking

Both click helpers first require the element to be enabled, `aria-disabled` to be other than `"true"` (case-insensitive), and CSS `pointer-events` to be other than `"none"` (case-insensitive). This readiness check does **not** check `isDisplayed()`.

### `safeClick(driver, element)`

Use this for native Selenium clicks. It polls readiness and retries when a click throws `ElementClickInterceptedException` or `ElementNotInteractableException`, for example while an overlay remains on screen. Missing and stale element exceptions are also ignored by the underlying wait.

```java
WebElement save = driver.findElement(By.id("save"));
assertTrue(WaitUtil.safeClick(driver, save), "Save button was not clicked");
```

### `waitAndClick(driver, element)`

After readiness passes, this tries a native click. Only interception or non-interactability triggers the fallback sequence:

1. Execute `arguments[0].click(); return true;` through JavaScript.
2. If that fails, temporarily set inline opacity to `1`, pause for 50 ms, and try a native click.
3. Attempt to restore the original inline opacity in a `finally` block. Restoration errors are logged.

If the driver cannot execute JavaScript, the fallback returns `false` and the outer wait can continue polling. The method logs elapsed time on completion after argument validation.

```java
assertTrue(WaitUtil.waitAndClick(driver, customMenuItem),
        "Custom menu item was not clicked");
```

A JavaScript click can bypass an obstruction that would block a user. Prefer `safeClick` when testing native interaction. Neither helper verifies the resulting navigation, saved data or selected state; wait for and assert the expected outcome separately.

### Checkboxes and radio buttons

The helpers stop after a successful click; they do not retry because selected state did not change. For a checkbox that must be selected:

```java
WebElement terms = driver.findElement(By.id("terms"));
if (!terms.isSelected()) {
    assertTrue(WaitUtil.safeClick(driver, terms), "Terms checkbox was not clicked");
}
assertTrue(terms.isSelected(), "Terms checkbox should be selected");
```

## 4. Reading and checking text

`waitForVisibleText` requires `isDisplayed()` and non-empty text after `String.trim()`. It returns an empty optional on an ordinary timeout or caught error; handle that explicitly:

```java
String message = WaitUtil.waitForVisibleText(driver, toast)
        .orElseThrow(() -> new AssertionError("Toast text did not appear"));
assertEquals("Saved successfully", message);
```

`waitForTextToContain` uses the same visibility and non-blank checks, then compares using `Locale.ROOT` lowercase conversion. The expected text is not trimmed. An empty expected string matches any non-blank visible text.

```java
assertTrue(WaitUtil.waitForTextToContain(driver, status, "complete"),
        "Status did not contain 'complete'");
```

## 5. Waiting for browser state

Title matching is case-insensitive; URL matching is case-sensitive. Both perform substring checks, so choose fragments specific enough for the assertion.

```java
assertTrue(WaitUtil.safeClick(driver, loginButton), "Login was not clicked");
assertTrue(WaitUtil.waitForUrlToContain(driver, "/dashboard"),
        "Dashboard URL was not reached");
assertTrue(WaitUtil.waitForTitleToContain(driver, "Dashboard"),
        "Dashboard title did not appear");
```

`waitForPageLoadComplete` checks whether `document.readyState` equals `"complete"`. It returns `true` immediately if the driver does not implement `JavascriptExecutor`, because it cannot perform the check. It does not guarantee that SPA updates, AJAX requests or application rendering have completed.

```java
assertTrue(WaitUtil.waitForPageLoadComplete(driver), "Page did not finish loading");
assertTrue(WaitUtil.waitForCondition(driver, "results table has 5+ rows",
        d -> d.findElements(By.cssSelector("table.results tbody tr")).size() >= 5),
        "Expected at least five result rows");
```

For `waitForCondition`, `false` or `null` keeps polling. Missing/stale-element exceptions are ignored during the wait; other exceptions are caught by the helper and cause a logged `false` result. The description appears in timeout/error logs.

## 6. Retrying with a page refresh

`retryWithRefresh(driver, maxAttempts, action)` makes up to `Math.max(maxAttempts, 2)` attempts, stopping as soon as the action returns `true`. Even zero, negative or one requested attempt allows two attempts.

- The first attempt runs without refreshing.
- Each subsequent attempt refreshes the page and calls `waitForPageLoadComplete`. If that returns `false`, a message is logged and the action still runs.
- An action returning `false` triggers another attempt when one remains.
- `ElementNotFoundException`, Selenium `TimeoutException`, `StaleElementReferenceException` and `NoSuchElementException` are caught during an attempt.
- If the final attempt throws `ElementNotFoundException`, that exception is rethrown. Earlier instances are discarded. Other handled failures ultimately return `false`.
- Other exceptions propagate. For example, an assertion failure inside the action does not trigger a retry.

Re-locate elements inside the action because refresh invalidates existing references. Return a boolean and assert outside the retry:

```java
assertTrue(WaitUtil.retryWithRefresh(driver, 3, () -> {
    WebElement status = driver.findElement(By.id("job-status"));
    return WaitUtil.waitForTextToContain(driver, status, "complete");
}), "Job did not complete after refresh retries");
```

This method has no single overall timeout: each attempt can include navigation, a page-load wait and any waits inside the action. Choose actions that are safe to repeat; repeated submissions or toggles can change state more than once.

## 7. Element lookup and stale references

These helpers accept `WebElement`, not a locator. With an ordinary element, `driver.findElement(...)` runs **before** the helper is called. If lookup fails, that exception occurs outside the helper's handling. Retrying the same stale element cannot re-locate it.

For an element that appears later or is replaced during rendering, re-locate inside a custom condition:

```java
assertTrue(WaitUtil.waitForCondition(driver, "status shows complete", d -> {
    WebElement current = d.findElement(By.id("job-status"));
    return current.isDisplayed()
            && current.getText().trim().equalsIgnoreCase("complete");
}), "Status did not become complete");
```

## 8. Diagnostics and common mistakes

| Mistake | Correct approach |
|---|---|
| Ignoring a boolean result | Assert it or explicitly handle `false` |
| Calling `.get()` on the text optional without checking | Use `orElseThrow` or handle an empty optional |
| Catching `TimeoutException` around ordinary wait helpers | Check their return values; they catch polling timeouts internally |
| Assuming every missing element throws `ElementNotFoundException` | Account for lookup timing and the timeout cause-chain check |
| Reusing a stale element | Re-locate inside a condition or retry action |
| Calling a four-argument `waitAndClick` | Use the existing two-argument method; no duration overload exists |
| Assuming page load means application readiness | Wait for a specific visible application state |
| Using fixed sleeps for readiness | Use a condition-based wait |

Diagnostic output starts with `[WaitUtil]`. Element logs attempt to include displayed/enabled state, location, tag, text, opacity and exception details. Unreadable fields become `n/a`; text longer than 80 characters is abbreviated with `...`. `waitAndClick` also logs elapsed milliseconds. Use these logs alongside the test's assertion message to distinguish a failed click, unmet condition or browser error.
