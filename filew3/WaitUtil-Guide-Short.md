# WaitUtil Guide (Short)

**Golden rule: no `Thread.sleep()` in tests. Use `WaitUtil`.**

## 1. Why it exists

A fixed sleep is a guess. It wastes time when the page is fast and still fails when the page is slow.

```java
Thread.sleep(5000);   // always 5 s: too long when fast, too short when slow
```

`WaitUtil` polls the **actual condition** (every ~1 s, up to 30 s) and continues the moment it's true.

**Why one central class:**
- One timeout/polling policy for the whole framework (change once, not in 200 files)
- Stale and not-yet-present elements are retried the same way everywhere
- Consistent results: `boolean` / `Optional`, so no try/catch clutter in tests
- Smart click fallbacks (native, stale retry, JavaScript) without `JavascriptExecutor` code in tests
- Built-in failure diagnostics in the log (enabled, displayed, text, opacity, exception)

## 2. Which method do I use?

| I want to... | Use |
|---|---|
| Click anything (button, link, checkbox, custom `role="button"`) | `waitAndClick` (default) |
| Read text of a banner/toast/label | `waitForVisibleText` |
| Assert an element eventually contains text | `waitForTextToContain` |
| Check page title after navigation | `waitForTitleToContain` |
| Check URL after navigation/redirect | `waitForUrlToContain` |
| Replace a sleep after `navigate()`/`get()` | `waitForPageLoadComplete` |
| Anything else (new tab, alert, cookie, row count) | `waitForCondition` |

`safeClick` is a plain click used internally by `waitAndClick`. When unsure, use `waitAndClick`.

## 3. Snippets

```java
WebDriver driver = BrowserDriver.getCurrentDriver();

// Click (returns boolean)
assertThat(WaitUtil.waitAndClick(driver, submitButton)).as("Submit clickable").isTrue();

// Read text (returns Optional<String>, trimmed)
String ref = WaitUtil.waitForVisibleText(driver, reference)
        .orElseThrow(() -> new AssertionError("Reference never appeared"));

// Assert text (case-insensitive "contains")
assertThat(WaitUtil.waitForTextToContain(driver, banner, "payment successful")).isTrue();

// Page-level
assertThat(WaitUtil.waitForTitleToContain(driver, "Dashboard")).isTrue();   // case-insensitive
assertThat(WaitUtil.waitForUrlToContain(driver, "/dashboard")).isTrue();    // case-SENSITIVE
WaitUtil.waitForPageLoadComplete(driver);

// Custom driver-level conditions
WaitUtil.waitForCondition(driver, "2 windows open", d -> d.getWindowHandles().size() == 2);
WaitUtil.waitForCondition(driver, "10 result rows",
        d -> d.findElements(By.cssSelector("#results tr")).size() == 10);
```

## 4. Using it in Cucumber + Page Objects

Put waits in **Page Objects and step definitions**. Never in feature files (no "wait 5 seconds" steps).

```java
// Page Object: callers never need to think about timing
public void submitPayment() {
    if (!WaitUtil.waitAndClick(driver, payNowButton)) {
        throw new AssertionError("'Pay now' could not be clicked");
    }
}

// Step definition
@Then("the payment confirmation page is displayed")
public void confirmationDisplayed() {
    assertThat(WaitUtil.waitForUrlToContain(driver, "/payment/confirm")).isTrue();
}
```

**Locators:** prefer lazy `@FindBy` fields (`PageFactory`). `WaitUtil` retries the *same* `WebElement` reference. A plain `driver.findElement()` result fails immediately if the element isn't there yet, and stays stale once stale.

## 5. Replacing `Thread.sleep()`

Ask: *"What am I actually waiting for?"*

| Old | Replace with |
|---|---|
| sleep after navigate/get | `waitForPageLoadComplete(driver)` |
| sleep before click | `waitAndClick(driver, el)` |
| sleep after click, then check URL | `waitForUrlToContain(driver, "/next")` |
| sleep, then check title | `waitForTitleToContain(driver, "Title")` |
| sleep, then read/assert text | `waitForVisibleText` / `waitForTextToContain` |
| sleep for popup, spinner, etc. | `waitForCondition(driver, "label", d -> ...)` |

```java
// Before
driver.get(loginUrl);
Thread.sleep(2000);
loginButton.click();
Thread.sleep(3000);
Assert.assertTrue(driver.getCurrentUrl().contains("/home"));

// After
driver.get(loginUrl);
WaitUtil.waitForPageLoadComplete(driver);
assertThat(WaitUtil.waitAndClick(driver, loginButton)).isTrue();
assertThat(WaitUtil.waitForUrlToContain(driver, "/home")).isTrue();
```

## 6. Behaviour to know

| Situation | Result |
|---|---|
| Success | `true` / `Optional.of(text)` |
| Ordinary timeout (stale, disabled, hidden, blocked) | `false` / `Optional.empty()` plus a `[WaitUtil]` log line |
| Element **never located** during the whole wait (wrong locator/page) | `ElementNotFoundException` (unchecked): fix the locator, don't catch it |
| Null driver/element/text | `NullPointerException` |

**Failures return `false` instead of throwing, so always assert the result.** A bare `WaitUtil.waitAndClick(driver, el);` silently hides failures.

`waitAndClick` order: wait until enabled and not `aria-disabled` / `pointer-events:none`, then native click, then stale retry, then JavaScript fallback if the click is intercepted. It deliberately does **not** require `isDisplayed()`, so custom checkboxes/radios with a hidden real `<input>` still work.

## 7. Do's and Don'ts

- ✅ Use `waitAndClick` for all clicks
- ✅ Assert every return value
- ✅ Give `waitForCondition` a meaningful label (it appears in failure logs)
- ✅ Keep condition predicates cheap and side-effect-free
- ❌ No `Thread.sleep()` or "wait N seconds" steps
- ❌ Don't mix implicit waits with `WaitUtil` (keep implicit wait at 0)
- ❌ Don't hand-roll `WebDriverWait` in tests. Use or extend `waitForCondition`
- ❌ Don't assume `waitForPageLoadComplete` means a single-page app has finished rendering

## 8. Troubleshooting

- **`expected true but was false`:** check the console for `[WaitUtil]` lines showing why it timed out. Add `.as("...")` to your assertion.
- **`ElementNotFoundException`:** wrong locator, wrong page, iframe, or shadow DOM.
- **`waitAndClick` returns false on a visible button:** likely still `aria-disabled`, `pointer-events:none`, disabled until form is valid, or a stale reference.
- **Timeout is 30 s and fixed** (`WAIT_TIME` in the class). If a wait uses the full 30 s, the condition never happened. That's a real failure, not slowness.

## 9. PR checklist

- [ ] No `Thread.sleep` / `sleep(` in the diff
- [ ] All clicks use `waitAndClick`
- [ ] Every `WaitUtil` result is asserted
- [ ] No new hand-rolled `WebDriverWait` or implicit waits
