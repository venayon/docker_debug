# WaitUtil Guide

Helpers for waiting on **browser / Selenium** conditions: clicking, text, title, URL and page load.

> **Rule of thumb:** if you are waiting for something on a web page, use `WaitUtil`.
> If you are waiting for a backend, API, database or file, use [`AwaitUtil`](AwaitUtil-Guide.md).

---

## 1. Why use it

Pages load and change asynchronously. Two bad habits cause most flaky UI tests:

| Bad habit | Problem |
|---|---|
| `Thread.sleep(5000)` | Too slow when the page is fast, too short when it is slow |
| `element.click()` right after navigating | Fails if the element is not ready, covered by a spinner, or re-rendered |

`WaitUtil` polls until the page is ready, then acts. **Never use `Thread.sleep` in tests.**

Defaults: wait up to **30 seconds**, check every **500 ms**.

---

## 2. The one thing to remember: success or exception

Every `WaitUtil` method either **succeeds** or **throws**. There is no "returns false" for a failure.

| What happened | What you get |
|---|---|
| It worked | Returns `true` (or an `Optional` containing the text) |
| Did not happen in time | Throws `org.openqa.selenium.TimeoutException` with a message saying what it waited for |
| Element was never on the page | Throws `WaitUtil.ElementNotFoundException` (usually a wrong locator) |
| Anything else (browser crashed, bad script) | The original exception is thrown unchanged |

So **do not write** `if (!WaitUtil.waitAndClick(...)) { fail(...); }`. Just call it. If it fails, your test fails with a useful message.

If you really need a yes/no answer without failing (rare), catch the timeout:

```java
boolean bannerShown;
try {
    WaitUtil.waitForUrlToContain(driver, "/promo");
    bannerShown = true;
} catch (TimeoutException e) {
    bannerShown = false;
}
```

---

## 3. Quick reference

| Method | Use it to... |
|---|---|
| `safeClick(driver, element)` | Click normally, retrying until the element is clickable |
| `waitAndClick(driver, element)` | Click, with a JavaScript click as a fallback |
| `waitAndClick(driver, element, timeout, poll)` | Same, with your own wait and poll times |
| `waitForVisibleText(driver, element)` | Wait for an element to show non-blank text, and read it |
| `waitForTextToContain(driver, element, text)` | Wait until an element's text contains something |
| `waitForCondition(driver, description, condition)` | Wait for any custom browser condition |
| `waitForTitleToContain(driver, text)` | Wait for the page title |
| `waitForUrlToContain(driver, fragment)` | Wait for navigation / redirect to finish |
| `waitForPageLoadComplete(driver)` | Wait for the browser to finish loading the page |

---

## 4. Clicking

### `safeClick(driver, element)`

**Solves:** clicks that fail because the element is disabled, still loading, or briefly covered by an overlay or spinner.

**How it works:** retries a normal Selenium click until it succeeds. It does **not** use JavaScript, so it behaves like a real user.

**Use it:** as your default for buttons, links and menu items.

```java
WebElement save = driver.findElement(By.id("save"));
WaitUtil.safeClick(driver, save);
```

### `waitAndClick(driver, element)`

**Solves:** the same problem, plus elements that a normal click cannot reach (blocked by an overlay, or hidden by custom styling).

**How it works:** tries a normal click. If that is blocked, it falls back to a JavaScript click.

**Use it:** for custom widgets or elements that `safeClick` cannot click.

> **Careful:** a JavaScript click ignores whether a real user could click the element. It can hide genuine UI bugs (for example a button that is permanently covered). Start with `safeClick`.

```java
WaitUtil.waitAndClick(driver, driver.findElement(By.cssSelector(".custom-menu-item")));
```

### `waitAndClick(driver, element, timeout, pollInterval)`

**Solves:** you want a shorter or longer wait than the default 30 s.

```java
import java.time.Duration;

// Give up after 5 seconds, check every 200 ms
WaitUtil.waitAndClick(driver, closeButton, Duration.ofSeconds(5), Duration.ofMillis(200));
```

Both durations must be positive, otherwise you get an `IllegalArgumentException`.

### Checkboxes and radio buttons

A click is **not safe to repeat**: clicking a checked box un-checks it. So set the state you want, instead of just clicking:

```java
WebElement terms = driver.findElement(By.id("terms"));
if (!terms.isSelected()) {
    WaitUtil.safeClick(driver, terms);
}
// Check the result with your normal assertion
Assert.assertTrue(terms.isSelected());
```

---

## 5. Reading and checking text

### `waitForVisibleText(driver, element)`

**Solves:** reading text from an element that appears later (toast messages, results, loaded labels).

**Returns:** `Optional<String>` with the trimmed text. It is never empty. If no text appears, it throws.

```java
String message = WaitUtil.waitForVisibleText(driver, driver.findElement(By.id("toast"))).get();
Assert.assertEquals(message, "Saved successfully");
```

### `waitForTextToContain(driver, element, text)`

**Solves:** waiting for text to change to what you expect (for example "Loading..." becoming "Done"). The match ignores upper and lower case.

```java
WebElement status = driver.findElement(By.id("job-status"));
WaitUtil.waitForTextToContain(driver, status, "complete");
```

---

## 6. Waiting for page state

### `waitForUrlToContain(driver, fragment)`

**Solves:** knowing a redirect or navigation has finished. The match is **case-sensitive**.

```java
WaitUtil.safeClick(driver, loginButton);
WaitUtil.waitForUrlToContain(driver, "/dashboard");
```

### `waitForTitleToContain(driver, text)`

**Solves:** confirming you landed on the right page. The match ignores upper and lower case.

```java
WaitUtil.waitForTitleToContain(driver, "Order History");
```

### `waitForPageLoadComplete(driver)`

**Solves:** waiting for the browser's load event after navigation or a refresh.

```java
driver.get("https://example.com/reports");
WaitUtil.waitForPageLoadComplete(driver);
```

> **Limit:** this only checks that the browser finished loading. Modern apps often keep loading data **after** that. For those, wait for something specific on the page with `waitForCondition`.

### `waitForCondition(driver, description, condition)`

**Solves:** any browser condition the other methods do not cover. This is the most flexible method.

- `description` is a plain-English label that appears in the error message. Make it meaningful.
- `condition` is a lambda that receives the driver and returns `true` when ready.

```java
// Wait until the table has at least 5 rows
WaitUtil.waitForCondition(driver, "results table has 5+ rows",
        d -> d.findElements(By.cssSelector("table.results tr")).size() >= 5);

// Wait for a loading spinner to disappear
WaitUtil.waitForCondition(driver, "spinner gone",
        d -> d.findElements(By.cssSelector(".spinner")).isEmpty());
```

---

## 7. Common mistakes

| Mistake | Do this instead |
|---|---|
| `Thread.sleep(...)` | Use a wait method |
| `if (!WaitUtil.waitAndClick(...)) ...` | Just call it. Failure throws |
| Reusing an old `WebElement` after the page re-rendered | Call `driver.findElement(...)` again. A stale element cannot be fixed by waiting |
| Using `waitAndClick` for everything | Use `safeClick` first. Use `waitAndClick` only when needed |
| Vague condition description | Write what you are waiting for, such as `"cart badge shows 3"` |
| Using `WaitUtil` to wait for an API or database | Use `AwaitUtil` |

---

## 8. Reading failures

| Exception | Meaning | First thing to check |
|---|---|---|
| `TimeoutException` | The thing never became true or clickable in time | Is the page really in the state you expect? Is the locator right? |
| `WaitUtil.ElementNotFoundException` | The element was never on the page during the whole wait | The locator, an iframe, or the wrong page |
| `IllegalStateException` | The driver cannot run JavaScript | Rare. Check the driver type |
| `IllegalArgumentException` / `NullPointerException` | Bad argument (zero duration, null element) | Your call |

On failures `WaitUtil` prints diagnostic lines starting with `[WaitUtil]` (displayed, enabled, text, tag). Look for these in the console output.
