# `WaitUtil.waitForPageLoadComplete`

Waits for the browser to report the page as fully loaded
(`document.readyState === "complete"`), instead of guessing with a fixed
`Thread.sleep(...)` after navigation.

## Why

A fixed sleep after `driver.navigate()` / `driver.get()` is a guess at how
long the page takes to load:

- **Too short** → flaky: the next step (e.g. `deleteAllCookies()`, reading
  an element) runs before the page has actually finished loading.
- **Too long** → wastes time on every run, on every page, whether it needed
  it or not — this adds up across a whole suite.

`waitForPageLoadComplete` polls the real condition instead, so it returns
as soon as the page is actually ready (often faster than a fixed sleep),
and still tolerates a slow page by waiting up to the default timeout
before giving up.

## Signature

```java
/**
 * Waits (up to {@link #WAIT_TIME}) for the browser to report the page
 * as fully loaded ({@code document.readyState === "complete"}).
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
```

Returns `true` once `document.readyState` reports `"complete"`. Returns
`false` on timeout or any error — it never throws, consistent with the
rest of `WaitUtil`.

## Usage

### Before — fixed sleep (flaky)

```java
System.out.println("NAVIGATING TO CUSTOMER FRONTEND");
Navigation.navigateToURL(startPageURL);
sleep(500); // guess - too short on a slow run, wasted time on a fast one
System.out.println("DELETING CUSTOMER COOKIES");
BrowserDriver.getCurrentDriver().manage().deleteAllCookies();
```

### After — polls the actual condition

```java
System.out.println("NAVIGATING TO CUSTOMER FRONTEND");
Navigation.navigateToURL(startPageURL);
WaitUtil.waitForPageLoadComplete(driver);
System.out.println("DELETING CUSTOMER COOKIES");
BrowserDriver.getCurrentDriver().manage().deleteAllCookies();
```

### In a Cucumber step

```java
@When("the user navigates to the dashboard")
public void navigateToDashboard() {
    Navigation.navigateToURL(dashboardUrl);
    WaitUtil.waitForPageLoadComplete(driver);
}
```

```java
public static boolean waitForCondition(final WebDriver driver, final String description,
                                        final java.util.function.Function<WebDriver, Boolean> condition) {
    Objects.requireNonNull(driver, "driver must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(condition, "condition must not be null");
    try {
        return Boolean.TRUE.equals(new WebDriverWait(driver, WAIT_TIME)
                .ignoring(NoSuchElementException.class, StaleElementReferenceException.class)
                .pollingEvery(POLL_INTERVAL)
                .until(condition));
    } catch (TimeoutException e) {
        System.out.printf("[WaitUtil] waitForCondition-timeout - %s%n", description);
        return false;
    } catch (Exception e) {
        System.out.printf("[WaitUtil] waitForCondition-exception - %s: %s%n", description, e.getMessage());
        return false;
    }
}
```

## Notes

- If the underlying `WebDriver` doesn't support JavaScript execution (isn't
  a `JavascriptExecutor`), the method returns `true` immediately rather
  than blocking for the full timeout on a check it can't perform.
- This only confirms the browser has finished loading the document — it
  does **not** guarantee any particular element is present or visible yet
  (e.g. content rendered later by client-side JS). Pair it with
  `WaitUtil.waitAndClick(...)` / `waitForVisibleText(...)` for that.
