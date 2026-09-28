# WaitUtil Quick Guide

**Rule: no `Thread.sleep()` in tests. Use `WaitUtil`.**

## Why it matters

A fixed sleep is a guess: it wastes time when the page is fast and still fails when the page is slow. `WaitUtil` polls the **real condition** (every ~1 s, up to 30 s) and moves on the moment it is true.

**How it helps automation engineers**
- **Faster, more stable runs:** no idle sleeps, fewer flaky failures
- **One central policy:** timeout, polling and stale/not-found retries live in one place, not scattered across tests
- **Less code:** click fallbacks (native, stale retry, JavaScript) are built in, so no `JavascriptExecutor` in tests
- **Easier debugging:** failures log element state (enabled, displayed, text, opacity, exception)
- **Simple contract:** methods return `boolean` / `Optional`, so no try/catch clutter

## Methods and usage

| Need | Method | Returns |
|---|---|---|
| Click anything (default) | `waitAndClick(driver, el)` | `boolean` |
| Read banner/toast text | `waitForVisibleText(driver, el)` | `Optional<String>` |
| Assert element text contains X | `waitForTextToContain(driver, el, "X")` | `boolean` |
| Check title after navigation | `waitForTitleToContain(driver, "Title")` | `boolean` |
| Check URL after navigation | `waitForUrlToContain(driver, "/path")` | `boolean` |
| Replace sleep after navigate | `waitForPageLoadComplete(driver)` | `boolean` |
| Anything else (tab, alert, rows) | `waitForCondition(driver, "label", d -> ...)` | `boolean` |

```java
assertThat(WaitUtil.waitAndClick(driver, loginButton)).as("Login clickable").isTrue();
assertThat(WaitUtil.waitForUrlToContain(driver, "/home")).isTrue();
assertThat(WaitUtil.waitForTextToContain(driver, banner, "welcome")).isTrue();

String ref = WaitUtil.waitForVisibleText(driver, reference)
        .orElseThrow(() -> new AssertionError("Reference never appeared"));

WaitUtil.waitForCondition(driver, "2 windows open", d -> d.getWindowHandles().size() == 2);
```

## Replacing sleeps

Ask: *what am I actually waiting for?*

```java
// Before                                  // After
driver.get(url);                           driver.get(url);
Thread.sleep(2000);                        WaitUtil.waitForPageLoadComplete(driver);
button.click();                            assertThat(WaitUtil.waitAndClick(driver, button)).isTrue();
Thread.sleep(3000);                        assertThat(WaitUtil.waitForUrlToContain(driver, "/next")).isTrue();
```

## Where to use it

Put waits in **Page Objects and step definitions**, never in feature files (no "wait 5 seconds" steps). Prefer lazy `@FindBy` elements, since `WaitUtil` retries the same `WebElement` reference.

## Must know

- **Always assert the result.** Timeouts return `false` / `Optional.empty()` rather than throwing, so an ignored result hides failures.
- `ElementNotFoundException` means the element never existed (wrong locator/page/iframe). Fix it, don't catch it.
- Don't mix implicit waits with `WaitUtil`, and don't hand-roll `WebDriverWait` in tests. Extend `waitForCondition` instead.
