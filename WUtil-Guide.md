# Avoiding Flaky Tests: `WaitUtil` vs `AwaitUtil`

A quick guide for anyone writing Cucumber step definitions in this project.
**Flaky tests almost always come from one root cause: checking something
before it's actually ready.** These two utility classes exist so you never
have to write your own polling/retry logic (or, worse, `Thread.sleep`) to
solve that problem.

---

## The one-line version

| You are waiting on... | Use |
|---|---|
| A **web element** (button, link, banner text, form field) | [`WaitUtil`](#waitutil-selenium--web-elements) |
| **Browser/page state that isn't a specific element** (page title, URL, window count, alert) | [`WaitUtil`](#waitutil-selenium--web-elements) — `waitForCondition`/`waitForTitleToContain`/`waitForUrlToContain` |
| Anything **not driven by Selenium at all** — an API, database, queue, file, async job | [`AwaitUtil`](#awaitutil-backend--async-state) |

If your step touches `WebDriver` or `WebElement` in any way, it's
`WaitUtil` — it has both element-scoped methods (click, read text) and
driver-scoped methods (title, URL, or any custom condition) for that.
`AwaitUtil` is for waits that have nothing to do with the browser at all.

**Never use `Thread.sleep(...)` in a test.** If you're tempted to, one of
these two classes almost certainly already covers your case.

---

## Why flaky tests happen

Most flaky UI/API tests fail for the same reason: the test checks something
*before the system has finished doing it*.

- The page has loaded, but the button is still disabled while JS wires up
  its click handler.
- The API returned 200 for your `POST`, but the read replica / search index
  / cache hasn't caught up yet, so the very next `GET` looks stale.
- An async job (email, batch process, projection) has been *triggered* but
  hasn't *finished*.

A fixed `Thread.sleep(2000)` "fixes" this on your laptop and then fails
randomly in CI, because CI is slower and load varies run to run. Polling
with a sensible timeout — what both these classes do — is the actual fix:
try immediately, keep retrying on a short interval, give up only after a
generous timeout, and tell you clearly what you were waiting for when it
does time out.

---

## `WaitUtil` (Selenium / web elements)

**Package:** `com.a.b.c`

Handles clicking and reading text off elements, resiliently. **Never
throws** for ordinary timing problems — every method returns `false` /
`Optional.empty()` instead, so you don't need try/catch around every call.

| Method | Use it when... |
|---|---|
| `safeClick(driver, element)` | You just need a plain, reliable click and don't care about browser-specific quirks. |
| `waitAndClick(driver, element)` | Default choice for clicking. Picks a JS click on Chrome (handles opacity-hidden elements) or a native click otherwise, and logs how long it took. |
| `waitForVisibleText(driver, element)` | You need to **read** text off an element (banner, toast, label) — not click it. |
| `waitForTextToContain(driver, element, expected)` | You need to **assert** an element eventually shows expected text (e.g. a `Then` step checking a success banner). |
| `waitForTitleToContain(driver, expected)` | You need to wait for/assert the **page title** (e.g. after a navigation/redirect) — not scoped to any element. |
| `waitForUrlToContain(driver, expected)` | You need to wait for/assert the **URL** (e.g. after a client-side route change) — not scoped to any element. |
| `waitForCondition(driver, description, condition)` | None of the above fit — any custom driver-level condition (window count, alert present, etc.). Pass your own `Function<WebDriver, Boolean>`. |

### Example

```java
@When("the user submits the payment")
public void submitPayment() {
    WaitUtil.waitAndClick(driver, submitButton);
}

@Then("a confirmation banner is shown")
public void confirmationBannerShown() {
    boolean shown = WaitUtil.waitForTextToContain(driver, bannerElement, "payment successful");
    assertThat(shown).isTrue();
}
```

### Why it doesn't throw

`WaitUtil` methods return `false`/`empty` instead of throwing so that a
`When` step (an *action*, like clicking) doesn't itself fail the scenario —
your `Then` step's assertion should be what fails, with a clear message,
not an obscure exception from deep inside a click helper.

---

## `AwaitUtil` (backend / async state)

**Package:** `uk.gov.dwp.gysp.acceptancetests.utils.AwaitUtil`

Built on [Awaitility](https://github.com/awaitility/awaitility). Handles
**eventual consistency** — waiting for something to become true on a
system that doesn't update instantly (APIs, databases, replicas, caches,
queues, async jobs). Unlike `WaitUtil`, this **does throw** on timeout,
because it's meant to back `Then` assertions — a timeout here means the
assertion genuinely failed.

| Method | Use it when... |
|---|---|
| `waitUntil(description, condition)` | Simple "wait until this becomes true" — e.g. a job finishes, a record exists. |
| `waitForValue(description, supplier, matcher)` | You want the wait **and** the resulting value, checked against a Hamcrest matcher (e.g. `equalTo(...)`, `hasSize(...)`). |
| `waitUntilConsistentlyTrue(description, condition, holdDuration)` | The value can **flap** — briefly look right then revert (common with load-balanced read replicas / multi-node caches). Requires the condition to hold continuously, not just once. |
| `untilAsserted(description, assertion)` | You're checking **several fields at once** and want a proper assertion failure message (which field, expected vs actual) instead of one generic mismatch. |
| `waitUntilQuietly(description, condition, timeout, pollInterval)` | Rare: you want a `true`/`false` result instead of a thrown exception (e.g. checking something did **not** happen within a window). |

### Example — simple eventual consistency

```java
@Then("the payment status eventually shows as {string}")
public void paymentStatusEventuallyShows(String expectedStatus) {
    AwaitUtil.waitForValue(
            "payment status for " + paymentId,
            () -> paymentApiClient.getStatus(paymentId),
            equalTo(expectedStatus));
}
```

### Example — multiple async sources must agree

```java
AwaitUtil.waitUntil("customer record consistent across DB, index and cache for " + customerId,
        () -> dbRepository.exists(customerId)
                && searchIndexClient.exists(customerId)
                && cacheClient.get(customerId) != null);
```

### Example — guarding against a flapping/false-positive read

```java
AwaitUtil.waitUntilConsistentlyTrue(
        "order status settled for " + orderId,
        () -> orderReadReplica.getStatus(orderId) == OrderStatus.COMPLETE,
        Duration.ofSeconds(5)); // must stay COMPLETE for 5s straight
```

---

## Common mistakes that cause flaky tests (please avoid these)

1. **`Thread.sleep(...)` anywhere in test code.** It's either too short
   (flaky) or too long (slow suite) — never correct. Use `WaitUtil` /
   `AwaitUtil` instead.
2. **Nesting a poll inside a poll.** Don't wrap an `AwaitUtil.waitUntil(...)`
   around a `WaitUtil` click, or vice versa — each already retries
   internally. Nesting just compounds timeouts (30s × 30s) when something
   is genuinely broken, and CI runs hang instead of failing fast.
3. **Catching and swallowing the timeout exception "just to be safe."**
   If `AwaitUtil` times out, that's a real, useful test failure — let it
   fail. Don't wrap it in try/catch to make a red test go green.
   `waitUntilQuietly` exists for the rare legitimate exception; it's not a
   general-purpose "make it stop complaining" tool.
4. **Accepting the first "true" result for something that can flap** (see
   `waitUntilConsistentlyTrue` above). If your API/DB sits behind a
   load-balanced replica set, a single successful poll doesn't always mean
   "done" — it might just mean "one replica out of several has caught up."
5. **Writing a one-off polling loop instead of using these classes.** If
   you find yourself writing a `while` loop with a sleep in it, stop —
   that's exactly what `AwaitUtil`/`WaitUtil` are for. Add a method to the
   shared class instead of duplicating polling logic per test.
6. **No description/alias on the wait.** Always pass a meaningful
   `description` string to `AwaitUtil` methods (e.g. include the entity ID).
   It's what shows up in the failure message and the Cucumber report —
   without it, a timeout just says "condition not met" with zero context
   for whoever picks up the failure next.

---

## Quick checklist before you write a new wait

- [ ] Is this a `WebElement`? → `WaitUtil`. Is it anything else async? → `AwaitUtil`.
- [ ] Did I give it a clear description (for `AwaitUtil`)?
- [ ] Could the value I'm checking flap/revert? → consider `waitUntilConsistentlyTrue`.
- [ ] Am I checking several fields at once? → consider `untilAsserted` for a better failure message.
- [ ] Am I about to write `Thread.sleep`? → don't. Use one of these classes instead.
- [ ] Am I nesting a wait inside another wait? → don't; flatten it into one condition.

If neither class covers your case, don't invent a custom retry loop in your
step definition — ping the automation team first; it's cheaper to extend
`WaitUtil`/`AwaitUtil` once than to have the same flaky pattern copy-pasted
across ten step files.
