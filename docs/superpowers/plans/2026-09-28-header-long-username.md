# Header Long Username Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep long logged-in usernames readable without hiding the bookshelf or logout controls.

**Architecture:** The shared browser script constructs a safe authenticated-user link with the full value in text and title attributes. The shared header stylesheet constrains only that link and applies ellipsis at desktop and narrow widths.

**Tech Stack:** Java 21, JUnit 5, AssertJ, jQuery, CSS

---

### Task 1: Add the regression contract

**Files:**
- Create: `novel-front/src/test/java/com/java2nb/novel/auth/HeaderUserDisplayTemplateTest.java`

- [ ] **Step 1: Write the failing test**

Create a classpath-resource test that reads `static/javascript/common.js` and `static/css/base.css`. Assert that JavaScript contains `header_user_name`, `.text(displayName)`, `.attr("title", displayName)`, and a separate logout link; assert that CSS contains the class plus `max-width`, `overflow: hidden`, `text-overflow: ellipsis`, `white-space: nowrap`, and a responsive media rule.

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -q '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=HeaderUserDisplayTemplateTest' -pl novel-front -am test
```

Expected: FAIL because the dedicated class and safe DOM rendering do not exist.

### Task 2: Implement safe constrained rendering

**Files:**
- Modify: `novel-front/src/main/resources/static/javascript/common.js`
- Modify: `novel-front/src/main/resources/static/css/base.css`
- Modify: `templates/green/static/javascript/common.js`
- Modify: `templates/green/static/css/base.css`

- [ ] **Step 1: Replace authenticated HTML string construction**

Derive `displayName` from `nickName || username || ""`; create the name anchor with jQuery, set its `text` and `title`, then append a separate logout anchor.

- [ ] **Step 2: Add the ellipsis styles**

Add `.bookShelf .header_user_name` with inline-block single-line ellipsis and a desktop maximum width. Add a narrow-screen media rule with a smaller maximum width.

- [ ] **Step 3: Synchronize production external assets**

Copy the finalized shared JavaScript and CSS into `templates/green/static`. The production profile serves these external files from `/app/templates`, so the test must assert that both copies are byte-for-byte equal.

- [ ] **Step 4: Run the focused test**

Run the Task 1 command. Expected: PASS.

- [ ] **Step 5: Run related authentication tests**

Run:

```powershell
& 'D:\IntelliJ IDEA 2024.1.2\plugins\maven\lib\maven3\bin\mvn.cmd' -q '-Dmaven.test.skip=false' '-DskipTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dtest=HeaderUserDisplayTemplateTest,AuthenticationTemplateTest' -pl novel-front -am test
```

Expected: PASS.

- [ ] **Step 6: Check the diff**

Run `git diff --check` and confirm only the test, shared script, shared stylesheet, design, and plan are part of this fix.

