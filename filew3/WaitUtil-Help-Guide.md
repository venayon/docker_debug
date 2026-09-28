# WaitUtil Guide
## Centralised Selenium Synchronisation for Java + Cucumber Automation

> **Purpose:** `WaitUtil` is the shared synchronisation layer for Selenium tests.  
> Use it to replace fixed `Thread.sleep(...)` calls and scattered custom waits with condition-based waiting that is faster, clearer, more consistent, and easier to maintain.

---

## 1. Why this utility exists

UI automation is asynchronous by nature.

A test may ask Selenium to click, read text, check a URL, or continue after navigation **before the application is actually ready**. A common first solution is:

```java
Thread.sleep(3000);
element.click();
```

This appears simple, but the test is making a guess:

> "I hope three seconds is enough."

That creates two problems:

- If the application is ready in 300 ms, the test wastes 2.7 seconds.
- If the application needs 3.5 seconds, the test still fails.

`WaitUtil` changes the model from **waiting for time** to **waiting for a condition**.

```java
boolean clicked = WaitUtil.waitAndClick(driver, element);
```

The test now means:

> "Continue as soon as this element is genuinely ready to be clicked, within the configured timeout."

That distinction is the core reason this class should be used centrally across the Java + Cucumber + Selenium test suite.

---

# 2. Why centralising waits is important

Without a shared wait utility, automation suites usually grow into a mixture of:

```java
Thread.sleep(1000);
Thread.sleep(3000);

new WebDriverWait(driver, Duration.ofSeconds(5));
new WebDriverWait(driver, Duration.ofSeconds(20));
new WebDriverWait(driver, Duration.ofSeconds(60));

element.click();

try {
    element.click();
} catch (StaleElementReferenceException e) {
    // retry somehow...
}
```

Different developers solve the same browser timing problem differently.

Over time this creates:

- inconsistent timeout values;
- duplicated retry logic;
- flaky tests;
- unnecessary execution time;
- hidden race conditions;
- different behaviour across Page Objects;
- difficult debugging;
- copy/pasted JavaScript clicks;
- `Thread.sleep(...)` spreading throughout step definitions;
- code that becomes harder for new automation developers to understand.

`WaitUtil` gives the framework **one place** for these decisions.

The current utility centralises:

- a shared wait timeout;
- a shared polling interval;
- stale-element handling;
- temporarily missing-element handling;
- click readiness rules;
- native-click-first behaviour;
- JavaScript click fallback;
- text waiting;
- title waiting;
- URL waiting;
- browser page-load waiting;
- generic WebDriver conditions;
- timeout/error logging.

This is not just code reuse. It is a **test framework policy**.

---

# 3. The rule we want across the test suite

## Avoid this

```java
Thread.sleep(2000);
submitButton.click();
```

## Prefer this

```java
assertThat(WaitUtil.waitAndClick(driver, submitButton))
        .as("Submit button should become clickable")
        .isTrue();
```

Or, when the Page Object owns the interaction:

```java
public boolean clickSubmit() {
    return WaitUtil.waitAndClick(driver, submitButton);
}
```

Then the Cucumber step remains readable:

```java
@When("the user submits the form")
public void theUserSubmitsTheForm() {
    assertThat(formPage.clickSubmit()).isTrue();
}
```

The step describes **business behaviour**.

The Page Object describes **page behaviour**.

`WaitUtil` owns **browser synchronisation behaviour**.

That separation is the target architecture.

---

# 4. What `WaitUtil` does internally

The utility has a central wait configuration:

```java
private static final Duration WAIT_TIME = Duration.ofSeconds(30);

private static final Duration POLL_INTERVAL =
        Duration.ofMillis(Math.max(WAIT_TIME.toMillis() / 30, 200));
```

At the current configuration, Selenium can wait for up to 30 seconds and polls at approximately one-second intervals.

The important point is that this does **not** mean every call waits 30 seconds.

If the condition becomes true after 400 ms, execution continues immediately.

Conceptually:

```text
Start
  |
  v
Is condition satisfied? ---- yes ----> continue test
  |
  no
  |
wait until next poll
  |
  v
check again
  |
  ...
  |
timeout reached
```

That is fundamentally different from:

```text
sleep 5 seconds no matter what
```

---

# 5. Quick method selection guide

| Need | Use |
|---|---|
| Click almost any element | `waitAndClick(driver, element)` |
| Perform a simpler native enabled-and-click retry | `safeClick(driver, element)` |
| Wait for visible, non-blank text and retrieve it | `waitForVisibleText(driver, element)` |
| Wait until element text contains expected text | `waitForTextToContain(driver, element, expectedText)` |
| Wait until page title contains text | `waitForTitleToContain(driver, expectedTitle)` |
| Wait until URL contains a fragment | `waitForUrlToContain(driver, expectedFragment)` |
| Wait for `document.readyState == "complete"` | `waitForPageLoadComplete(driver)` |
| Wait for a custom driver-level state | `waitForCondition(driver, description, condition)` |

## Default choice for clicks

Use:

```java
WaitUtil.waitAndClick(driver, element);
```

for normal framework code unless there is a specific reason not to.

`safeClick(...)` is a lower-level helper and is also used internally as part of stale-element recovery.

---

# 6. `waitAndClick` — the default click helper

## When to use it

Use `waitAndClick(...)` for:

- buttons;
- links;
- checkboxes;
- radio buttons;
- inputs;
- custom component-library controls;
- elements using `role="button"`;
- JavaScript-driven clickable `<div>` or `<span>` controls;
- controls that can temporarily become stale;
- controls whose click can temporarily be intercepted.

Example:

```java
boolean clicked = WaitUtil.waitAndClick(driver, continueButton);

assertThat(clicked)
        .as("Continue button should be clickable")
        .isTrue();
```

### Page Object example

```java
public class CheckoutPage {

    private final WebDriver driver;

    @FindBy(id = "continue")
    private WebElement continueButton;

    public CheckoutPage(WebDriver driver) {
        this.driver = driver;
        PageFactory.initElements(driver, this);
    }

    public boolean clickContinue() {
        return WaitUtil.waitAndClick(driver, continueButton);
    }
}
```

### Cucumber step

```java
@When("the user continues to payment")
public void theUserContinuesToPayment() {
    assertThat(checkoutPage.clickContinue())
            .as("Continue to payment action")
            .isTrue();
}
```

---

# 7. Why `waitAndClick` is stronger than `element.click()`

The utility uses a layered click strategy.

Conceptually:

```text
1. Wait until the element is genuinely ready
             |
             v
2. Try normal Selenium element.click()
             |
     +-------+--------+
     |                |
 success           stale
     |                |
 return true      retry through safeClick
                      |
              intercepted / not interactable
                      |
                      v
              JavaScript fallback
```

This is valuable because the test suite does not need to repeat the same defensive code everywhere.

Instead of this appearing in many Page Objects:

```java
try {
    element.click();
} catch (StaleElementReferenceException e) {
    // retry
} catch (ElementClickInterceptedException e) {
    // JS click
}
```

the framework owns that logic once.

---

# 8. Readiness is more than `isEnabled()`

Modern web applications do not always use a native HTML `disabled` attribute.

For example:

```html
<div role="button" aria-disabled="true">Continue</div>
```

or:

```css
pointer-events: none;
```

Selenium's `isEnabled()` alone may not represent those application-level disabled states for custom elements.

`WaitUtil` therefore checks:

```java
element.isEnabled()
```

plus:

```java
aria-disabled="true"
```

and:

```java
pointer-events: none
```

This is particularly useful for component-library applications where clickable elements are not always native `<button>` controls.

---

# 9. Why `waitAndClick` does not simply require `isDisplayed()`

This is an advanced but important framework detail.

Some checkbox and radio implementations keep the real `<input>` visually hidden while displaying a styled label or pseudo-element to the user.

For example:

```html
<input type="checkbox" style="opacity: 0">
<label>Accept terms</label>
```

A blanket rule such as:

```java
element.isDisplayed() && element.isEnabled()
```

can reject an element that is intentionally hidden but still participates in the component's interaction model.

For that reason, `waitAndClick(...)` does not make `isDisplayed()` a universal prerequisite.

Instead, it attempts the real Selenium click and reacts to the actual interaction result.

This keeps the helper more compatible with custom control implementations.

---

# 10. `safeClick` — simpler native-click retry

`safeClick(...)` waits for the element to be enabled and then performs a normal Selenium click.

```java
boolean clicked = WaitUtil.safeClick(driver, saveButton);
```

## Use it when

- you explicitly want the simpler native-click path;
- you do not need the broader readiness checks of `waitAndClick`;
- framework-level code requires that exact behaviour.

## For normal test code

Prefer:

```java
WaitUtil.waitAndClick(...)
```

because it is the class's broader click abstraction.

---

# 11. `waitForVisibleText` — retrieve text when it is actually ready

A common flaky pattern is:

```java
String message = successBanner.getText();
```

immediately after an asynchronous action.

The banner may exist, but its text may not have appeared yet.

Use:

```java
Optional<String> message =
        WaitUtil.waitForVisibleText(driver, successBanner);
```

Then handle the result explicitly:

```java
String actualMessage = WaitUtil
        .waitForVisibleText(driver, successBanner)
        .orElseThrow(() ->
                new AssertionError("Success message did not become visible"));
```

Assertion:

```java
assertThat(actualMessage)
        .contains("Application submitted");
```

## Why `Optional<String>`?

The method can return:

```java
Optional.empty()
```

when the text never becomes visible/non-blank within the wait or an ordinary Selenium/timing failure occurs.

This forces the caller to make a conscious decision about what failure means.

---

# 12. `waitForTextToContain` — wait and assert dynamic text

Use when text changes asynchronously.

Example application behaviour:

```text
Submitting...
Processing...
Application submitted successfully
```

Avoid:

```java
Thread.sleep(3000);

assertThat(statusBanner.getText())
        .contains("submitted successfully");
```

Use:

```java
assertThat(
        WaitUtil.waitForTextToContain(
                driver,
                statusBanner,
                "submitted successfully"
        )
).isTrue();
```

The match is case-insensitive.

Good use cases include:

- notification banners;
- toast messages;
- validation messages;
- asynchronous status text;
- search result summaries;
- progress completion messages.

---

# 13. `waitForTitleToContain`

Useful after navigation or redirect:

```java
assertThat(
        WaitUtil.waitForTitleToContain(driver, "Dashboard")
).isTrue();
```

Cucumber example:

```java
@Then("the dashboard page is displayed")
public void theDashboardPageIsDisplayed() {
    assertThat(
            WaitUtil.waitForTitleToContain(driver, "Dashboard")
    )
    .as("Dashboard page title")
    .isTrue();
}
```

The title comparison is case-insensitive.

---

# 14. `waitForUrlToContain`

Useful for:

- redirect chains;
- SPA route changes;
- authentication callbacks;
- navigation whose final URL changes asynchronously.

Example:

```java
assertThat(
        WaitUtil.waitForUrlToContain(driver, "/confirmation")
).isTrue();
```

Cucumber example:

```java
@Then("the user is redirected to the confirmation page")
public void userIsRedirectedToConfirmationPage() {
    assertThat(
            WaitUtil.waitForUrlToContain(driver, "/confirmation")
    )
    .as("Confirmation URL")
    .isTrue();
}
```

The URL fragment comparison is case-sensitive.

---

# 15. `waitForPageLoadComplete`

This method waits until JavaScript reports:

```javascript
document.readyState === "complete"
```

## Before

```java
driver.get(baseUrl);
Thread.sleep(2000);
cookieManager.deleteAllCookies();
```

## After

```java
driver.get(baseUrl);

assertThat(
        WaitUtil.waitForPageLoadComplete(driver)
).isTrue();

cookieManager.deleteAllCookies();
```

## Why this is better

A fixed sleep asks:

```text
"Has enough time probably passed?"
```

The utility asks:

```text
"Has the browser reported that page loading is complete?"
```

That is a much stronger synchronization contract.

### Important limitation

`document.readyState == "complete"` means the browser finished the normal document loading lifecycle.

It does **not** guarantee that every asynchronous application operation has completed.

A React/Angular/Vue application may still perform API calls after document load.

For those cases, wait for the **actual application condition**, for example:

```java
WaitUtil.waitForTextToContain(...);
```

or:

```java
WaitUtil.waitForCondition(...);
```

---

# 16. `waitForCondition` — advanced custom waits

This is the generic driver-level escape hatch.

Signature concept:

```java
WaitUtil.waitForCondition(
        driver,
        "description",
        d -> /* condition returning true or false */
);
```

The condition should be:

- quick to evaluate;
- side-effect free where possible;
- safe to call repeatedly.

## Example: wait for a second browser window

```java
assertThat(
        WaitUtil.waitForCondition(
                driver,
                "two browser windows are open",
                d -> d.getWindowHandles().size() == 2
        )
).isTrue();
```

## Example: wait until an alert exists

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

assertThat(alertPresent).isTrue();
```

## Example: wait for local application state exposed by JavaScript

```java
boolean appReady = WaitUtil.waitForCondition(
        driver,
        "application ready flag",
        d -> {
            JavascriptExecutor js = (JavascriptExecutor) d;
            return Boolean.TRUE.equals(
                    js.executeScript("return window.appReady === true;")
            );
        }
);

assertThat(appReady).isTrue();
```

---

# 17. The most important refactoring: removing `Thread.sleep`

## Pattern 1 — sleep before click

### Before

```java
Thread.sleep(2000);
continueButton.click();
```

### After

```java
assertThat(
        WaitUtil.waitAndClick(driver, continueButton)
).isTrue();
```

---

## Pattern 2 — sleep before reading text

### Before

```java
Thread.sleep(1000);

String message = successBanner.getText();
assertThat(message).contains("Success");
```

### After

```java
assertThat(
        WaitUtil.waitForTextToContain(
                driver,
                successBanner,
                "Success"
        )
).isTrue();
```

---

## Pattern 3 — sleep after navigation

### Before

```java
driver.get(targetUrl);
Thread.sleep(3000);
```

### After

```java
driver.get(targetUrl);

assertThat(
        WaitUtil.waitForPageLoadComplete(driver)
).isTrue();
```

If the real requirement is a route:

```java
assertThat(
        WaitUtil.waitForUrlToContain(driver, "/dashboard")
).isTrue();
```

If the real requirement is a page component:

```java
assertThat(
        WaitUtil.waitForTextToContain(
                driver,
                heading,
                "Dashboard"
        )
).isTrue();
```

The best wait is the one that represents the actual condition the scenario needs.

---

# 18. Do not mechanically replace every sleep with `waitForPageLoadComplete`

This is a common migration mistake.

Suppose the existing code is:

```java
clickSearch();
Thread.sleep(2000);
assertResults();
```

It may be tempting to change it to:

```java
clickSearch();
WaitUtil.waitForPageLoadComplete(driver);
assertResults();
```

But if the search results arrive through an AJAX request, page load may already be complete.

The correct replacement is to identify **what the sleep was really waiting for**.

For example:

```java
clickSearch();

assertThat(
        WaitUtil.waitForVisibleText(driver, resultCount)
).isPresent();
```

or:

```java
assertThat(
        WaitUtil.waitForTextToContain(
                driver,
                resultCount,
                "results"
        )
).isTrue();
```

### Refactoring question

Whenever you see:

```java
Thread.sleep(...)
```

ask:

> **What observable condition are we actually waiting for?**

Then choose the helper that represents that condition.

---

# 19. Migration decision table

| Existing code | Ask | Better replacement |
|---|---|---|
| `sleep(); button.click()` | Waiting for click readiness? | `waitAndClick` |
| `sleep(); element.getText()` | Waiting for text? | `waitForVisibleText` |
| `sleep(); assert text` | Waiting for specific message? | `waitForTextToContain` |
| `sleep(); check URL` | Waiting for redirect? | `waitForUrlToContain` |
| `sleep(); check title` | Waiting for page identity? | `waitForTitleToContain` |
| `sleep()` after `get()`/navigation | Waiting for document load? | `waitForPageLoadComplete` |
| custom state with no helper | What condition proves readiness? | `waitForCondition` |

---

# 20. Cucumber architecture — where should waits live?

A healthy structure is:

```text
Feature
  |
Step Definition
  |
Page Object / Component Object
  |
WaitUtil
  |
Selenium WebDriver
```

## Feature

Business language only:

```gherkin
Scenario: Submit an application
  When the user submits the application
  Then a success message is displayed
```

## Step definition

Coordination and assertions:

```java
@When("the user submits the application")
public void submitApplication() {
    assertThat(applicationPage.submit()).isTrue();
}

@Then("a success message is displayed")
public void successMessageIsDisplayed() {
    assertThat(applicationPage.hasSuccessMessage()).isTrue();
}
```

## Page Object

Page-specific behaviour:

```java
public boolean submit() {
    return WaitUtil.waitAndClick(driver, submitButton);
}

public boolean hasSuccessMessage() {
    return WaitUtil.waitForTextToContain(
            driver,
            successBanner,
            "submitted successfully"
    );
}
```

## Why this matters

Avoid placing framework mechanics directly inside every Cucumber step:

```java
@When("the user submits the application")
public void submitApplication() throws InterruptedException {
    Thread.sleep(2000);
    submitButton.click();
}
```

Step definitions should describe the scenario, not browser timing implementation.

---

# 21. Page Object design example

```java
public class LoginPage {

    private final WebDriver driver;

    @FindBy(id = "username")
    private WebElement username;

    @FindBy(id = "password")
    private WebElement password;

    @FindBy(id = "login")
    private WebElement loginButton;

    @FindBy(css = ".error-summary")
    private WebElement errorSummary;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
        PageFactory.initElements(driver, this);
    }

    public void enterUsername(String value) {
        username.sendKeys(value);
    }

    public void enterPassword(String value) {
        password.sendKeys(value);
    }

    public boolean clickLogin() {
        return WaitUtil.waitAndClick(driver, loginButton);
    }

    public boolean hasError(String expectedMessage) {
        return WaitUtil.waitForTextToContain(
                driver,
                errorSummary,
                expectedMessage
        );
    }
}
```

Cucumber:

```java
@When("the user logs in")
public void userLogsIn() {
    loginPage.enterUsername(testUser.username());
    loginPage.enterPassword(testUser.password());

    assertThat(loginPage.clickLogin())
            .as("Login action")
            .isTrue();
}

@Then("the login error {string} is displayed")
public void loginErrorDisplayed(String message) {
    assertThat(loginPage.hasError(message))
            .as("Expected login validation")
            .isTrue();
}
```

This scales much better than sleeps scattered throughout step definitions.

---

# 22. Understanding return values

Most public methods are designed to communicate ordinary timing/browser failures through a return value.

Common patterns are:

```java
boolean
```

or:

```java
Optional<String>
```

Do not ignore them.

## Avoid

```java
WaitUtil.waitAndClick(driver, submitButton);
```

when clicking is essential to the scenario.

If it returns `false`, the test may continue and fail later with a misleading error.

## Prefer

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
        .waitForVisibleText(driver, confirmationBanner)
        .orElseThrow(() ->
                new AssertionError(
                        "Confirmation banner text did not appear"
                ));
```

A wait utility improves reliability only if its result is used meaningfully.

---

# 23. `ElementNotFoundException` — why some failures still throw

There is an important difference between:

```text
"The element existed but never became ready"
```

and:

```text
"The element could not be located for the entire wait"
```

The second situation is often not normal timing flakiness.

It can indicate:

- a wrong locator;
- the wrong page;
- a removed component;
- a broken Page Object;
- a significant application change.

For element-scoped waits, `WaitUtil` can therefore throw:

```java
WaitUtil.ElementNotFoundException
```

when repeated `NoSuchElementException` behaviour indicates the element was genuinely never found.

This is intentional.

It prevents a likely locator/framework bug from being silently converted into a generic:

```text
false
```

and failing much later.

---

# 24. Exception philosophy

The utility separates errors into two broad categories.

## Ordinary automation timing/browser conditions

Examples:

- element temporarily stale;
- element not yet enabled;
- text not yet visible;
- click temporarily blocked;
- condition times out.

These are generally handled by waiting, retrying, logging, and returning a result.

## Programming/configuration errors

Examples:

```java
driver == null
element == null
expectedText == null
```

These indicate incorrect test code rather than normal asynchronous browser behaviour.

The utility deliberately validates these inputs instead of hiding them.

This distinction is useful for test maintainability:

> Recover from browser timing.  
> Expose programming mistakes.

---

# 25. Built-in diagnostics

When element interactions fail, the utility attempts to log useful state such as:

- displayed state;
- enabled state;
- element location;
- tag name;
- text;
- opacity;
- exception type/message.

Example output may resemble:

```text
[WaitUtil] waitAndClick-not-ready-timeout -
Displayed: true,
Enabled: false,
Location: (120, 450),
TagName: button,
Text: Continue,
Opacity: 1,
Exception: TimeoutException: ...
```

The logging itself is defensive so that a stale element during diagnostic collection does not replace the original failure.

This is another advantage of centralisation: diagnostic behaviour improves once for the entire suite.

---

# 26. JavaScript click fallback

`waitAndClick(...)` does **not** start with JavaScript.

It first attempts a normal Selenium click.

That is important because a native Selenium click more closely models real browser interaction and detects problems such as interception.

The JavaScript path is a fallback when Selenium reports the element is genuinely blocked/not interactable.

The helper can also temporarily deal with an opacity-based interaction pattern and restores the original opacity afterwards.

## Why not call JavaScript click everywhere?

Avoid framework code like:

```java
((JavascriptExecutor) driver)
        .executeScript("arguments[0].click();", element);
```

as the default interaction.

A JavaScript click can bypass normal interactability rules and can make a test appear successful even when a real user could not click the control.

The utility's strategy is better:

```text
native interaction first
       |
       v
fallback only when needed
```

---

# 27. "But WaitUtil itself contains a Thread.sleep(50)!"

Yes.

There is a small, private sleep inside the JavaScript opacity fallback:

```java
sleepBriefly(50);
```

This does **not** mean tests should continue using `Thread.sleep(...)`.

There is a major difference between:

### Scattered test synchronization

```java
Thread.sleep(3000);
```

used throughout step definitions and Page Objects as a guess about application readiness,

and:

### Encapsulated implementation detail

```java
sleepBriefly(50);
```

inside one private fallback strategy controlled by the framework.

The goal of this refactor is to remove **test-level fixed sleeps as synchronization logic**.

If the internal fallback implementation is ever improved, it can be changed once inside `WaitUtil` without modifying hundreds of tests.

That is precisely why centralisation matters.

---

# 28. Beginner guidance

If you are new to automation, follow these rules first.

### Rule 1

Do not write:

```java
Thread.sleep(...);
```

to make a Selenium test pass.

### Rule 2

For clicks, start with:

```java
WaitUtil.waitAndClick(driver, element);
```

### Rule 3

For dynamic text, use:

```java
WaitUtil.waitForTextToContain(...);
```

or:

```java
WaitUtil.waitForVisibleText(...);
```

### Rule 4

For redirects:

```java
WaitUtil.waitForUrlToContain(...);
```

### Rule 5

For navigation:

```java
WaitUtil.waitForPageLoadComplete(...);
```

when document load is the condition that matters.

### Rule 6

Assert the result of important waits.

---

# 29. Intermediate guidance

Once comfortable with the basics:

- keep wait calls primarily in Page Objects/component objects;
- keep Cucumber steps business-readable;
- choose a wait based on the real observable condition;
- avoid duplicating `WebDriverWait` unless the shared helper genuinely cannot model the need;
- avoid direct JavaScript click code in tests;
- do not swallow failed wait results;
- use descriptive assertions;
- use `waitForCondition(...)` for driver-level states not already represented by a named helper.

---

# 30. Advanced guidance

Experienced automation developers should treat `WaitUtil` as an abstraction boundary.

Before adding a new ad-hoc wait elsewhere, ask:

1. Is this condition already supported?
2. Can it be expressed using `waitForCondition(...)`?
3. Is it common enough that `WaitUtil` should gain a named method?
4. Would adding framework behaviour here improve every test?
5. Is the proposed condition deterministic and observable?
6. Are we waiting for application state, or just guessing a duration?

Example:

If ten tests begin doing:

```java
WaitUtil.waitForCondition(
        driver,
        "loading spinner disappears",
        d -> !spinner.isDisplayed()
);
```

that may be a sign the framework should introduce a reusable method such as:

```java
waitForElementToDisappear(...)
```

rather than allowing ten slightly different implementations.

Centralisation should evolve with repeated framework needs.

---

# 31. When **not** to use this utility

Do not add waits simply because a test contains Selenium.

A wait is unnecessary when the state is already deterministic.

Also avoid waiting for conditions unrelated to the scenario.

Example:

```java
WaitUtil.waitForPageLoadComplete(driver);
WaitUtil.waitForTitleToContain(driver, "Dashboard");
WaitUtil.waitForUrlToContain(driver, "/dashboard");
WaitUtil.waitForTextToContain(driver, heading, "Dashboard");
```

Using four waits when one reliable business condition proves readiness adds noise.

Prefer the strongest, most meaningful condition for that scenario.

---

# 32. Anti-patterns

## Anti-pattern: sleep followed by WaitUtil

```java
Thread.sleep(2000);
WaitUtil.waitAndClick(driver, button);
```

The sleep defeats much of the purpose.

Use:

```java
WaitUtil.waitAndClick(driver, button);
```

---

## Anti-pattern: ignoring the boolean

```java
WaitUtil.waitForUrlToContain(driver, "/complete");

doNextThing();
```

Prefer:

```java
assertThat(
        WaitUtil.waitForUrlToContain(driver, "/complete")
).isTrue();

doNextThing();
```

---

## Anti-pattern: repeated custom `WebDriverWait`

```java
new WebDriverWait(driver, Duration.ofSeconds(10))
        .until(...);
```

inside many Page Objects.

If the condition is common, centralise it.

---

## Anti-pattern: JavaScript click as first choice

```java
js.executeScript("arguments[0].click();", button);
```

Prefer:

```java
WaitUtil.waitAndClick(driver, button);
```

---

## Anti-pattern: catching everything in the step

```java
try {
    WaitUtil.waitAndClick(driver, submit);
} catch (Exception e) {
    // ignore
}
```

Do not hide genuine failures.

---

# 33. Choosing the strongest wait condition

Suppose clicking "Submit" eventually produces all of these:

- URL becomes `/confirmation`;
- title becomes `Confirmation`;
- success banner appears;
- page load completes.

Which should you wait for?

Choose the condition that best expresses the scenario.

If the scenario says:

```gherkin
Then the application is confirmed
```

then:

```java
WaitUtil.waitForTextToContain(
        driver,
        confirmationBanner,
        "Application submitted"
);
```

may be stronger than merely checking:

```java
document.readyState == "complete"
```

Framework-quality automation waits for **meaningful state**, not merely convenient technical state.

---

# 34. Refactoring workflow for existing tests

A useful migration approach is:

### Step 1 — find fixed sleeps

Search the codebase for:

```text
Thread.sleep
sleep(
```

### Step 2 — inspect what happens immediately afterwards

Example:

```java
Thread.sleep(2000);
paymentButton.click();
```

### Step 3 — identify the real condition

```text
payment button becomes interactable
```

### Step 4 — replace the guess with the condition

```java
WaitUtil.waitAndClick(driver, paymentButton);
```

### Step 5 — assert meaningful outcomes

```java
assertThat(
        WaitUtil.waitAndClick(driver, paymentButton)
).isTrue();
```

### Step 6 — run repeatedly

Do not validate a flakiness refactor with one successful run.

Run the affected scenario repeatedly and, where relevant, under:

- local execution;
- CI;
- headless browser;
- slower environments;
- parallel test execution.

The goal is not merely "it passes."

The goal is:

> **It waits for the correct condition consistently.**

---

# 35. Example end-to-end refactor

## Before

```java
@When("the user submits the claim")
public void submitClaim() throws InterruptedException {

    submitButton.click();

    Thread.sleep(3000);

    String message = confirmationBanner.getText();

    assertThat(message)
            .contains("Claim submitted");
}
```

Problems:

- direct click may race with enablement;
- fixed 3-second delay;
- text may arrive after 3 seconds;
- test always waits 3 seconds even if response is instant;
- step contains browser synchronization details.

## After

Page Object:

```java
public boolean submitClaim() {
    return WaitUtil.waitAndClick(driver, submitButton);
}

public boolean claimSubmissionConfirmed() {
    return WaitUtil.waitForTextToContain(
            driver,
            confirmationBanner,
            "Claim submitted"
    );
}
```

Step:

```java
@When("the user submits the claim")
public void submitClaim() {
    assertThat(claimPage.submitClaim())
            .as("Submit claim")
            .isTrue();
}

@Then("the claim is confirmed")
public void claimIsConfirmed() {
    assertThat(claimPage.claimSubmissionConfirmed())
            .as("Claim confirmation message")
            .isTrue();
}
```

Benefits:

- no fixed test-level delay;
- test proceeds immediately when ready;
- synchronization logic is reusable;
- step definitions are clearer;
- failures occur closer to the real cause;
- browser-specific interaction handling stays central.

---

# 36. Framework ownership

A good team rule is:

> Test code should state **what it needs to become true**.  
> `WaitUtil` should own **how Selenium waits for it**.

Examples:

```text
Test requirement                 WaitUtil responsibility

Button can be clicked      ---> readiness + retry + click strategy
Message appears            ---> polling + visibility + text handling
Redirect finishes          ---> URL polling
Page document loads        ---> readyState polling
Window opens               ---> generic driver-condition polling
```

That is the architectural value of the class.

---

# 37. Team coding standard proposal

The following can be adopted as a pull-request standard:

> **Selenium synchronisation**
>
> - New test code must not introduce `Thread.sleep(...)` for UI synchronization.
> - Use `WaitUtil` for supported Selenium waits and interactions.
> - Prefer `waitAndClick(...)` for clicks.
> - Choose waits based on observable application/browser conditions.
> - Assert or otherwise act on wait return values.
> - Keep wait implementation out of Cucumber feature files.
> - Prefer Page Object/component methods that internally use `WaitUtil`.
> - Repeated custom waits should be considered for promotion into `WaitUtil`.
> - JavaScript interaction should remain a fallback rather than the default.
> - Genuine locator/programming errors must not be silently swallowed.

---

# 38. Pull-request review checklist

When reviewing Selenium changes, ask:

- [ ] Has any new `Thread.sleep(...)` been introduced?
- [ ] Is the test waiting for a condition or merely delaying?
- [ ] Could `waitAndClick(...)` replace direct click + sleep logic?
- [ ] Is asynchronous text handled through a text wait?
- [ ] Are URL/title/navigation waits using the shared utility?
- [ ] Is the wait placed in the appropriate Page Object/component layer?
- [ ] Is the boolean/`Optional` result handled?
- [ ] Is custom JavaScript click code being duplicated?
- [ ] Is there duplicated `WebDriverWait` logic that belongs centrally?
- [ ] Does the chosen wait reflect the business/application condition?
- [ ] Would a new shared `WaitUtil` method benefit multiple tests?

---

# 39. Troubleshooting

## `waitAndClick` returns `false`

Check whether:

- the element ever becomes enabled;
- `aria-disabled` stays `true`;
- CSS keeps `pointer-events: none`;
- another element continuously intercepts the click;
- the application is in the expected state;
- the locator references the intended control.

Review the utility diagnostics before adding another wait.

---

## `ElementNotFoundException` is thrown

Treat this first as a likely locator/page-state problem.

Check:

- locator correctness;
- iframe/window context;
- whether navigation reached the right page;
- whether the component still exists;
- whether the Page Object is stale/outdated.

Do not immediately increase timeout.

A wrong locator does not become correct after waiting longer.

---

## `waitForPageLoadComplete` succeeds but the UI is not ready

The application is probably doing work after the document load event.

Wait for the actual application condition instead:

```java
WaitUtil.waitForTextToContain(...);
```

or:

```java
WaitUtil.waitForCondition(...);
```

---

## Tests still contain sleeps after migration

Do not remove them blindly.

For each remaining sleep, identify its purpose and replace it with the corresponding state-based wait.

Some sleeps may reveal missing helper methods that should be added centrally.

---

# 40. Final mental model

A beginner often writes:

```java
"wait 3 seconds, then try"
```

A stronger automation developer writes:

```java
"wait until the thing I need is true"
```

A mature automation framework goes one step further:

```java
"define that waiting behaviour once,
reuse it everywhere,
log it consistently,
and improve it centrally."
```

That is the role of `WaitUtil`.

---

# 41. Cheat sheet

```java
// CLICK — default
WaitUtil.waitAndClick(driver, button);

// SIMPLE NATIVE CLICK
WaitUtil.safeClick(driver, button);

// READ VISIBLE NON-BLANK TEXT
Optional<String> text =
        WaitUtil.waitForVisibleText(driver, banner);

// WAIT FOR EXPECTED TEXT
WaitUtil.waitForTextToContain(
        driver,
        banner,
        "Success"
);

// WAIT FOR TITLE
WaitUtil.waitForTitleToContain(
        driver,
        "Dashboard"
);

// WAIT FOR URL
WaitUtil.waitForUrlToContain(
        driver,
        "/confirmation"
);

// WAIT FOR DOCUMENT LOAD
WaitUtil.waitForPageLoadComplete(driver);

// CUSTOM DRIVER CONDITION
WaitUtil.waitForCondition(
        driver,
        "two windows open",
        d -> d.getWindowHandles().size() == 2
);
```

---

# 42. Recommended usage summary

For most developers working in this test framework:

```text
Need to click?
    -> waitAndClick

Need to read dynamic text?
    -> waitForVisibleText

Need to prove expected text appeared?
    -> waitForTextToContain

Need to prove navigation/redirect?
    -> waitForUrlToContain / waitForTitleToContain

Need document load?
    -> waitForPageLoadComplete

Need something unusual at WebDriver level?
    -> waitForCondition

Thinking about Thread.sleep?
    -> identify the real condition first
```

---

## Closing principle

**Do not synchronise Selenium tests with guesses about time.  
Synchronise them with observable state.**

`WaitUtil` exists so that this principle is implemented consistently across the entire Java + Cucumber + Selenium automation framework.
