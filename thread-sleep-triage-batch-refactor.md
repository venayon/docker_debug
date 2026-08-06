# `Thread.sleep` Triage & Batch Refactor Guide

**Stack:** Selenium + Java 21 + Cucumber (BOM 7.34) + Awaitility
**Context:** 150 occurrences of `Thread.sleep` found via `grep -rn "Thread.sleep" src/test`
**Purpose:** Triage 150 occurrences into fixable patterns and refactor in low-risk batches instead of one-by-one

---

## Step 1: Extract every occurrence with context into a triage sheet

Don't just grep the line — pull surrounding context so you can classify without opening every file.

```bash
grep -rn -B2 -A2 "Thread.sleep" src/test --include="*.java" > sleep_audit_raw.txt
```

Better — generate a CSV you can sort/filter in Excel/Sheets:

```bash
grep -rn "Thread.sleep" src/test --include="*.java" | \
awk -F: '{
  file=$1; line=$2;
  $1=""; $2="";
  gsub(/^[ \t]+/, "");
  print file "," line "," "\"" $0 "\""
}' > sleep_audit.csv
```

This gives you `file, line_number, code` — one row per occurrence, ready to drop into a spreadsheet.

---

## Step 2: Classify each row into a pattern bucket

Most 150-occurrence codebases cluster into ~5 recurring *shapes*, not 150 unique problems.

| Pattern | What it's actually waiting for | Fix template |
|---|---|---|
| **A — Page/element load** | `sleep(N); driver.findElement(...)` | `Awaitility.until(ExpectedConditions.visibilityOfElementLocated(...))` |
| **B — Value/text match** | `sleep(N); assertEquals(expected, element.getText())` | `Awaitility.until(() -> element text equals expected)` |
| **C — Backend/async settle** | `sleep(N)` after an API call or button click, no element check after | `Awaitility.until(() -> apiClient.getStatus(...) == DONE)` |
| **D — Animation/transition** | `sleep(N)` after a click that triggers a modal/dropdown/collapse | `Awaitility.until(ExpectedConditions.elementToBeClickable(...))` or visibility |
| **E — Unexplainable / "just in case"** | `sleep(N)` with no clear reason, often between unrelated steps | Needs investigation — likely masking a shared-state/race issue |

Get the count per bucket. This tells you where to spend effort — usually A+B+D are 70–80% and are mechanical, C needs an API client wired in, E is the dangerous long tail.

---

## Step 3: Auto-flag likely bucket per row

Scan the next non-blank line after each `sleep` for signal words:

```bash
awk '
/Thread\.sleep/ {
    file=FILENAME; line=FNR; sleep_line=$0;
    getline next_line;
    print file ":" line ": SLEEP=[" sleep_line "] NEXT=[" next_line "]"
}
' src/test/**/*.java > sleep_context.txt
```

Then pre-bucket by grepping that output for signal words:

```bash
grep -i "findElement\|isDisplayed\|click()" sleep_context.txt  > bucket_A_D_candidates.txt
grep -i "getText\|assertEquals\|assertThat" sleep_context.txt  > bucket_B_candidates.txt
grep -i "api\|Response\|status\|Client" sleep_context.txt      > bucket_C_candidates.txt
```

Anything left unmatched is Bucket E — needs a human to read the surrounding step and figure out intent.

---

## Step 4: Fix by pattern, not by file — reusable helpers per bucket

Write small reusable wait helpers once, then it's a mechanical find/replace per bucket rather than 150 hand-written `Awaitility` blocks.

```java
public class Waits {

    public static void untilVisible(WebDriver driver, By locator) {
        Awaitility.await("element visible: " + locator)
            .pollDelay(Duration.ofMillis(200))
            .pollInterval(Duration.ofMillis(300))
            .atMost(Duration.ofSeconds(isCi() ? 20 : 10))
            .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
            .until(() -> ExpectedConditions.visibilityOfElementLocated(locator).apply(driver) != null);
    }

    public static void untilClickable(WebDriver driver, By locator) {
        Awaitility.await("element clickable: " + locator)
            .pollDelay(Duration.ofMillis(200))
            .pollInterval(Duration.ofMillis(300))
            .atMost(Duration.ofSeconds(isCi() ? 20 : 10))
            .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
            .until(() -> ExpectedConditions.elementToBeClickable(locator).apply(driver) != null);
    }

    public static void untilTextEquals(WebDriver driver, By locator, String expected) {
        Awaitility.await("text equals '" + expected + "' at: " + locator)
            .pollDelay(Duration.ofMillis(200))
            .pollInterval(Duration.ofMillis(300))
            .atMost(Duration.ofSeconds(isCi() ? 20 : 10))
            .ignoreExceptionsInstanceOf(StaleElementReferenceException.class)
            .until(() -> driver.findElement(locator).getText().equals(expected));
    }

    public static <T> void untilApiState(String description, Supplier<T> poll, T expected) {
        Awaitility.await(description)
            .pollInterval(Duration.ofMillis(500))
            .atMost(Duration.ofSeconds(isCi() ? 20 : 10))
            .until(() -> poll.get().equals(expected));
    }
}
```

Now Bucket A becomes a near-mechanical swap across call sites:

```java
// Before
Thread.sleep(3000);
driver.findElement(loc).click();

// After
Waits.untilClickable(driver, loc);
driver.findElement(loc).click();
```

---

## Step 5: Roll out in batches, not all 150 at once

| Batch | Buckets | Risk | Notes |
|---|---|---|---|
| 1 | A + D (element visibility/clickability) | Low | Mechanical, likely 40–50% of the 150 |
| 2 | B (text/value matches) | Low | Mechanical, similar shape to Batch 1 |
| 3 | C (API-backed waits) | Medium | Needs API client wiring — do after A/B prove the pattern |
| 4 | E (unexplained) | High | Handle last, individually — most likely hiding a real shared-state/race bug rather than just a wait problem |

After each batch, re-run the suite 3–5x and track failure rate before merging the next batch — isolates which batch introduces any new flakiness instead of debugging all 150 changes at once.

---

## Tracking Table (populate from `sleep_audit.csv`)

| File | Line | Code snippet | Bucket | Batch | Status | Owner |
|---|---|---|---|---|---|---|
| | | | | | Not started / In progress / Fixed / Verified | |
