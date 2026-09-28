# WaitUtil Guide
## Centralised Selenium Synchronisation for Java + Cucumber

> `WaitUtil` is the shared synchronisation layer for Selenium tests. Use it instead of fixed `Thread.sleep(...)` calls and repeated custom waits.

---

## 1. Why `WaitUtil` exists

UI tests are asynchronous. Elements may exist before they are enabled, text may appear after an API response, URLs may change after redirects, and pages may re-render while Selenium is interacting with them.

A fixed sleep guesses how long the application needs:

```java
Thread.sleep(3000);
submitButton.click();
```

This is unreliable:

- if the application is ready in 300 ms, the test wastes time;
- if it needs 3.5 seconds, the test still fails.

Prefer waiting for the actual condition:

```java
assertThat(WaitUtil.waitAndClick(driver, submitButton))
        .isTrue();
```

The test continues as soon as the condition is satisfied.

---

## 2. Why centralise waits

Without a shared utility, test suites usually accumulate:

```java
Thread.sleep(...);
new WebDriverWait(...);
element.click();
// repeated stale-element handling
// repeated JavaScript clicks
```

`WaitUtil` gives the framework one place for:

- timeout and polling policy;
- stale/missing-element retries;
- click readiness rules;
- native click and fallback behaviour;
- text, URL, title and page-load waits;
- custom driver-level conditions;
- consistent diagnostics.

Think of it as a **framework policy**, not just a helper class.

---

## 3. Recommended architecture

```text
Feature
   ↓
Step Definition
   ↓
Page / Component Object
   ↓
WaitUtil
   ↓
Selenium WebDriver
```

Keep Cucumber steps focused on business behaviour.

```java
@When("the user submits the form")
public void submitForm() {
    assertThat(formPage.clickSubmit()).isTrue();
}
```

Put Selenium synchronisation inside the Page Object:

```java
public boolean clickSubmit() {
    return WaitUtil.waitAndClick(driver, submitButton);
}
```

---

## 4. Quick method guide

| Need | Use | Where it can be used |
|---|---|---|
| Click an element | `waitAndClick(driver, element)` | Page Objects, component objects, reusable UI actions; default choice for buttons, links, checkboxes, radios and custom clickable controls |
| Simpler native click retry | `safeClick(driver, element)` | Lower-level framework/helper code where only an enabled native Selenium click is required |
| Read visible, non-blank text | `waitForVisibleText(driver, element)` | Page Objects and assertions for banners, labels, toasts, headings and dynamically loaded text |
| Wait for expected text | `waitForTextToContain(driver, element, text)` | Page Objects, Cucumber verification steps and assertions for status, validation, success/error and notification messages |
| Wait for page title | `waitForTitleToContain(driver, title)` | Navigation verification, page-identification checks and Cucumber `Then` steps |
| Wait for URL fragment | `waitForUrlToContain(driver, fragment)` | Redirects, SPA route changes, authentication callbacks and navigation verification |
| Wait for document load | `waitForPageLoadComplete(driver)` | After `driver.get(...)`, navigation or page refresh when browser document load completion is the condition required |
| Wait for a custom WebDriver condition | `waitForCondition(driver, description, condition)` | Advanced/framework scenarios such as window count, alerts, browser state or other driver-level conditions not covered by a named helper |

For normal clicks, use `waitAndClick(...)` by default.

---

# 5. `waitAndClick` — default click helper

Use for buttons, links, checkboxes, radios, inputs and custom controls such as `role="button"` elements.

```java
assertThat(
        WaitUtil.waitAndClick(driver, continueButton)
)
.as("Continue button should be clickable")
.isTrue();
```

The current flow is approximately:

```text
poll
 ↓
check interaction state
 ↓
try native element.click()
 ↓
 success → return true
 stale / temporarily missing → poll again
 intercepted / not interactable → JS fallback
 ↓
timeout → false or ElementNotFoundException
```

The readiness check and click happen in the **same polling cycle**, reducing the race where the DOM changes between "ready" and `click()`.

### Interaction checks

The helper considers:

```java
isEnabled()
aria-disabled="true"
pointer-events: none
```

The internal helper is named `isInteractionEnabled()` because it checks whether interaction is allowed; it does not claim the element is guaranteed to be fully visible or unobstructed.

### Why native click first?

A normal Selenium click better represents real user interaction. JavaScript clicking is a fallback when Selenium reports the element is intercepted or not interactable.

Avoid using direct JS click throughout Page Objects:

```java
((JavascriptExecutor) driver)
        .executeScript("arguments[0].click();", element);
```

Prefer:

```java
WaitUtil.waitAndClick(driver, element);
```

---

## 6. `safeClick`

`safeClick(...)` waits for an element to be enabled and performs a normal Selenium click.

```java
boolean clicked = WaitUtil.safeClick(driver, saveButton);
```

Use it only when the simpler native-click behaviour is specifically required. For normal framework code, prefer `waitAndClick(...)`.

---

## 7. Waiting for text

### Read visible text

```java
String message = WaitUtil
        .waitForVisibleText(driver, successBanner)
        .orElseThrow(() ->
                new AssertionError("Success message did not appear"));
```

Use `waitForVisibleText(...)` when the test needs the actual text value.

### Wait for expected text

```java
assertThat(
        WaitUtil.waitForTextToContain(
                driver,
                statusBanner,
                "submitted successfully"
        )
).isTrue();
```

Useful for:

- banners;
- toasts;
- validation messages;
- asynchronous status text;
- result summaries.

---

## 8. Navigation waits

### URL

```java
assertThat(
        WaitUtil.waitForUrlToContain(driver, "/confirmation")
).isTrue();
```

Use after redirects or SPA route changes.

### Title

```java
assertThat(
        WaitUtil.waitForTitleToContain(driver, "Dashboard")
).isTrue();
```

### Page load

```java
driver.get(targetUrl);

assertThat(
        WaitUtil.waitForPageLoadComplete(driver)
).isTrue();
```

This waits for:

```javascript
document.readyState === "complete"
```

Important: page load complete does **not** mean every React/Angular/Vue API call has finished. If the test needs a specific UI state, wait for that state instead.

---

## 9. Custom waits with `waitForCondition`

Use this when there is no dedicated helper and the condition depends on `WebDriver`.

### Wait for another window

```java
assertThat(
        WaitUtil.waitForCondition(
                driver,
                "two browser windows are open",
                d -> d.getWindowHandles().size() == 2
        )
).isTrue();
```

### Wait for an alert

```java
boolean alertPresent = WaitUtil.waitForCondition(
        driver,
        "browser alert is present",
        d -> {
            try {
                d.switchTo().alert();
                return true;
            } catch (NoAlertPresentException e) {
                return false;
            }
        }
);
```

Custom conditions should be cheap and safe to evaluate repeatedly.

---

# 10. Replacing `Thread.sleep`

Do not replace sleeps mechanically. First ask:

> **What condition was this sleep actually waiting for?**

### Click readiness

Before:

```java
Thread.sleep(2000);
continueButton.click();
```

After:

```java
assertThat(
        WaitUtil.waitAndClick(driver, continueButton)
).isTrue();
```

### Dynamic text

Before:

```java
Thread.sleep(2000);
assertThat(statusBanner.getText()).contains("Success");
```

After:

```java
assertThat(
        WaitUtil.waitForTextToContain(driver, statusBanner, "Success")
).isTrue();
```

### Redirect

Before:

```java
submitButton.click();
Thread.sleep(3000);
assertThat(driver.getCurrentUrl()).contains("/confirmation");
```

After:

```java
assertThat(WaitUtil.waitAndClick(driver, submitButton)).isTrue();
assertThat(WaitUtil.waitForUrlToContain(driver, "/confirmation")).isTrue();
```

### Navigation

Before:

```java
driver.get(targetUrl);
Thread.sleep(3000);
```

After:

```java
driver.get(targetUrl);
assertThat(WaitUtil.waitForPageLoadComplete(driver)).isTrue();
```

Only use `waitForPageLoadComplete()` when document load is actually the condition you need.

---

## 11. Handling return values

Most methods return either:

```java
boolean
```

or:

```java
Optional<String>
```

Do not ignore important results.

Avoid:

```java
WaitUtil.waitAndClick(driver, submitButton);
```

Prefer:

```java
assertThat(
        WaitUtil.waitAndClick(driver, submitButton)
)
.as("Submit button should be clickable")
.isTrue();
```

For text:

```java
String text = WaitUtil
        .waitForVisibleText(driver, banner)
        .orElseThrow(() ->
                new AssertionError("Banner text did not appear"));
```

Fail close to the real problem instead of allowing the scenario to continue into a misleading failure.

---

## 12. `ElementNotFoundException`

There is an important distinction between:

```text
Element exists but never reaches the required state
```

and:

```text
Element is never found during the whole wait
```

The second case often indicates:

- a wrong locator;
- the wrong page;
- a removed component;
- a broken Page Object;
- an application change.

For element-scoped waits, `WaitUtil` can throw:

```java
WaitUtil.ElementNotFoundException
```

The click polling now preserves genuine `NoSuchElementException` behaviour so the utility can distinguish a missing element from a normal readiness timeout.

If this exception occurs, inspect the locator and page state before increasing the timeout.

---

## 13. Failure handling philosophy

### Ordinary browser/timing conditions

Examples:

- temporarily stale element;
- element not yet enabled;
- text not yet visible;
- click temporarily intercepted;
- condition timeout.

These are handled through polling, retries, logging and return values where appropriate.

### Programming errors

Examples:

```java
driver == null
element == null
expectedText == null
```

These should fail immediately. They are test-code problems, not browser timing problems.

---

## 14. Diagnostics

On interaction failures, `WaitUtil` attempts to log useful state such as:

- displayed/enabled state;
- location;
- tag name;
- text;
- opacity;
- exception type and message.

Example:

```text
[WaitUtil] waitAndClick-timeout -
Displayed: true,
Enabled: false,
TagName: button,
Text: Continue,
Exception: TimeoutException ...
```

Check these diagnostics before adding another delay.

---

# 15. Page Object example

```java
public class LoginPage {

    private final WebDriver driver;

    @FindBy(id = "login")
    private WebElement loginButton;

    @FindBy(css = ".error-summary")
    private WebElement errorSummary;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
        PageFactory.initElements(driver, this);
    }

    public boolean clickLogin() {
        return WaitUtil.waitAndClick(driver, loginButton);
    }

    public boolean hasError(String message) {
        return WaitUtil.waitForTextToContain(
                driver,
                errorSummary,
                message
        );
    }
}
```

Cucumber:

```java
@When("the user logs in")
public void userLogsIn() {
    assertThat(loginPage.clickLogin())
            .as("Login action")
            .isTrue();
}

@Then("the login error {string} is displayed")
public void loginErrorDisplayed(String message) {
    assertThat(loginPage.hasError(message))
            .isTrue();
}
```

---

# 16. Common anti-patterns

### Sleep before a wait

Avoid:

```java
Thread.sleep(2000);
WaitUtil.waitAndClick(driver, button);
```

Use:

```java
WaitUtil.waitAndClick(driver, button);
```

### Ignore a wait result

Avoid:

```java
WaitUtil.waitForUrlToContain(driver, "/complete");
doNextThing();
```

Prefer:

```java
assertThat(
        WaitUtil.waitForUrlToContain(driver, "/complete")
).isTrue();
```

### Duplicate `WebDriverWait`

Avoid creating slightly different custom waits across Page Objects when a shared helper already exists.

### JavaScript click as the default

Use `waitAndClick(...)`; let the utility decide when a fallback is necessary.

### Waiting for the wrong condition

Do not use `waitForPageLoadComplete()` for an AJAX result just because it is available. Wait for the result element or text the scenario actually needs.

---

# 17. Beginner → advanced mindset

### Beginner

```text
"Wait three seconds, then try."
```

### Better automation

```text
"Wait until the required condition is true."
```

### Framework-level automation

```text
"Define that behaviour centrally,
reuse it everywhere,
and improve it once for the whole suite."
```

That is the role of `WaitUtil`.

---

## 18. Team rules

Recommended standards for new Selenium code:

- Do not introduce `Thread.sleep(...)` for UI synchronisation.
- Prefer `waitAndClick(...)` for clicks.
- Wait for observable application/browser state.
- Keep synchronisation logic inside Page/Component Objects where practical.
- Handle `boolean` and `Optional` results deliberately.
- Avoid duplicated `WebDriverWait` logic.
- Avoid direct JavaScript clicking in test code.
- Treat `ElementNotFoundException` as a likely locator/page-state issue.
- Promote repeated custom waits into `WaitUtil`.

---

## 19. PR review checklist

- [ ] Any new `Thread.sleep(...)`?
- [ ] Is the test waiting for a real condition rather than delaying?
- [ ] Could `waitAndClick(...)` replace direct click/retry logic?
- [ ] Is dynamic text using a text wait?
- [ ] Are URL/title/navigation waits centralised?
- [ ] Is the wait located in the right framework layer?
- [ ] Is the return value handled?
- [ ] Is custom JS click code being duplicated?
- [ ] Is custom `WebDriverWait` logic worth adding centrally?

---

## 20. Cheat sheet

```java
// Default click
WaitUtil.waitAndClick(driver, button);

// Simple native click
WaitUtil.safeClick(driver, button);

// Read visible text
Optional<String> text =
        WaitUtil.waitForVisibleText(driver, banner);

// Wait for text
WaitUtil.waitForTextToContain(
        driver,
        banner,
        "Success"
);

// Title
WaitUtil.waitForTitleToContain(
        driver,
        "Dashboard"
);

// URL
WaitUtil.waitForUrlToContain(
        driver,
        "/confirmation"
);

// Document load
WaitUtil.waitForPageLoadComplete(driver);

// Custom condition
WaitUtil.waitForCondition(
        driver,
        "two windows open",
        d -> d.getWindowHandles().size() == 2
);
```

---

## Final principle

> **Do not synchronise Selenium tests with guesses about time. Synchronise them with observable state.**

`WaitUtil` exists to apply that principle consistently across the Java + Cucumber + Selenium automation framework.
