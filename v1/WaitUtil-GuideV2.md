# WaitUtil Quick Reference

Use [`WaitUtil`](sources/WaitUtil.java) for Selenium/browser waits; use [`AwaitUtil`](sources/AwaitUtil-Guide.md) for backend polling.

**Defaults:** 30-second timeout, 500 ms polling. No custom-duration overloads.

## Methods

All methods return `boolean` except `waitForVisibleText`, which returns `Optional<String>`.

| Method | Behaviour |
|---|---|
| `safeClick(driver, element)` | Native click; retries intercepted/not-interactable clicks. Prefer for normal UI interaction. |
| `waitAndClick(driver, element)` | Native click, then JavaScript fallback if intercepted/not interactable. If JS click fails, tries native click with temporary opacity `1`. |
| `waitForVisibleText(driver, element)` | Waits for displayed, non-blank text; returns trimmed text. |
| `waitForTextToContain(driver, element, text)` | Case-insensitive substring match on visible, non-blank text. |
| `waitForCondition(driver, description, condition)` | Polls `Function<WebDriver, Boolean>` until `true`; `false`/`null` keeps waiting. |
| `waitForTitleToContain(driver, text)` | Case-insensitive title substring match. |
| `waitForUrlToContain(driver, fragment)` | Case-sensitive URL substring match. |
| `waitForPageLoadComplete(driver)` | Checks `document.readyState == "complete"`; returns `true` if JavaScript is unsupported. Does not wait for SPA/AJAX rendering. |
| `retryWithRefresh(driver, maxAttempts, action)` | Retries a `BooleanSupplier`; refreshes before each subsequent attempt. |

## Usage

Examples use JUnit Jupiter assertions. **Assert return values when success is required.**

```java
assertTrue(WaitUtil.safeClick(driver, saveButton), "Save click failed");
assertTrue(WaitUtil.waitForUrlToContain(driver, "/dashboard"), "Redirect failed");

String text = WaitUtil.waitForVisibleText(driver, toast)
        .orElseThrow(() -> new AssertionError("Toast text missing"));
assertEquals("Saved successfully", text);

// Re-locate on each poll when the DOM can replace the element.
assertTrue(WaitUtil.waitForCondition(driver, "status complete", d -> {
    WebElement status = d.findElement(By.id("status"));
    return status.isDisplayed()
            && status.getText().trim().equalsIgnoreCase("complete");
}), "Status did not complete");

// Re-locate after refresh; return a boolean and assert outside the action.
assertTrue(WaitUtil.retryWithRefresh(driver, 3, () ->
        WaitUtil.waitForTextToContain(driver,
                driver.findElement(By.id("status")), "complete")),
        "Status did not complete after refresh retries");
```

## Failure handling and pitfalls

- Ordinary waits log timeouts/caught errors and return `false` or `Optional.empty()`. Null required arguments throw `NullPointerException`.
- Element waits throw `ElementNotFoundException` only when a timeout's cause chain contains `NoSuchElementException`. This does not prove the element was never present; click readiness checks swallow missing/stale errors, so missing elements can simply yield `false`.
- `waitForCondition` and title/URL/page-load helpers return `false` for polling exceptions; they do not convert missing elements into `ElementNotFoundException`.
- `findElement(...)` before a helper call can throw outside its handling. A stale `WebElement` cannot recover by waiting: re-locate it.
- Click readiness checks enabled state, `aria-disabled` and `pointer-events`, but not visibility. JS clicks can bypass UI obstructions. Assert the resulting state separately; avoid repeated checkbox/radio toggles.
- Diagnostics start with `[WaitUtil]`; `waitAndClick` also logs elapsed time.

## Refresh retry rules

- Allows up to `Math.max(maxAttempts, 2)` attempts, stopping on `true`. First attempt does not refresh.
- After refresh, waits for page load but still runs the action if that wait returns `false`.
- Retries `false`, `ElementNotFoundException`, `TimeoutException`, `StaleElementReferenceException` and `NoSuchElementException`.
- Rethrows `ElementNotFoundException` if thrown on the final attempt; other handled failures end as `false`. Other exceptions/assertion failures propagate.
- Use actions safe to repeat. Each attempt has its own waits; there is no overall 30-second limit.
