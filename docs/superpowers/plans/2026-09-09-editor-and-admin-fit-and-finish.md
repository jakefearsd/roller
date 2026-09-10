# Editor & Admin Fit-and-Finish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the EasyMDE editor with a CodeMirror 6 editor that has a theme-true live preview, paste/drop image upload and an in-app guide; and sweep the admin screens so status, buttons, selection, dates, sidebars, modals, errors and layouts are consistent, with the walkthrough's defects fixed.

**Architecture:** Package A builds one self-hosted JS bundle (`roller-editor.js`) via `frontend-maven-plugin` + esbuild, exposes `window.RollerEditor`, and keeps the three seam functions (`insertMediaFile`, `rollerSetEntryText`, `rollerGetEntryText`) the browser tests and draft recovery use. Preview reuses `PreviewServlet`'s authenticated model pipeline with a new `shell=true` branch that renders a `_preview` theme template (fallback `templates/weblog/preview.vm`); the fragment endpoint is unchanged. Package B is a JSP/CSS/controller sweep pinned by a new `JspConsistencyTest` source scan and per-bug unit tests.

**Tech Stack:** Java 25, Spring Boot 4.1, JSP/JSTL, Velocity, Bootstrap 5.3.8 (+ Bootstrap Icons 1.13.1), CodeMirror 6 (`@codemirror/state`, `view`, `language`, `commands`, `search`, `autocomplete`, `lang-markdown`, `@lezer/highlight`), esbuild, `frontend-maven-plugin`, JUnit 5, Selenide ITs.

**Spec:** `docs/superpowers/specs/2026-09-09-editor-and-admin-fit-and-finish-design.md`

## Global Constraints

- **Never push.** Commit each task locally (`git commit`), never `git push`. Work on `master` in the main checkout or on the task's worktree branch; do not create feature branches otherwise.
- **TDD:** write the failing test first and watch it fail for the expected reason; characterisation tests (restyles) say so in their javadoc.
- **One build at a time:** before any `mvn` run: `pgrep -f "[s]urefirebooter.*source/roller" >/dev/null && echo BUSY || echo CLEAR`; if BUSY, inline the wait in the same command (`while pgrep -f "[s]urefirebooter.*source/roller" >/dev/null; do sleep 10; done; mvn ...`).
- **Quality gates:** PMD/CPD/SpotBugs at zero (`mvn -pl app verify`); no `@SuppressWarnings` without a stated reason; no `{}` or bare `--` inside `config/pmd/ruleset.xml` comments.
- **SLF4J:** `Throwable` is always the last argument and never consumed by a `{}`.
- **Controllers:** every `@RequestParam`/`@PathVariable` carries an explicit name (`ControllerMetadataTest`).
- **i18n:** every new user-visible string is a key in `ApplicationResources.properties` AND in all seven locale bundles (`_de _es _fr _ja _ko _ru _zh_CN`, English value is fine); `MessageKeyTest`, `MessagePlaceholderContractTest`, `MessageFormatRegressionTest` stay at `Set.of()`. A value with a `{n}` placeholder must not contain a bare `'`; one without must not contain `''`.
- **Escaping:** entry titles are stored escaped (emit bare); page titles are stored raw (escape on output); every author-controlled `bean.*`/display expression in an editor/admin JSP goes through `fn:escapeXml` (`EditorJspEscapingTest`).
- **Confirmations:** `data-confirm` attribute only; never inline `onclick="return confirm(...)"`.
- **Design tokens:** no hex literal in `roller.css`, `roller-tokens.css` or (new) `app/frontend/src/`; the type scale is 12 / 14.5 / 16 / 20 / 26 px only; emphasis is weight (600), never size.
- **Routes:** any CSS marker renamed on an admin screen must be updated in `it-selenium/src/test/java/org/apache/roller/it/support/Routes.java` in the same commit.
- **Velocity is lenient:** before deleting any Java member reachable from a template, `grep -rn <member> app/src/main/webapp/themes app/src/main/webapp/WEB-INF/velocity`.
- **Worktrees:** Package A and Package B run in separate worktrees under `.worktrees/` (git-ignored, inside the repo), both from the same pinned base commit; before merging, run the `comm`/`git merge-tree` check from CLAUDE.md. Merge A before B. Reports go to the main checkout under `docs/superpowers/reports/`, never inside a worktree.
- **Browser suite:** `mvn verify -Pit` is the gate for tasks marked **IT gate**; it takes ~4 minutes; run it from the worktree with `-DskipUnitTests`.

## File ownership (parallel packages)

- **Package A owns:** `EntryEdit.jsp`, `EntryEditor.jsp`, `PageEdit.jsp`, `app/frontend/**`, `WEB-INF/velocity/templates/weblog/preview.vm`, `themes/{journal,travel,portfolio}/preview.vm` + their `theme.xml`, the `init(root)` change in `WEB-INF/velocity/weblog.vm`, `PreviewServlet`, `EntryEditController`, `PageEditController`, `MediaFileAddController`, `MediaApi`, new `ui/controllers/MediaUploads.java`, new `roller-ui/styles/roller-editor.css`, the EasyMDE/Font Awesome lines of `head.jsp` and `pom.xml`, `it-selenium/.../support/Editor.java` and the 25 IT classes' `.CodeMirror` lines, `docs/design/editor/editor-markdown-surface.html`, `docs/design/design-system.md` (card list), CLAUDE.md "Entry editing" + "Build" sections.
- **Package B owns:** every other JSP, `roller.css`, `roller.js`, `RollerViewResolver`, `bannerStatus.jsp`, `BaseController`, `EntriesBean`/`EntriesController`, `ThemeDataServlet`, `TemplatesController`, `MediaFileViewController`, `weblog.vm`'s search-form lines 1158/1191 only, `TemplateEdit.jsp`, `Routes.java` (marker rows for B screens only), CLAUDE.md "Admin UI" section, new `JspConsistencyTest`, new `rollerConfig.tld` tag.
- **Shared, append-only:** `ApplicationResources*.properties` (distinct keys; B rebases on A).

---

# Package A — The editor

### Task A1: Frontend build wiring (Node + esbuild in Maven)

**Files:**
- Create: `app/frontend/package.json`, `app/frontend/build.mjs`, `app/frontend/src/editor.js` (placeholder export), `app/frontend/.npmrc`
- Modify: `pom.xml` (parent `pluginManagement`), `app/pom.xml` (plugin executions), `.gitignore`
- Test: `app/src/test/java/org/apache/roller/weblogger/build/EditorBundlePomTest.java`

**Interfaces:**
- Produces: `target/classes/static/roller-ui/scripts/roller-editor.js` in the WAR, referenced from `head.jsp` as `/roller-ui/scripts/roller-editor.js` (Task A3).

- [ ] **Step 1: Write the failing pom test**

```java
package org.apache.roller.weblogger.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JavaScript build that produces the editor bundle. The bundle is
 * built by Maven (frontend-maven-plugin + esbuild) and is not committed, so
 * the only thing that can rot is this wiring -- a plugin dropped from the
 * pom builds a WAR whose editor page loads a 404 and says nothing.
 */
class EditorBundlePomTest {

    private static final Path APP_POM = Path.of("pom.xml");
    private static final Path PARENT_POM = Path.of("..", "pom.xml");
    private static final Path PACKAGE_JSON = Path.of("frontend", "package.json");
    private static final Path HEAD_JSP = Path.of("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");

    @Test
    void theFrontendPluginIsPinnedInTheParentAndExecutedInApp() throws IOException {
        String parent = Files.readString(PARENT_POM);
        assertTrue(parent.contains("<artifactId>frontend-maven-plugin</artifactId>"),
                "parent pluginManagement must declare frontend-maven-plugin");
        Matcher node = Pattern.compile("<nodeVersion>v(\\d+)\\.(\\d+)\\.(\\d+)</nodeVersion>").matcher(parent);
        assertTrue(node.find(), "nodeVersion must be pinned to an exact vX.Y.Z");
        assertEquals("22", node.group(1), "Node 22 LTS");

        String app = Files.readString(APP_POM);
        int install = app.indexOf("<goal>install-node-and-npm</goal>");
        int ci = app.indexOf("<id>npm-ci</id>");
        int build = app.indexOf("<id>npm-build</id>");
        assertTrue(install > 0 && ci > install && build > ci,
                "install-node-and-npm, npm ci, npm run build must be declared in that order");
        assertTrue(app.contains("<workingDirectory>${project.basedir}/frontend</workingDirectory>"));
    }

    @Test
    void theBundleLandsWhereHeadJspLoadsIt() throws IOException {
        String build = Files.readString(Path.of("frontend", "build.mjs"));
        assertTrue(build.contains("target/classes/static/roller-ui/scripts/roller-editor.js"),
                "esbuild outfile must be the classpath static path");
        String head = Files.readString(HEAD_JSP);
        assertTrue(head.contains("/roller-ui/scripts/roller-editor.js"),
                "head.jsp must load the bundle");
    }

    @Test
    void easyMdeAndFontAwesomeAreGone() throws IOException {
        String app = Files.readString(APP_POM);
        String head = Files.readString(HEAD_JSP);
        assertFalse(app.contains("easymde"), "pom still depends on easymde");
        assertFalse(app.contains("font-awesome"), "pom still depends on font-awesome");
        assertFalse(head.contains("easymde"), "head.jsp still loads easymde");
        assertFalse(head.contains("font-awesome"), "head.jsp still loads font-awesome");
    }

    @Test
    void nodeArtifactsAreIgnored() throws IOException {
        String ignore = Files.readString(Path.of("..", ".gitignore"));
        assertTrue(ignore.contains("app/frontend/node_modules/"));
        assertTrue(ignore.contains("app/frontend/node/"));
        assertTrue(Files.exists(PACKAGE_JSON.resolveSibling("package-lock.json")),
                "package-lock.json must be committed so npm ci is reproducible");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -pl app test -Dtest=EditorBundlePomTest -DfailIfNoTests=false`
Expected: FAIL — "parent pluginManagement must declare frontend-maven-plugin".

(The `easyMdeAndFontAwesomeAreGone` test will keep failing until Task A3 removes them; that is expected. Mark it `@Disabled("removed in Task A3")` for this commit and re-enable in A3.)

- [ ] **Step 3: Add the frontend directory**

`app/frontend/package.json`:
```json
{
  "name": "roller-editor",
  "private": true,
  "version": "0.0.0",
  "scripts": {
    "build": "node build.mjs",
    "test": "node --test test/"
  },
  "dependencies": {
    "@codemirror/autocomplete": "6.18.6",
    "@codemirror/commands": "6.8.1",
    "@codemirror/lang-markdown": "6.3.2",
    "@codemirror/language": "6.11.1",
    "@codemirror/search": "6.5.10",
    "@codemirror/state": "6.5.2",
    "@codemirror/view": "6.36.8",
    "@lezer/highlight": "1.2.1"
  },
  "devDependencies": {
    "esbuild": "0.25.5"
  }
}
```
(Use the exact latest 6.x/1.x versions `npm view <pkg> version` reports at implementation time; pin them exactly, no `^`.)

`app/frontend/build.mjs`:
```js
import { build } from 'esbuild';

await build({
  entryPoints: ['src/editor.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  globalName: 'RollerEditor',
  target: ['es2020'],
  outfile: '../target/classes/static/roller-ui/scripts/roller-editor.js',
  legalComments: 'none',
  logLevel: 'info'
});
```

`app/frontend/src/editor.js` (placeholder for this task; Task A2 fills it):
```js
export function create() { throw new Error('RollerEditor not built yet'); }
```

`app/frontend/.npmrc`: `fund=false\naudit=false\n`

Run `cd app/frontend && npm install` once locally to generate `package-lock.json`; commit the lockfile.

- [ ] **Step 4: Wire the plugin**

Parent `pom.xml`, inside `<pluginManagement><plugins>` (next to `maven-pmd-plugin`):
```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>1.15.1</version>
    <configuration>
        <!-- Pinned exactly: the bundle is built on CI and on every laptop
             from this runtime, and a floating major would move the
             output under everyone. Downloaded once into app/frontend/node
             (git-ignored); only the first build needs the network. -->
        <nodeVersion>v22.17.0</nodeVersion>
        <installDirectory>${project.basedir}/frontend</installDirectory>
    </configuration>
</plugin>
```

`app/pom.xml`, in `<build><plugins>` before `maven-resources-plugin`:
```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <configuration>
        <workingDirectory>${project.basedir}/frontend</workingDirectory>
    </configuration>
    <executions>
        <execution>
            <id>install-node</id>
            <goals><goal>install-node-and-npm</goal></goals>
            <phase>generate-resources</phase>
        </execution>
        <execution>
            <id>npm-ci</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration><arguments>ci</arguments></configuration>
        </execution>
        <execution>
            <id>npm-build</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration><arguments>run build</arguments></configuration>
        </execution>
    </executions>
</plugin>
```

`.gitignore` — append:
```
app/frontend/node_modules/
app/frontend/node/
```

`head.jsp` — add after the `roller.js` line (Task A3 will move it into the editor-only `<c:if>`):
```jsp
<script src="<c:url value='/roller-ui/scripts/roller-editor.js'/>"></script>
```

- [ ] **Step 5: Build and run the test**

Run: `mvn -pl app -DskipTests generate-resources && ls -la app/target/classes/static/roller-ui/scripts/roller-editor.js && mvn -pl app test -Dtest=EditorBundlePomTest`
Expected: bundle file exists (a few KB for the placeholder); PASS (3 of 4, one disabled).

- [ ] **Step 6: Measure and record the cost**

Run cold: `mvn -pl app -DskipTests clean generate-resources` and warm (again). Note both times for the CLAUDE.md update in Task A10.

- [ ] **Step 7: Commit**

```bash
git add pom.xml app/pom.xml .gitignore app/frontend app/src/test/java/org/apache/roller/weblogger/build/EditorBundlePomTest.java app/src/main/webapp/WEB-INF/jsps/tiles/head.jsp
git commit -m "build(editor): add the frontend-maven-plugin + esbuild wiring for the editor bundle"
```

---

### Task A2: The CodeMirror 6 editor core

**Files:**
- Create: `app/frontend/src/editor.js`, `app/frontend/src/markdown-theme.js`, `app/frontend/src/shortcodes.js`, `app/frontend/src/stats.js`, `app/frontend/test/stats.test.mjs`, `app/frontend/test/shortcodes.test.mjs`
- Modify: `app/pom.xml` (add an `npm-test` execution at `test` phase, skipped with `-DskipTests`)

**Interfaces:**
- Produces: `window.RollerEditor.create(options)` where `options = { textarea: HTMLTextAreaElement, placeholder?: string, shortcodes?: [{name, snippet, label}], onChange?: () => void, onUpload?: (files: File[]) => void }` returning `{ getValue(): string, setValue(text: string): void, insert(text: string): void, focus(): void, destroy(): void, view: EditorView, wrapSelection(before, after, placeholderText): void, toggleLinePrefix(prefix): void, scrollFraction(): number, stats(): {words, minutes} }`.
- `stats.js` exports `wordCount(text)` and `readingMinutes(words)`; `shortcodes.js` exports `shortcodeRanges(text, names)` returning `[{from, to}]`.

- [ ] **Step 1: Write the failing node tests**

`app/frontend/test/stats.test.mjs`:
```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { wordCount, readingMinutes } from '../src/stats.js';

test('counts words across lines and ignores markdown punctuation', () => {
  assert.equal(wordCount('# Title\n\nSome **bold** words, and `code`.'), 6);
  assert.equal(wordCount(''), 0);
  assert.equal(wordCount('   \n  '), 0);
});

test('reading time rounds up and never reads 0 for text', () => {
  assert.equal(readingMinutes(0), 0);
  assert.equal(readingMinutes(1), 1);
  assert.equal(readingMinutes(230), 1);
  assert.equal(readingMinutes(231), 2);
});
```

`app/frontend/test/shortcodes.test.mjs`:
```js
import test from 'node:test';
import assert from 'node:assert/strict';
import { shortcodeRanges } from '../src/shortcodes.js';

test('finds opening, closing and escaped shortcodes for registered names only', () => {
  const text = 'a [image id="1"] b [/faq] c [[gallery dir="x"]] d [unknown x] e';
  const ranges = shortcodeRanges(text, ['image', 'faq', 'gallery']);
  assert.deepEqual(ranges, [
    { from: 2, to: 16 },
    { from: 19, to: 25 },
    { from: 28, to: 47 }
  ]);
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd app/frontend && npm test`
Expected: FAIL — cannot find module `../src/stats.js`.

- [ ] **Step 3: Implement the pure modules**

`app/frontend/src/stats.js`:
```js
const WORDS_PER_MINUTE = 230;

export function wordCount(text) {
  if (!text) return 0;
  const stripped = text
    .replace(/`[^`]*`/g, ' code ')
    .replace(/[#*_>\[\]()!\-]+/g, ' ');
  const words = stripped.trim().split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w));
  return words.length;
}

export function readingMinutes(words) {
  if (words <= 0) return 0;
  return Math.ceil(words / WORDS_PER_MINUTE);
}
```

`app/frontend/src/shortcodes.js`:
```js
function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

/** Ranges of [name ...], [/name] and [[name ...]] for the registered names. */
export function shortcodeRanges(text, names) {
  if (!names || names.length === 0) return [];
  const alternation = names.map(escapeRegex).join('|');
  const re = new RegExp('\\[\\[?\\/?(?:' + alternation + ')(?:\\s[^\\]]*)?\\]\\]?', 'g');
  const ranges = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    ranges.push({ from: m.index, to: m.index + m[0].length });
  }
  return ranges;
}
```

Run `npm test` → PASS.

- [ ] **Step 4: Write the editor core**

`app/frontend/src/markdown-theme.js`:
```js
import { EditorView } from '@codemirror/view';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags } from '@lezer/highlight';

/* Every colour is a design token (roller-tokens.css); no hex here. Emphasis
   is weight, never size (design-system.md, signature move 4). */
export const rollerTheme = EditorView.theme({
  '&': {
    backgroundColor: 'var(--surface)',
    color: 'var(--ink)',
    fontFamily: 'var(--font-ui)',
    fontSize: '14.5px',
    border: '1px solid var(--line)',
    borderRadius: '6px'
  },
  '&.cm-focused': { outline: '2px solid var(--focus)', outlineOffset: '1px' },
  '.cm-content': { padding: '12px 14px', lineHeight: '1.6', caretColor: 'var(--ink)' },
  '.cm-line': { padding: '0' },
  '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--ink)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'var(--accent-quiet)'
  },
  '.cm-activeLine': { backgroundColor: 'transparent' },
  '.cm-gutters': { display: 'none' },
  '.cm-placeholder': { color: 'var(--ink-soft)', fontStyle: 'normal' },
  '.cm-scroller': { fontFamily: 'inherit', overflow: 'auto' },
  '.cm-panels': { backgroundColor: 'var(--paper)', color: 'var(--ink)', borderColor: 'var(--line)' },
  '.cm-searchMatch': { backgroundColor: 'var(--accent-quiet)', outline: '1px solid var(--accent)' },
  '.cm-tooltip': { backgroundColor: 'var(--surface)', border: '1px solid var(--line)', color: 'var(--ink)' },
  '.cm-tooltip-autocomplete ul li[aria-selected]': { backgroundColor: 'var(--accent-quiet)', color: 'var(--ink)' },
  '.roller-shortcode': { color: 'var(--accent)', fontFamily: 'var(--font-data)', fontSize: '13.5px' }
});

const style = HighlightStyle.define([
  { tag: tags.heading, fontWeight: '600' },
  { tag: tags.strong, fontWeight: '600' },
  { tag: tags.emphasis, fontStyle: 'italic' },
  { tag: tags.strikethrough, textDecoration: 'line-through' },
  { tag: tags.monospace, fontFamily: 'var(--font-data)', fontSize: '13.5px' },
  { tag: tags.link, color: 'var(--accent)', textDecoration: 'underline' },
  { tag: tags.url, color: 'var(--accent)' },
  { tag: tags.quote, color: 'var(--ink-soft)' },
  { tag: tags.processingInstruction, color: 'var(--ink-soft)' },
  { tag: tags.meta, color: 'var(--ink-soft)' }
]);

export const rollerHighlighting = syntaxHighlighting(style);
```

`app/frontend/src/editor.js`:
```js
import { EditorState, Compartment } from '@codemirror/state';
import { EditorView, keymap, placeholder as placeholderExt, drawSelection,
         dropCursor, highlightActiveLine, ViewPlugin, Decoration } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { markdown, markdownLanguage, markdownKeymap } from '@codemirror/lang-markdown';
import { bracketMatching } from '@codemirror/language';
import { closeBrackets, autocompletion } from '@codemirror/autocomplete';
import { search, searchKeymap } from '@codemirror/search';
import { rollerTheme, rollerHighlighting } from './markdown-theme.js';
import { shortcodeRanges } from './shortcodes.js';
import { wordCount, readingMinutes } from './stats.js';

const shortcodeMark = Decoration.mark({ class: 'roller-shortcode' });

function shortcodePlugin(names) {
  return ViewPlugin.fromClass(class {
    constructor(view) { this.decorations = this.build(view); }
    update(update) { if (update.docChanged || update.viewportChanged) this.decorations = this.build(update.view); }
    build(view) {
      const ranges = [];
      for (const { from, to } of view.visibleRanges) {
        const text = view.state.doc.sliceString(from, to);
        for (const r of shortcodeRanges(text, names)) {
          ranges.push(shortcodeMark.range(from + r.from, from + r.to));
        }
      }
      return Decoration.set(ranges, true);
    }
  }, { decorations: (v) => v.decorations });
}

function shortcodeCompletion(shortcodes) {
  return autocompletion({
    override: [(context) => {
      const word = context.matchBefore(/\[\w*/);
      if (!word || (word.from === word.to && !context.explicit)) return null;
      return {
        from: word.from,
        options: shortcodes.map((s) => ({ label: '[' + s.name, detail: s.label, apply: s.snippet }))
      };
    }]
  });
}

/* Handles image files pasted or dropped into the editor; the host decides
   what an upload means (Task A7). Returns true when it consumed the event. */
function fileHandler(onUpload) {
  const files = (list) => Array.from(list || []).filter((f) => f.type.startsWith('image/'));
  return EditorView.domEventHandlers({
    paste(event) {
      const picked = files(event.clipboardData && event.clipboardData.files);
      if (picked.length === 0 || !onUpload) return false;
      event.preventDefault();
      onUpload(picked);
      return true;
    },
    drop(event) {
      const picked = files(event.dataTransfer && event.dataTransfer.files);
      if (picked.length === 0 || !onUpload) return false;
      event.preventDefault();
      onUpload(picked);
      return true;
    }
  });
}

export function create(options) {
  const textarea = options.textarea;
  const shortcodes = options.shortcodes || [];
  const names = shortcodes.map((s) => s.name);
  const changeListener = EditorView.updateListener.of((update) => {
    if (update.docChanged) {
      textarea.value = update.state.doc.toString();
      if (options.onChange) options.onChange();
    }
  });

  const state = EditorState.create({
    doc: textarea.value,
    extensions: [
      history(),
      drawSelection(),
      dropCursor(),
      highlightActiveLine(),
      bracketMatching(),
      closeBrackets(),
      search({ top: true }),
      markdown({ base: markdownLanguage, addKeymap: false }),
      rollerHighlighting,
      rollerTheme,
      EditorView.lineWrapping,
      placeholderExt(options.placeholder || ''),
      shortcodePlugin(names),
      shortcodeCompletion(shortcodes),
      fileHandler(options.onUpload),
      keymap.of([...markdownKeymap, ...searchKeymap, ...historyKeymap, ...defaultKeymap, indentWithTab]),
      changeListener
    ]
  });

  const wrapper = document.createElement('div');
  wrapper.className = 'roller-editor';
  textarea.parentNode.insertBefore(wrapper, textarea);
  textarea.hidden = true;
  const view = new EditorView({ state, parent: wrapper });

  function replaceSelection(text) {
    view.dispatch(view.state.replaceSelection(text));
    view.focus();
  }

  return {
    view,
    getValue: () => view.state.doc.toString(),
    setValue: (text) => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } }),
    insert: replaceSelection,
    focus: () => view.focus(),
    destroy: () => { view.destroy(); wrapper.remove(); textarea.hidden = false; },
    wrapSelection(before, after, fallback) {
      const { from, to } = view.state.selection.main;
      const selected = view.state.doc.sliceString(from, to) || fallback;
      view.dispatch({
        changes: { from, to, insert: before + selected + after },
        selection: { anchor: from + before.length, head: from + before.length + selected.length }
      });
      view.focus();
    },
    toggleLinePrefix(prefix) {
      const line = view.state.doc.lineAt(view.state.selection.main.from);
      const has = line.text.startsWith(prefix);
      view.dispatch({
        changes: has ? { from: line.from, to: line.from + prefix.length, insert: '' }
                     : { from: line.from, insert: prefix }
      });
      view.focus();
    },
    scrollFraction() {
      const s = view.scrollDOM;
      const max = s.scrollHeight - s.clientHeight;
      return max <= 0 ? 0 : s.scrollTop / max;
    },
    stats() {
      const words = wordCount(view.state.doc.toString());
      return { words, minutes: readingMinutes(words) };
    }
  };
}
```

- [ ] **Step 5: Add the npm test to Maven and build**

`app/pom.xml`, add a fourth execution to the frontend plugin:
```xml
<execution>
    <id>npm-test</id>
    <goals><goal>npm</goal></goals>
    <phase>test</phase>
    <configuration>
        <arguments>test</arguments>
        <skip>${skipTests}</skip>
    </configuration>
</execution>
```
Extend `EditorBundlePomTest.theFrontendPluginIsPinnedInTheParentAndExecutedInApp` with `assertTrue(app.contains("<id>npm-test</id>"))`.

Run: `mvn -pl app -DskipTests generate-resources && ls -la app/target/classes/static/roller-ui/scripts/roller-editor.js`
Expected: bundle ~350–450 KB minified. Then `mvn -pl app test -Dtest=EditorBundlePomTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/frontend app/pom.xml app/src/test/java/org/apache/roller/weblogger/build/EditorBundlePomTest.java
git commit -m "feat(editor): CodeMirror 6 core with token theme, shortcode marks and completion"
```

---

### Task A3: Switch the entry editor to RollerEditor, migrate the browser tests **(IT gate)**

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp`, `app/src/main/webapp/WEB-INF/jsps/tiles/head.jsp`, `app/pom.xml` (drop easymde + font-awesome), `app/src/main/webapp/roller-ui/styles/roller.css` (delete the `/* ---- EasyMDE` block, lines 370–478), `app/src/test/java/org/apache/roller/weblogger/build/EditorBundlePomTest.java` (re-enable)
- Create: `app/src/main/webapp/roller-ui/styles/roller-editor.css`, `it-selenium/src/test/java/org/apache/roller/it/support/Editor.java`
- Modify: the 25 IT classes that name `.CodeMirror` (`grep -rl CodeMirror it-selenium/src/test/java`), `WebjarReferenceTest` if it lists easymde.

**Interfaces:**
- Consumes: `RollerEditor.create` (A2).
- Produces: page globals `rollerEditor` (the A2 handle), `insertMediaFile(text)`, `rollerSetEntryText(text)`, `rollerGetEntryText()`; DOM: `.roller-editor .cm-editor`, toolbar `#editorToolbar` with `button[data-cmd]`, mode control `#editorMode` (`button[data-mode="write|split|preview"]`), `#editorPreviewPane` (empty until A5), `#editorStatus` (empty until A8).
- IT helper `Editor` (below) is the only place ITs name editor selectors.

- [ ] **Step 1: Write the IT support helper and switch every IT to it (failing first)**

`it-selenium/src/test/java/org/apache/roller/it/support/Editor.java`:
```java
package org.apache.roller.it.support;

import com.codeborne.selenide.SelenideElement;

import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;

/**
 * The one place the browser suite names the Markdown editor's DOM. Every
 * read and write goes through the page's own seam functions
 * (rollerGetEntryText / rollerSetEntryText), never the editor's internals,
 * so replacing the editor again is a change to this class only.
 */
public final class Editor {

    /** CodeMirror 6's root; visible once the bundle has mounted. */
    public static final String ROOT = ".roller-editor .cm-editor";
    public static final String CONTENT = ".roller-editor .cm-content";
    public static final String TOOLBAR = "#editorToolbar";
    public static final String MODE_SPLIT = "#editorMode button[data-mode='split']";
    public static final String MODE_PREVIEW = "#editorMode button[data-mode='preview']";
    public static final String MODE_WRITE = "#editorMode button[data-mode='write']";
    public static final String PREVIEW_FRAME = "#editorPreviewPane iframe";

    private Editor() { }

    public static SelenideElement root() {
        return $(ROOT).shouldBe(visible);
    }

    public static void setText(String markdown) {
        root();
        executeJavaScript("rollerSetEntryText(arguments[0]);", markdown);
    }

    public static String getText() {
        root();
        return executeJavaScript("return rollerGetEntryText();");
    }

    /** Types through the keyboard, for tests that need real input events. */
    public static void type(String text) {
        $(CONTENT).shouldBe(visible).click();
        $(CONTENT).sendKeys(text);
    }
}
```

In each of the 25 IT classes: replace `private static final String EDITOR_BODY = ".CodeMirror";` and every `$(".CodeMirror")` with `Editor.root()`; replace `executeJavaScript("rollerSetEntryText(arguments[0]);", x)` with `Editor.setText(x)` and `executeJavaScript("return rollerGetEntryText();")` with `Editor.getText()`. `MarkdownPreviewIT`'s `PREVIEW_BUTTON = "button.preview"` becomes `Editor.MODE_PREVIEW` and `PREVIEW_PANE = ".editor-preview"` becomes `"#editorPreviewPane"` (A5 makes it an iframe; for this task the pane shows the server fragment inline — see Step 3).

- [ ] **Step 2: Run one IT to see it fail for the right reason**

Run: `mvn verify -Pit -DskipUnitTests -Dit.test=AuthoringJourneyIT`
Expected: FAIL — `Element not found {.roller-editor .cm-editor}` (the page still mounts EasyMDE).

- [ ] **Step 3: Replace the EasyMDE block in EntryEditor.jsp**

Replace the `<textarea ...>` through the end of the shortcode `<div class="dropdown ...">` and the `rollerEditor = new EasyMDE({...})` block with:

```jsp
<%-- The editor surface. RollerEditor (roller-editor.js, built from
     app/frontend by Maven) mounts on this textarea; the textarea stays in
     the form as the posted field and is kept in sync on every change. --%>
<div class="editor-surface" id="editorSurface" data-mode="write">
    <div class="editor-toolbar" id="editorToolbar" role="toolbar" aria-label="<spring:message code='editor.toolbar'/>">
        <button type="button" class="editor-tool" data-cmd="bold" title="<spring:message code='editor.bold'/> (Ctrl+B)"><span class="bi bi-type-bold" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bold'/></span></button>
        <button type="button" class="editor-tool" data-cmd="italic" title="<spring:message code='editor.italic'/> (Ctrl+I)"><span class="bi bi-type-italic" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.italic'/></span></button>
        <button type="button" class="editor-tool" data-cmd="heading" title="<spring:message code='editor.heading'/>"><span class="bi bi-type-h2" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.heading'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="quote" title="<spring:message code='editor.quote'/>"><span class="bi bi-blockquote-left" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.quote'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ul" title="<spring:message code='editor.bulletList'/>"><span class="bi bi-list-ul" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.bulletList'/></span></button>
        <button type="button" class="editor-tool" data-cmd="ol" title="<spring:message code='editor.numberedList'/>"><span class="bi bi-list-ol" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.numberedList'/></span></button>
        <span class="editor-tool-sep" aria-hidden="true"></span>
        <button type="button" class="editor-tool" data-cmd="link" title="<spring:message code='editor.link'/> (Ctrl+K)"><span class="bi bi-link-45deg" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.link'/></span></button>
        <button type="button" class="editor-tool" data-cmd="code" title="<spring:message code='editor.code'/>"><span class="bi bi-code" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.code'/></span></button>
        <button type="button" class="editor-tool" data-cmd="table" title="<spring:message code='editor.table'/>"><span class="bi bi-table" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.table'/></span></button>
        <button type="button" class="editor-tool" data-cmd="image" title="<spring:message code='weblogEdit.insertMediaFile'/>"><span class="bi bi-image" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='weblogEdit.insertMediaFile'/></span></button>
        <div class="dropdown d-inline-block" id="shortcodeInsertMenu">
            <button class="editor-tool dropdown-toggle" type="button" id="shortcodeInsertButton"
                    data-bs-toggle="dropdown" aria-expanded="false"><spring:message code="weblogEdit.insertShortcode"/></button>
            <ul class="dropdown-menu" aria-labelledby="shortcodeInsertButton">
                <c:forEach items="${shortcodeCards}" var="card">
                    <li><button type="button" class="dropdown-item shortcode-card"
                                data-shortcode="<c:out value='${card.name}'/>"
                                data-snippet="<c:out value='${card.snippet}'/>"
                                data-chooser="${card.usesMediaChooser}"><spring:message code="${card.labelKey}"/></button></li>
                </c:forEach>
            </ul>
        </div>
        <div class="editor-mode" id="editorMode" role="radiogroup" aria-label="<spring:message code='editor.mode'/>">
            <button type="button" role="radio" aria-checked="true" data-mode="write"><spring:message code="editor.mode.write"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="split"><spring:message code="editor.mode.split"/></button>
            <button type="button" role="radio" aria-checked="false" data-mode="preview"><spring:message code="editor.mode.preview"/></button>
        </div>
        <button type="button" class="editor-tool editor-help" data-cmd="help" title="<spring:message code='editor.guide'/> (Ctrl+/)"><span class="bi bi-question-circle" aria-hidden="true"></span><span class="visually-hidden"><spring:message code='editor.guide'/></span></button>
    </div>
    <div class="editor-panes">
        <div class="editor-write">
            <textarea name="bean.text" id="edit_content" rows="18">${fn:escapeXml(bean.text)}</textarea>
        </div>
        <div class="editor-preview-pane" id="editorPreviewPane" hidden aria-live="polite"></div>
    </div>
    <div class="editor-status" id="editorStatus"></div>
</div>
```

Delete the old `<div class="mb-4"><button ... onclick="onClickMediaFileInsert();">Insert Media File</button></div>` (the toolbar image button replaces it). Keep the Summary card for now (Task A10 restyles it).

Replace the EasyMDE construction in the `$(document).ready` block with:

```js
var shortcodes = [];
document.querySelectorAll('#shortcodeInsertMenu .shortcode-card').forEach(function (b) {
    shortcodes.push({ name: b.dataset.shortcode, snippet: b.dataset.snippet, label: b.textContent.trim() });
});
rollerEditor = RollerEditor.create({
    textarea: document.getElementById('edit_content'),
    placeholder: '<spring:message code="editor.placeholder" javaScriptEscape="true"/>',
    shortcodes: shortcodes
});

var commands = {
    bold: function () { rollerEditor.wrapSelection('**', '**', '<spring:message code="editor.boldPlaceholder" javaScriptEscape="true"/>'); },
    italic: function () { rollerEditor.wrapSelection('*', '*', '<spring:message code="editor.italicPlaceholder" javaScriptEscape="true"/>'); },
    heading: function () { rollerEditor.toggleLinePrefix('## '); },
    quote: function () { rollerEditor.toggleLinePrefix('> '); },
    ul: function () { rollerEditor.toggleLinePrefix('- '); },
    ol: function () { rollerEditor.toggleLinePrefix('1. '); },
    link: function () { rollerEditor.wrapSelection('[', '](https://)', '<spring:message code="editor.linkPlaceholder" javaScriptEscape="true"/>'); },
    code: function () { rollerEditor.wrapSelection('`', '`', 'code'); },
    table: function () { rollerEditor.insert('\n| <spring:message code="editor.tableHeader" javaScriptEscape="true"/> | <spring:message code="editor.tableHeader" javaScriptEscape="true"/> |\n| --- | --- |\n|  |  |\n'); },
    image: function () { onClickMediaFileInsert(); },
    help: function () { if (window.rollerOpenGuide) { window.rollerOpenGuide(); } }
};
document.getElementById('editorToolbar').addEventListener('click', function (event) {
    var button = event.target.closest('button[data-cmd]');
    if (button && commands[button.dataset.cmd]) {
        event.preventDefault();
        commands[button.dataset.cmd]();
    }
});
document.getElementById('editorMode').addEventListener('click', function (event) {
    var button = event.target.closest('button[data-mode]');
    if (button) { rollerSetEditorMode(button.dataset.mode); }
});
try {
    var storedMode = window.localStorage.getItem('roller.editor.mode.v1:${pageContext.request.contextPath}');
    if (storedMode) { rollerSetEditorMode(storedMode); }
} catch (e) { /* storage unavailable: write mode */ }
```

Add, outside `ready`, next to the three seam functions:

```js
<%-- Mode is a data attribute on the surface; CSS lays the panes out.
     Preview content arrives via rollerRenderPreview (server fragment) until
     Task A5 replaces the pane with the theme-true iframe. --%>
function rollerSetEditorMode(mode) {
    var surface = document.getElementById('editorSurface');
    surface.dataset.mode = mode;
    document.querySelectorAll('#editorMode button[data-mode]').forEach(function (b) {
        b.setAttribute('aria-checked', b.dataset.mode === mode ? 'true' : 'false');
    });
    var pane = document.getElementById('editorPreviewPane');
    pane.hidden = (mode === 'write');
    if (mode !== 'write') { rollerRenderPreview(rollerGetEntryText(), pane); }
    try { window.localStorage.setItem('roller.editor.mode.v1:${pageContext.request.contextPath}', mode); } catch (e) { }
}
```

Keep `rollerRenderPreview(plainText, preview)` as is (it sets `preview.innerHTML`); change the dirty-flag and draft hooks from `rollerEditor.codemirror.on('change', ...)` to pass `onChange` into `create` — collect the callbacks into an array `rollerEditorChangeListeners` and have `onChange` call each; `roller-draft.js`'s `onEditorChange: function (cb) { rollerEditorChangeListeners.push(cb); }`.

Replace `rollerEditor.codemirror.replaceSelection(toInsert)` with `rollerEditor.insert(toInsert)`, `rollerEditor.value(text)` with `rollerEditor.setValue(text)`, `rollerEditor.value()` with `rollerEditor.getValue()`, `rollerEditor.codemirror.focus()` with `rollerEditor.focus()`. Delete the `extraKeys` shortcut wiring; the document-level keydown handler already covers Ctrl+S / Ctrl+Enter (CM6 does not claim them). Add `Ctrl+/` → `commands.help()` to that handler.

- [ ] **Step 4: Styles**

`app/src/main/webapp/roller-ui/styles/roller-editor.css` (loaded from `head.jsp` inside the editor-only `<c:if>` together with the bundle; all colours are tokens):
```css
/* Editor surface: toolbar, panes, status. Layout only -- CodeMirror's own
   look comes from EditorView.theme in app/frontend/src/markdown-theme.js. */
.editor-surface { border: 1px solid var(--line); border-radius: 6px; background: var(--surface); }
.editor-toolbar { display: flex; align-items: center; gap: 2px; padding: 6px 8px; border-bottom: 1px solid var(--line); flex-wrap: nowrap; overflow-x: auto; }
.editor-tool { border: 0; background: transparent; color: var(--ink); border-radius: 4px; padding: 4px 7px; font: inherit; font-size: 14.5px; line-height: 1; cursor: pointer; }
.editor-tool:hover, .editor-tool[aria-expanded="true"] { background: var(--accent-quiet); }
.editor-tool:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
.editor-tool-sep { width: 1px; height: 18px; background: var(--line); margin: 0 4px; }
.editor-mode { margin-left: auto; display: inline-flex; border: 1px solid var(--line); border-radius: 99px; overflow: hidden; }
.editor-mode button { border: 0; background: transparent; color: var(--ink-soft); font: inherit; font-size: 12px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; padding: 4px 10px; cursor: pointer; }
.editor-mode button[aria-checked="true"] { background: var(--accent-quiet); color: var(--ink); }
.editor-panes { display: grid; grid-template-columns: 1fr; min-height: 420px; }
.editor-surface[data-mode="split"] .editor-panes { grid-template-columns: 1fr 1fr; }
.editor-surface[data-mode="split"] .editor-preview-pane { border-left: 1px solid var(--line); }
.editor-surface[data-mode="preview"] .editor-write { display: none; }
.editor-write .cm-editor { border: 0; border-radius: 0; min-height: 420px; }
.editor-preview-pane { overflow: auto; }
.editor-preview-pane iframe { width: 100%; height: 100%; min-height: 420px; border: 0; background: var(--surface); }
.editor-status { display: flex; gap: 16px; padding: 6px 12px; border-top: 1px solid var(--line); font-family: var(--font-ui); font-size: 12px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--ink-soft); }
.editor-status:empty { display: none; }
.editor-status .is-error { color: var(--bad); }
```

`head.jsp`: replace the Font Awesome `<c:if>` block and the two EasyMDE lines with:
```jsp
<%-- The Markdown editor. Built from app/frontend by Maven (see CLAUDE.md,
     Build), loaded only on the two screens that mount one. Keyed off
     tile_content for the same reason the old Font Awesome include was. --%>
<c:if test="${fn:contains(tile_content, 'EntryEdit.jsp') or fn:contains(tile_content, 'PageEdit.jsp')}">
<script src="<c:url value='/roller-ui/scripts/roller-editor.js'/>"></script>
<link rel="stylesheet" media="all" href="<c:url value='/roller-ui/styles/roller-editor.css'/>" />
</c:if>
```
Remove the temporary bundle `<script>` added in A1. Remove the `easymde` and `font-awesome` `<dependency>` blocks from `app/pom.xml` and any easymde entry from `WebjarReferenceTest`. Delete the `/* ---- EasyMDE (Markdown editor) ---- */` block from `roller.css` (through `.editor-preview pre { ... }`), keeping `.editor-preview-link`. Re-enable `easyMdeAndFontAwesomeAreGone`.

Add keys (base + 7 bundles): `editor.toolbar=Formatting`, `editor.bold=Bold`, `editor.italic=Italic`, `editor.heading=Heading`, `editor.quote=Quote`, `editor.bulletList=Bulleted list`, `editor.numberedList=Numbered list`, `editor.link=Link`, `editor.code=Code`, `editor.table=Table`, `editor.mode=Editor mode`, `editor.mode.write=Write`, `editor.mode.split=Split`, `editor.mode.preview=Preview`, `editor.guide=Writing guide`, `editor.placeholder=Write in Markdown…`, `editor.boldPlaceholder=bold text`, `editor.italicPlaceholder=italic text`, `editor.linkPlaceholder=link text`, `editor.tableHeader=Header`.

- [ ] **Step 5: Unit tests, then the browser suite**

Run: `mvn -pl app test -Dtest='EditorBundlePomTest,WebjarReferenceTest,DesignTokenTest,MessageKeyTest,EditorJspEscapingTest'` → PASS.
Run: `mvn verify -Pit -DskipUnitTests` → all green. (PageIT will fail because PageEdit still mounts EasyMDE, whose script is gone. Temporarily apply the same bundle mount to `PageEdit.jsp`'s script block — `RollerEditor.create({textarea: document.getElementById('edit_content')})` plus the four seam rewrites — without the toolbar; Task A9 rebuilds that page properly.)

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/webapp app/pom.xml app/src/main/resources app/src/test it-selenium/src/test
git commit -m "feat(editor): mount CodeMirror 6 in the entry editor and retire EasyMDE + Font Awesome"
```

---

### Task A4: Theme-true preview shell (server side)

**Files:**
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/rendering/servlets/PreviewServlet.java` (`selectTemplate`)
- Create: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/preview.vm`, `app/src/main/webapp/themes/journal/preview.vm`, `app/src/main/webapp/themes/travel/preview.vm`, `app/src/main/webapp/themes/portfolio/preview.vm`
- Modify: `app/src/main/webapp/themes/{journal,travel,portfolio}/theme.xml` (register `_preview` as `action="custom"`, like `_page`)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/PreviewShellRenderingTest.java`

**Interfaces:**
- Produces: `GET /roller-ui/authoring/preview/<handle>/?shell=true` → HTML document with `<link rel="stylesheet" href="…stylesheet…">`, the asset macros, `<article id="previewArticle" class="<theme prose class>">` and a `<script>` that listens for `message` events `{type:'roller-preview', html:string}` and calls `window.rollerPreviewInit(article)` after swapping (defined in A5).

- [ ] **Step 1: Write the failing rendering test**

Model on `JournalThemeRenderingTest` (fixture setup, `RenderingTestSupport.ensureRenderingRuntime()`, an authenticated `MockHttpServletRequest`; copy its `setUp`/`tearDown` and the way it obtains the preview servlet — if none exists, add `RenderingTestSupport.previewServlet()` mirroring `pageServlet()`).

```java
@Test
void theShellCarriesTheThemeStylesheetAssetsAndArticle() throws Exception {
    MockHttpServletRequest request = RenderingTestSupport.anonymousGet("/roller-ui/authoring/preview", "/" + HANDLE + "/");
    request.setParameter("shell", "true");
    request.setUserPrincipal(() -> user.getUserName());   // an editor of HANDLE
    MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.previewServlet(), request);
    String html = response.getContentAsString();
    assertEquals(200, response.getStatus());
    assertTrue(html.contains("<link rel=\"stylesheet\" href=\"" + weblog stylesheet url + "\">"), html);
    assertTrue(html.contains("photoswipe.css"), "gallery assets");
    assertTrue(html.contains("leaflet"), "map assets");
    assertTrue(html.contains("<article id=\"previewArticle\" class=\"qj-prose\""), "journal's prose class");
    assertTrue(html.contains("http-equiv=\"Content-Security-Policy\" content=\"" + CSP_JOURNAL_PREVIEW + "\""));
    assertTrue(html.contains("addEventListener('message'"), "the shell listens for fragments");
    assertFalse(html.contains("qj-head"), "no site chrome in the shell");
}

@Test
void aWeblogWhoseThemeShipsNoPreviewTemplateGetsTheSharedShell() throws Exception {
    // same request against a weblog on a theme without _preview (use a
    // TestUtils weblog switched to a theme id with no preview.vm, or a
    // custom theme with no _preview row): expects <article id="previewArticle" class="roller-preview-prose">
}
```
`CSP_JOURNAL_PREVIEW` = journal's pinned CSP string with `frame-ancestors 'none'` replaced by `frame-ancestors 'self'`.

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -pl app test -Dtest=PreviewShellRenderingTest`
Expected: FAIL — the response is the theme's full default page (contains `qj-head`).

- [ ] **Step 3: Add the shell branch to PreviewServlet.selectTemplate**

At the top of `selectTemplate`:
```java
// The editor's live preview asks for the theme's *shell* -- stylesheet,
// asset macros and a bare article -- and fills the article itself from
// the entryEdit!preview.rol fragment. A theme may ship its own _preview
// (the article wrapper in its own prose class); otherwise the shared
// shell renders it, so a theme that has never heard of the editor still
// previews in its own stylesheet.
if ("true".equals(previewRequest.getRequest().getParameter("shell"))) {
    ThemeTemplate shell = null;
    try {
        shell = weblogger.getThemeManager().getTheme(tmpWebsite).getTemplateByName("_preview");
    } catch (WebloggerException e) {
        log.warn("Error looking up '_preview' template for weblog {}", weblog.getHandle(), e);
    }
    return shell != null ? shell
            : new StaticThemeTemplate("templates/weblog/preview.vm", TemplateLanguage.VELOCITY);
}
```
(`WeblogPreviewRequest` must expose the request; if it does not, pass `request.getParameter("shell")` into `selectTemplate` as a boolean argument instead.)

- [ ] **Step 4: The shared shell and the three theme shells**

`WEB-INF/velocity/templates/weblog/preview.vm`:
```velocity
<!doctype html>
<html lang="$model.weblog.getLanguageTag()">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src * data:; frame-src https://www.youtube-nocookie.com https://player.vimeo.com; font-src 'self'; base-uri 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'self'">
    <title>Preview</title>
    <link rel="stylesheet" href="$model.weblog.stylesheet">
    #showGalleryGridStyles()
    #showGalleryAssets()
    #showEmbedAssets()
    #showMapAssets()
    #showPreviewShellScript()
</head>
<body class="roller-preview">
<main class="roller-preview-main">
    <article id="previewArticle" class="roller-preview-prose"></article>
</main>
</body>
</html>
```
Each theme's `preview.vm` is the same document with the theme's CSP (its pinned string, `frame-ancestors 'self'`) and its body/article classes: journal `<body class="qj"><main class="qj-main qj-main-entry"><article id="previewArticle" class="qj-prose">`; travel `<body class="tg"><main class="tg-main"><article id="previewArticle" class="tg-prose">`; portfolio `<body class="pf"><main class="pf-main"><article id="previewArticle" class="pf-prose">` — copy the exact wrapper classes each theme's `permalink.vm` puts around `$model.weblogEntry`'s rendered text (read them; do not guess). Register in each `theme.xml`:
```xml
<template action="custom">
    <name>_preview</name>
    <description>Editor live-preview shell</description>
    <link>_preview</link>
    <navbar>false</navbar>
    <hidden>true</hidden>
    <renditions>
        <rendition>
            <templateLanguage>velocity</templateLanguage>
            <contentsFile>preview.vm</contentsFile>
        </rendition>
    </renditions>
</template>
```
(match the `_page` registration's exact element set).

Add to `weblog.vm`, beside `#showAudienceAssets`:
```velocity
#macro(showPreviewShellScript)
<script>
// The editor posts {type:'roller-preview', html} into this frame; the shell
// swaps the article and re-runs the asset initialisers on the new nodes.
window.addEventListener('message', function (event) {
  if (event.origin !== window.location.origin) { return; }
  var data = event.data || {};
  if (data.type !== 'roller-preview') { return; }
  var article = document.getElementById('previewArticle');
  article.innerHTML = data.html;
  if (window.rollerPreviewInit) { window.rollerPreviewInit(article); }
  if (typeof data.scroll === 'number') {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    window.scrollTo(0, Math.max(0, max) * data.scroll);
  }
});
window.parent.postMessage({ type: 'roller-preview-ready' }, window.location.origin);
</script>
#end
```

- [ ] **Step 5: Run the test, then the theme rendering tests**

Run: `mvn -pl app test -Dtest='PreviewShellRenderingTest,JournalThemeRenderingTest,TravelThemeRenderingTest,PortfolioThemeRenderingTest,ThemeCspCoverageTest'` → PASS. (`ThemeCspCoverageTest` scans theme CSS for `@font-face` vs `font-src`; the shells carry the same `font-src` as their theme.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/rendering/servlets/PreviewServlet.java app/src/main/webapp/WEB-INF/velocity app/src/main/webapp/themes app/src/test/java/org/apache/roller/weblogger/ui/rendering/servlets/PreviewShellRenderingTest.java
git commit -m "feat(preview): a theme-true preview shell, per-theme _preview with a shared fallback"
```

---

### Task A5: Split/preview wiring in the editor, asset re-initialisation **(IT gate)**

**Files:**
- Modify: `EntryEditor.jsp` (preview pane → iframe, debounce, postMessage, scroll sync), `WEB-INF/velocity/weblog.vm` (`#showGalleryAssets`, `#showMapAssets`, `#showEmbedAssets` expose `rollerPreviewInit`)
- Modify: `it-selenium/.../MarkdownPreviewIT.java`

**Interfaces:**
- Consumes: shell URL `${previewShellURL}` (model attribute set by `EntryEditController.addEntryModelAttributes`: `weblogger.getUrlStrategy().getPreviewURLStrategy(null).getWeblogURL(weblog, null, false) + "?shell=true"` — verify the preview strategy's weblog URL is `/roller-ui/authoring/preview/<handle>/`; if not, build the string from `WebloggerRuntimeConfig.getRelativeContextURL() + "/roller-ui/authoring/preview/" + handle + "/?shell=true"`).
- Produces: `window.rollerPreviewInit(root)` inside the shell (from the macros).

- [ ] **Step 1: Extend MarkdownPreviewIT (failing first)**

```java
@Test
void theSplitPaneIsTheThemeAndFollowsTyping() {
    open(ENTRY_ADD);
    Editor.setText("## Field notes\n\nSome *emphasis*.");
    $(Editor.MODE_SPLIT).click();
    SelenideElement frame = $(Editor.PREVIEW_FRAME).shouldBe(visible);
    switchTo().frame(frame);
    $("link[rel='stylesheet'][href*='journal']").should(exist);     // the fixture weblog is on journal
    $("#previewArticle.qj-prose h2").shouldHave(text("Field notes"), Duration.ofSeconds(5));
    switchTo().defaultContent();
    Editor.type("\n\nA new paragraph.");
    switchTo().frame($(Editor.PREVIEW_FRAME));
    $("#previewArticle p").shouldHave(text("A new paragraph."), Duration.ofSeconds(5));
    switchTo().defaultContent();
}
```
Keep the existing `thePreviewRendersMarkdownThroughTheServer` test, updated to click `Editor.MODE_PREVIEW` and read inside the iframe.

- [ ] **Step 2: Run to see it fail**

Run: `mvn verify -Pit -DskipUnitTests -Dit.test=MarkdownPreviewIT` → FAIL: no iframe in `#editorPreviewPane`.

- [ ] **Step 3: Wire the pane**

In `EntryEditor.jsp`, replace `rollerRenderPreview` and `rollerSetEditorMode` with:

```js
var rollerPreview = { frame: null, ready: false, timer: null, pending: false, inflight: false };

function rollerEnsurePreviewFrame() {
    if (rollerPreview.frame) { return; }
    var pane = document.getElementById('editorPreviewPane');
    var frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts allow-same-origin');
    frame.setAttribute('title', '<spring:message code="editor.mode.preview" javaScriptEscape="true"/>');
    frame.src = '${previewShellURL}';
    pane.appendChild(frame);
    rollerPreview.frame = frame;
    window.addEventListener('message', function (event) {
        if (event.origin !== window.location.origin) { return; }
        if (event.data && event.data.type === 'roller-preview-ready') {
            rollerPreview.ready = true;
            rollerPushPreview();
        }
    });
}

<%-- Debounced: a POST per keystroke would render the whole shortcode
     pipeline on every character. 400ms of idle is below "did it hang?" --%>
function rollerSchedulePreview() {
    if (document.getElementById('editorSurface').dataset.mode === 'write') { return; }
    window.clearTimeout(rollerPreview.timer);
    rollerPreview.timer = window.setTimeout(rollerPushPreview, 400);
}

function rollerPushPreview() {
    if (!rollerPreview.ready) { return; }
    if (rollerPreview.inflight) { rollerPreview.pending = true; return; }
    rollerPreview.inflight = true;
    $.ajax({
        type: 'POST',
        url: '<c:url value="/roller-ui/authoring/entryEdit!preview.rol"/>',
        data: {
            id: $("input[name='bean.id']").val(),
            text: rollerGetEntryText(),
            weblog: $("input[name='weblog']").val(),
            '${_csrf.parameterName}': '${_csrf.token}'
        },
        success: function (html) {
            rollerPreview.frame.contentWindow.postMessage(
                { type: 'roller-preview', html: html, scroll: rollerEditor.scrollFraction() },
                window.location.origin);
        },
        error: function () {
            rollerPreview.frame.contentWindow.postMessage(
                { type: 'roller-preview', html: '<p class="roller-preview-error"><spring:message code="weblogEdit.previewFailed" javaScriptEscape="true"/></p>' },
                window.location.origin);
        },
        complete: function () {
            rollerPreview.inflight = false;
            if (rollerPreview.pending) { rollerPreview.pending = false; rollerPushPreview(); }
        }
    });
}

function rollerSetEditorMode(mode) {
    var surface = document.getElementById('editorSurface');
    surface.dataset.mode = mode;
    document.querySelectorAll('#editorMode button[data-mode]').forEach(function (b) {
        b.setAttribute('aria-checked', b.dataset.mode === mode ? 'true' : 'false');
    });
    var pane = document.getElementById('editorPreviewPane');
    pane.hidden = (mode === 'write');
    if (mode !== 'write') { rollerEnsurePreviewFrame(); rollerPushPreview(); }
    try { window.localStorage.setItem('roller.editor.mode.v1:${pageContext.request.contextPath}', mode); } catch (e) { }
}
rollerEditorChangeListeners.push(rollerSchedulePreview);
```
Scroll sync: on the editor's `scrollDOM` `scroll` event (`rollerEditor.view.scrollDOM.addEventListener('scroll', …)`), throttle to one `postMessage({type:'roller-preview-scroll', scroll: rollerEditor.scrollFraction()})` per animation frame; extend the shell script in `#showPreviewShellScript` to handle `roller-preview-scroll` by scrolling proportionally (same arithmetic as the `scroll` field).

In `EntryEditController.addEntryModelAttributes` add `model.addAttribute("previewShellURL", …)` as described in Interfaces; unit-test it in `EntryEditControllerTest` (the attribute ends with `/roller-ui/authoring/preview/<handle>/?shell=true`).

- [ ] **Step 4: Asset initialisers become re-runnable**

In `weblog.vm`:
- `#showGalleryAssets`: wrap the module body in `async function rollerInitGalleries(root) { if (!(root || document).querySelector('.jgrid')) return; … lightbox.init(); }`; keep the top-level call `rollerInitGalleries(document)`; register `window.rollerPreviewInit = (function (prev) { return function (root) { if (prev) prev(root); rollerInitGalleries(root); }; })(window.rollerPreviewInit);`. Because the existing PhotoSwipe instance binds to `.jgrid` via `gallery` selector, destroy the previous lightbox (`if (window.rollerGalleryLightbox) window.rollerGalleryLightbox.destroy();`) before creating a new one.
- `#showMapAssets`: extract the `DOMContentLoaded` body into `function rollerInitMaps(root)` that queries `(root || document).querySelectorAll('.travel-map:not([data-roller-map])')` and marks each container `data-roller-map="1"` after `initMap`; chain onto `window.rollerPreviewInit` the same way; keep the load-once Leaflet script injection.
- `#showEmbedAssets`: same shape, `function rollerInitEmbeds(root)` over `.video-embed:not([data-roller-embed])`.

Extend `MapAssetsRenderingTest`/`GalleryIT`-adjacent unit tests: `weblog.vm` rendered output contains `window.rollerPreviewInit` in each macro (a rendering test that renders a journal permalink and asserts the three `rollerInit*` function names are present).

- [ ] **Step 5: Run gates**

Run: `mvn -pl app test -Dtest='EntryEditControllerTest,MapAssetsRenderingTest,JournalThemeRenderingTest,PreviewShellRenderingTest'` → PASS.
Run: `mvn verify -Pit -DskipUnitTests` → all green (GalleryIT/MapIT/VideoEmbedIT cover the public-page half of the macro change).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/webapp/WEB-INF/jsps/editor/EntryEditor.jsp app/src/main/webapp/WEB-INF/velocity/weblog.vm app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/EntryEditController.java app/src/test it-selenium/src/test
git commit -m "feat(editor): split and preview modes render inside the theme-true shell"
```

---

### Task A6: Shared upload helper and the session upload endpoint

**Files:**
- Create: `app/src/main/java/org/apache/roller/weblogger/ui/controllers/MediaUploads.java`
- Modify: `app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java` (delegate `processUpload`/`effectiveContentType`/`buildMediaFile`/`defaultDirectory` to the helper), `app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/MediaFileAddController.java` (new `upload` mapping)
- Test: `app/src/test/java/org/apache/roller/weblogger/ui/controllers/MediaUploadsTest.java`, `MediaFileAddControllerTest` (new cases), `app/src/test/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApiTest` (existing, must stay green), a source test in `MediaUploadsTest` asserting `MediaApi.java` contains no `effectiveContentType(` definition.

**Interfaces:**
- Produces:
```java
public final class MediaUploads {
    public record Result(String fileName, String status, String detail, MediaFile file) { }
    /** One file, one result, never throws for a per-file refusal. */
    public static Result store(MultipartFile upload, Weblog weblog, MediaFileDirectory directory,
                               MediaFileManager mfm, RollerMessages messages) throws WebloggerException;
    public static MediaFileDirectory defaultDirectory(Weblog weblog, MediaFileManager mfm) throws WebloggerException;
    public static MediaFileDirectory directoryFor(Weblogger weblogger, Weblog weblog, String directoryId) throws WebloggerException; // null when id blank; ownership-checked, null for a foreign/unknown id
}
```
- Endpoint: `POST /roller-ui/authoring/mediaFileAdd!upload.rol` (multipart; params `weblog`, `directoryId?`, files `file`) → JSON `{"results":[{"fileName","status","detail","id","url"}],"created":n,"failed":n}`; 201 all created, 207 otherwise, 400 no files, 404 foreign directory, 403 when `uploads.enabled` is off (JSON `{"error":"uploads.disabled"}`).

- [ ] **Step 1: Failing tests**

`MediaUploadsTest` (DB-backed, `TestUtils` fixtures):
```java
@Test
void aFileLandsInTheGivenDirectoryAndIsReturnedCreated() throws Exception {
    MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", tinyPng());
    RollerMessages messages = new RollerMessages();
    MediaUploads.Result r = MediaUploads.store(png, weblog, dir, mfm, messages);
    assertEquals("created", r.status());
    assertNotNull(r.file().getId());
    assertEquals(0, messages.getErrorCount());
}

@Test
void aForbiddenExtensionIsReportedNotThrown() throws Exception {
    // set uploads.types.forbid to include "exe" via MockWeblogger.attached() runtime config
    MockMultipartFile exe = new MockMultipartFile("file", "x.exe", "application/octet-stream", new byte[]{1});
    MediaUploads.Result r = MediaUploads.store(exe, weblog, dir, mfm, new RollerMessages());
    assertEquals("error", r.status());
    assertNotNull(r.detail());
}

@Test
void aForeignDirectoryIdResolvesToNull() throws Exception {
    assertNull(MediaUploads.directoryFor(weblogger, weblog, otherWeblogsDirectory.getId()));
    assertNull(MediaUploads.directoryFor(weblogger, weblog, "   "));
}

@Test
void theApiNoLongerCarriesItsOwnCopy() throws IOException {
    String api = Files.readString(Path.of("src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java"));
    assertFalse(api.contains("private String effectiveContentType("), "MediaApi must delegate to MediaUploads");
    assertTrue(api.contains("MediaUploads."), "MediaApi must delegate to MediaUploads");
}
```
`MediaFileAddControllerTest` additions (Spring `MockMvc` is not used in this repo; call the method directly with a `MockHttpServletRequest` carrying the action weblog, as the other controller tests do):
```java
@Test
void uploadReturns201AndTheImageShortcodeIdForAGoodFile() { … status 201; body results[0].status=="created", id non-blank … }
@Test
void uploadReturns404ForAForeignDirectory() { … }
@Test
void uploadReturns403WhenUploadsAreDisabled() { … }
@Test
void uploadReturns207WhenOneOfTwoFilesIsRefused() { … }
```

- [ ] **Step 2: Run to see them fail** — `mvn -pl app test -Dtest='MediaUploadsTest,MediaFileAddControllerTest'` → compile failure on `MediaUploads`.

- [ ] **Step 3: Implement the helper** by moving `processUpload`, `effectiveContentType`, `buildMediaFile`, `defaultDirectory` out of `MediaApi` verbatim (keeping their comments) into `MediaUploads` as static methods; `store` returns `Result` (a `MediaFile` rather than the API's `MediaView`). `MediaApi.processUpload` becomes a two-liner: `MediaUploads.Result r = MediaUploads.store(...); return r.file() == null ? new UploadResult(r.fileName(), r.status(), r.detail(), null) : new UploadResult(r.fileName(), "created", null, MediaDtos.toView(r.file(), url(weblog, r.file())));`. `directoryFor` uses `weblogger.getMediaFileManager().getMediaFileDirectory(id)` and returns null unless `dir.getWeblog().getHandle().equals(weblog.getHandle())` (mirror `MediaApi.requireDirectory`'s check).

- [ ] **Step 4: Implement the endpoint** in `MediaFileAddController`:
```java
/**
 * The editor's paste/drop upload. JSON in, JSON out, one result per file;
 * the per-file logic is MediaUploads, shared with the API so the two
 * surfaces cannot drift on what a refusal is. A foreign directory is 404,
 * never 403 (the by-id ownership rule); uploads.enabled off is 403.
 */
@PostMapping(value = "/mediaFileAdd!upload.rol", produces = MediaType.APPLICATION_JSON_VALUE)
@ResponseBody
public ResponseEntity<Map<String, Object>> upload(HttpServletRequest request,
        @RequestParam(value = "directoryId", required = false) String directoryId,
        @RequestParam(value = "file", required = false) MultipartFile[] files) throws WebloggerException {
    Weblog weblog = getActionWeblog(request);
    if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "uploads.disabled"));
    }
    if (files == null || files.length == 0) {
        return ResponseEntity.badRequest().body(Map.of("error", "no.files"));
    }
    MediaFileManager mfm = weblogger.getMediaFileManager();
    MediaFileDirectory directory;
    if (StringUtils.isNotBlank(directoryId)) {
        directory = MediaUploads.directoryFor(weblogger, weblog, directoryId);
        if (directory == null) {
            return ResponseEntity.notFound().build();
        }
    } else {
        directory = MediaUploads.defaultDirectory(weblog, mfm);
    }
    RollerMessages messages = new RollerMessages();
    List<Map<String, Object>> results = new ArrayList<>();
    int created = 0;
    for (MultipartFile file : files) {
        MediaUploads.Result r = MediaUploads.store(file, weblog, directory, mfm, messages);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fileName", r.fileName());
        row.put("status", r.status());
        row.put("detail", r.detail());
        if (r.file() != null) {
            created++;
            row.put("id", r.file().getId());
            row.put("url", weblogger.getUrlStrategy().getMediaFileURL(weblog, r.file().getId(), false));
        }
        results.add(row);
    }
    weblogger.flush();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("results", results);
    body.put("created", created);
    body.put("failed", files.length - created);
    return ResponseEntity.status(created == files.length ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS).body(body);
}
```
Confirm `MediaFileAddController implements UISecurityEnforced` with `WeblogPermission` POST (it does, for `save`); the interceptor covers the new mapping automatically.

- [ ] **Step 5: Run** `mvn -pl app test -Dtest='MediaUploadsTest,MediaFileAddControllerTest,MediaApi*Test'` → PASS; `mvn -pl app verify -DskipTests=false -Dtest='MediaUploadsTest'` is not needed; run `mvn -pl app pmd:check spotbugs:check` after `compile` to be sure the moved code passes the gates in its new home.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/org/apache/roller/weblogger/ui/controllers/MediaUploads.java app/src/main/java/org/apache/roller/weblogger/ui/restapi/v1/MediaApi.java app/src/main/java/org/apache/roller/weblogger/ui/controllers/editor/MediaFileAddController.java app/src/test
git commit -m "feat(media): one shared per-file upload helper and a session JSON upload endpoint"
```

---

### Task A7: Paste and drop images into the editor **(IT gate)**

**Files:**
- Modify: `EntryEditor.jsp` (upload handler), `app/frontend/src/editor.js` (already routes files to `onUpload`), `it-selenium/.../MarkdownPreviewIT.java` (or a new `EditorUploadIT`)

- [ ] **Step 1: Failing IT** (`EditorUploadIT`, holds `RollerIT.SHARED_MEDIA` write lock and `GLOBAL_CONFIG` read lock like `MediaBulkUploadIT`):
```java
@Test
void aDroppedImageBecomesAnImageShortcode() {
    open(ENTRY_ADD);
    Editor.root();
    // Simulate the drop through the page's own seam: rollerUploadImages(files)
    executeJavaScript(
        "var bytes = Uint8Array.from(atob(arguments[0]), c => c.charCodeAt(0));" +
        "var f = new File([bytes], 'drop.png', {type: 'image/png'});" +
        "rollerUploadImages([f]);", TINY_PNG_BASE64);
    Wait().until(d -> Editor.getText().matches("(?s).*\\[image id=\"[^\"]+\"\\].*"));
    assertFalse(Editor.getText().contains("uploading"));
}

@Test
void aRefusedFileLeavesAStatusLineMessageAndNoShortcode() {
    // uploads.types.forbid includes "exe" by default in dev config? If not, set it via setGlobalFlag in @BeforeAll.
    … rollerUploadImages([File named x.exe type image/png]) …   // extension refused by the manager
    $("#editorStatus .is-error").shouldBe(visible);
    assertFalse(Editor.getText().contains("[image"));
}
```

- [ ] **Step 2: Run to fail** — `mvn verify -Pit -DskipUnitTests -Dit.test=EditorUploadIT` → `rollerUploadImages is not defined`.

- [ ] **Step 3: Implement in EntryEditor.jsp**
```js
<%-- Paste/drop upload. A placeholder line marks where the image will go;
     it is replaced by the shortcode on success and removed on refusal, and
     the refusal is shown on the status line (Task A8 styles it). Files go
     one at a time so a refusal is attributable to its file. --%>
function rollerUploadImages(files) {
    var queue = Array.prototype.slice.call(files);
    (function next() {
        var file = queue.shift();
        if (!file) { return; }
        var placeholder = '[uploading ' + file.name.replace(/[\[\]]/g, '') + '…]';
        rollerEditor.insert(placeholder + '\n');
        var form = new FormData();
        form.append('file', file, file.name);
        form.append('weblog', $("input[name='weblog']").val());
        form.append('${_csrf.parameterName}', '${_csrf.token}');
        fetch('<c:url value="/roller-ui/authoring/mediaFileAdd!upload.rol"/>', { method: 'POST', body: form, credentials: 'same-origin' })
            .then(function (r) { return r.json().then(function (body) { return { status: r.status, body: body }; }); })
            .then(function (res) {
                var result = res.body && res.body.results && res.body.results[0];
                var text = rollerGetEntryText();
                if (result && result.id) {
                    rollerSetEntryText(text.replace(placeholder, '[image id="' + result.id + '"]'));
                } else {
                    rollerSetEntryText(text.replace(placeholder + '\n', '').replace(placeholder, ''));
                    rollerStatusError((result && result.detail) || '<spring:message code="editor.uploadFailed" javaScriptEscape="true"/>');
                }
            })
            .catch(function () {
                rollerSetEntryText(rollerGetEntryText().replace(placeholder + '\n', '').replace(placeholder, ''));
                rollerStatusError('<spring:message code="editor.uploadFailed" javaScriptEscape="true"/>');
            })
            .then(next);
    })();
}

function rollerStatusError(message) {
    var status = document.getElementById('editorStatus');
    var el = document.createElement('span');
    el.className = 'is-error';
    el.textContent = message;
    status.appendChild(el);
    window.setTimeout(function () { el.remove(); }, 8000);
}
```
Pass `onUpload: rollerUploadImages` into `RollerEditor.create`. Keys: `editor.uploadFailed=The image could not be uploaded.` (all bundles).

- [ ] **Step 4: Run** `mvn verify -Pit -DskipUnitTests -Dit.test='EditorUploadIT,MarkdownPreviewIT,MediaBulkUploadIT'` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(editor): paste or drop an image and it becomes an [image] shortcode"`.

---

### Task A8: Writing guide and status line

**Files:**
- Modify: `EntryEditor.jsp` (offcanvas guide, status line updater), `EntryEditController.addEntryModelAttributes` (nothing new — `shortcodeCards` is already there)
- Test: `EntryEditorJspGuideTest` (source scan: the guide lists `${shortcodeCards}` via `c:forEach`, the keyboard table names Ctrl+S / Ctrl+Enter / Ctrl+/), `it-selenium` `ShortcodeCardIT` gains `theGuideListsEveryRegisteredShortcode` comparing offcanvas rows to `ShortcodeExpander.DEFAULT` names (the IT has access to the app's shortcode names through the Insert menu: assert the guide's `[data-shortcode]` set equals the menu's).

- [ ] **Step 1: Failing tests** (source-scan first: assert `EntryEditor.jsp` contains `id="editorGuide"` and `class="offcanvas`; IT as above).
- [ ] **Step 2: Run to fail.**
- [ ] **Step 3: Implement.** Markup after the media-chooser modal:
```jsp
<div class="offcanvas offcanvas-end editor-guide" tabindex="-1" id="editorGuide" aria-labelledby="editorGuideTitle">
    <div class="offcanvas-header">
        <p class="rail-group-label mb-0" id="editorGuideTitle"><spring:message code="editor.guide"/></p>
        <button type="button" class="btn-close" data-bs-dismiss="offcanvas" aria-label="<spring:message code='generic.close'/>"></button>
    </div>
    <div class="offcanvas-body">
        <p class="rail-group-label"><spring:message code="editor.guide.markdown"/></p>
        <table class="editor-guide-table">
            <tr><td><code># <spring:message code="editor.heading"/></code></td><td><spring:message code="editor.guide.h1"/></td></tr>
            <tr><td><code>**<spring:message code="editor.boldPlaceholder"/>**</code></td><td><spring:message code="editor.bold"/></td></tr>
            <tr><td><code>*<spring:message code="editor.italicPlaceholder"/>*</code></td><td><spring:message code="editor.italic"/></td></tr>
            <tr><td><code>[<spring:message code="editor.linkPlaceholder"/>](https://…)</code></td><td><spring:message code="editor.link"/></td></tr>
            <tr><td><code>- item</code></td><td><spring:message code="editor.bulletList"/></td></tr>
            <tr><td><code>1. item</code></td><td><spring:message code="editor.numberedList"/></td></tr>
            <tr><td><code>&gt; quote</code></td><td><spring:message code="editor.quote"/></td></tr>
            <tr><td><code>`code`</code></td><td><spring:message code="editor.code"/></td></tr>
            <tr><td><code>| a | b |</code></td><td><spring:message code="editor.table"/></td></tr>
        </table>
        <p class="rail-group-label"><spring:message code="editor.guide.shortcodes"/></p>
        <table class="editor-guide-table">
            <c:forEach items="${shortcodeCards}" var="card">
                <tr data-shortcode="<c:out value='${card.name}'/>"><td><code><c:out value="${card.snippet}"/></code></td><td><spring:message code="${card.labelKey}"/></td></tr>
            </c:forEach>
        </table>
        <p class="rail-group-label"><spring:message code="editor.guide.shortcuts"/></p>
        <table class="editor-guide-table">
            <tr><td><kbd>Ctrl</kbd>+<kbd>S</kbd></td><td><spring:message code="weblogEdit.save"/></td></tr>
            <tr><td><kbd>Ctrl</kbd>+<kbd>Enter</kbd></td><td><spring:message code="weblogEdit.post"/></td></tr>
            <tr><td><kbd>Ctrl</kbd>+<kbd>B</kbd> / <kbd>I</kbd></td><td><spring:message code="editor.bold"/> / <spring:message code="editor.italic"/></td></tr>
            <tr><td><kbd>Ctrl</kbd>+<kbd>F</kbd></td><td><spring:message code="editor.find"/></td></tr>
            <tr><td><kbd>Ctrl</kbd>+<kbd>/</kbd></td><td><spring:message code="editor.guide"/></td></tr>
        </table>
    </div>
</div>
```
JS: `window.rollerOpenGuide = function () { bootstrap.Offcanvas.getOrCreateInstance(document.getElementById('editorGuide')).show(); };`. Status line: `function rollerUpdateStatus() { var s = rollerEditor.stats(); document.getElementById('editorStatusWords').textContent = …; }` with two spans `#editorStatusWords` (`editor.status.words` = `{0} words · {1} min read`, arity pinned) and `#editorStatusSave` (`editor.status.unsaved` / `editor.status.savedLocally` / `editor.status.saved`), updated from the dirty flag and from `draftRecoveryBar`'s `roller-draft:saved` event — add `dispatch(bar, 'roller-draft:saved')` in `roller-draft.js`'s `save()` after `store.setItem` (roller-draft.js is A-owned for this line). CSS for `.editor-guide-table` (12px caps for labels, mono `code`, 14.5 body) in `roller-editor.css`. Keys: `editor.guide.markdown=Markdown`, `editor.guide.shortcodes=Shortcodes`, `editor.guide.shortcuts=Keyboard`, `editor.guide.h1=Heading`, `editor.find=Find in text`, `editor.status.words={0} words · {1} min read`, `editor.status.unsaved=Unsaved changes`, `editor.status.savedLocally=Draft saved locally`, `editor.status.saved=Saved`.
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='EntryEditorJspGuideTest,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest'`; `mvn verify -Pit -DskipUnitTests -Dit.test='ShortcodeCardIT,EntryAutosaveIT'` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(editor): in-app writing guide and a word-count/save-state status line"`.

---

### Task A9: Page editor on the writing-surface layout **(IT gate)**

**Files:**
- Modify: `PageEdit.jsp` (rebuild), `PageEditController` (add `previewShellURL`), `roller.css` lines 658–700 region only if a PageEdit-specific rule exists there (read the comment at 658 first)
- Test: `PageEditJspTest` (existing test that reads `PageEdit.jsp` for the `_showInNav` marker stays green; add: `editor-grid`, `editor-rail`, `sessionExpiryBar`, and that `bean.title`/`bean.slug` are `fn:escapeXml`-wrapped), `PageIT` (existing flows; add a split-preview assertion mirroring MarkdownPreviewIT).

- [ ] **Step 1: Failing source test**, **Step 2: run**, **Step 3: rebuild.** Layout:
```jsp
<div class="editor-grid">
<form id="pageEditForm" method="post" class="form-stacked editor-form" action="…pageEdit!save.rol">
  <input type="hidden" name="weblog" …/><input type="hidden" name="bean.id" …/>
  <div class="editor-main">
    <div id="sessionExpiryBar" …>  <%-- copy from EntryEdit.jsp verbatim --%>
    <div id="draftRecoveryBar" …>  <%-- existing --%>
    <input type="text" id="page_bean_title" name="bean.title" value="${fn:escapeXml(bean.title)}" maxlength="255" autofocus class="editor-title" placeholder="…" aria-label="…"/>
    <p class="editor-permalink"><span class="editor-slug-prefix">${urls.weblogAbsolute(actionWeblog)}</span><input type="text" id="page_bean_slug" name="bean.slug" value="${fn:escapeXml(bean.slug)}" maxlength="255" class="editor-slug"/> … copy control when published …</p>
    <%-- editor surface: the SAME markup as EntryEditor.jsp's #editorSurface block (textarea name="bean.content") --%>
  </div>
  <div class="editor-rail">
    <div class="editor-box"> PUBLISH: <select id="page_bean_status" …> + Save button (btn btn-primary, id="pageSaveButton") + Full preview link when published </div>
    <div class="editor-box"> NAVIGATION: _showInNav marker + checkbox, navOrder number </div>
    <div class="editor-box"> SEO drawer (existing SEO card body, unchanged ids) </div>
    <c:if test="${not empty bean.id}"><button type="button" class="delete-link" id="deletePageButton" data-page-id="…" data-page-title="${fn:escapeXml(bean.title)}">…</button></c:if>
  </div>
  <sec:csrfInput/>
</form>
</div>
```
Extract the `#editorSurface` toolbar/panes block from `EntryEditor.jsp` into `WEB-INF/jsps/editor/EditorSurface.jsp` taking the textarea name/value through request attributes (`editorFieldName`, `editorFieldValue`) and `jsp:include` it from both editors, so the markup is single-homed. The page-side script reuses `rollerSaveDraft`-style save via `#pageSaveButton`, the same `rollerSetEditorMode`/preview/upload functions (move those into `WEB-INF/jsps/editor/EditorScript.jsp`, included by both; it references `${previewShellURL}` and `${_csrf.*}` from request scope). Add the session-expiry script block from EntryEdit.jsp.
- [ ] **Step 4: Run** unit tests + `mvn verify -Pit -DskipUnitTests -Dit.test='PageIT,EntryAutosaveIT,MarkdownPreviewIT,AuthoringJourneyIT'` → PASS, then the full `-Pit`.
- [ ] **Step 5: Commit** — `git commit -m "feat(pages): the page editor shares the entry editor's writing surface and rail"`.

---

### Task A10: Rail fixes, Summary drawer, design card, docs

**Files:**
- Modify: `EntryEdit.jsp` (newsletter/revisions boxes → `.editor-box`, delete link last, `newsletter.noList` link as its own sentence), `EntryEditor.jsp` (Summary → `.editor-drawer`), `ApplicationResources*.properties` (`newsletter.noList.link=Open weblog settings`), `docs/design/design-system.md` (list the new card), CLAUDE.md
- Create: `docs/design/editor/editor-markdown-surface.html` (first line `<!-- @dsCard group="Editor" name="Markdown surface" subtitle="Toolbar, Write/Split/Preview, status line, guide" -->`, light + dark panels, no external requests)
- Test: `DesignCardsTest` (existing) must pass; `EntryEditJspTest` source scan: no `class="card` inside `.editor-rail`, `newsletter.noList` is followed by a separate `<a>` with `newsletter.noList.link`.

- [ ] Steps: failing scan → run → edit → `mvn -pl app test -Dtest='DesignCardsTest,EntryEditJspTest,MessageKeyTest'` → `mvn -pl app verify` (full quality gate) → commit `docs+feat(editor): rail boxes on one shape, summary drawer, the editor card and CLAUDE.md`.

CLAUDE.md edits: in "Entry editing", replace the **Editor** bullet with the CM6 description (bundle, `RollerEditor.create`, seam functions, theme-true shell via `PreviewServlet?shell=true` and `_preview`, paste upload endpoint, guide); delete the `ReferenceError: EasyMDE is not defined` flake paragraph under CI; in "Build and Development Commands" add the frontend build note with the A1 cold/warm timings and the Node-download caveat.

---

# Package B — Admin fit-and-finish sweep

Package B's source-scan test grows one method per task; create it in B1 and extend it after.

### Task B1: One status pill component

**Files:**
- Modify: `roller.css` (add `.status-pill*`, delete `.draftEntryBox/.pendingEntryBox/.scheduledEntryBox`, `td.*entry/tr.*entry` tints, the `.badge.bg-success/info/warning/danger` overrides — keep `.badge.bg-secondary`), `Entries.jsp` (legend, row classes, status cell), `Pages.jsp` (status cell), `Trash.jsp` (add a pill column? no — Trash rows are all trashed; skip), `EntryEdit.jsp` (`.editor-statusrow`), `Routes.java` if any marker names a removed class (grep first).
- Create: `app/src/test/java/org/apache/roller/weblogger/ui/JspConsistencyTest.java`

- [ ] **Step 1: Failing scan**
```java
package org.apache.roller.weblogger.ui;

/**
 * Source scans that pin the admin UI's shared vocabulary: one status-pill
 * component, three button buckets, one selection bar, one confirm idiom,
 * no jQuery UI, no sidebar h3+hr. Each method names the task that made it
 * true; a red method means the vocabulary drifted, not that a page broke.
 */
class JspConsistencyTest {
    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static Stream<Path> jsps() { … walk JSPS, *.jsp … }

    @Test
    void statusIsAlwaysAStatusPill() throws IOException {
        for (Path jsp : jsps().filter(p -> p.toString().contains("/editor/")).toList()) {
            String src = Files.readString(jsp);
            assertFalse(src.contains("badge bg-success") || src.contains("badge bg-info")
                    || src.contains("badge bg-warning") || src.contains("badge bg-danger")
                    || src.contains("badge bg-primary"), jsp + " uses a Bootstrap badge for status; use .status-pill");
            assertFalse(src.contains("EntryBox"), jsp + " renders the old status legend");
            assertFalse(src.contains("class=\"draftentry\"") || src.contains("class=\"pendingentry\""), jsp + " tints rows by status");
        }
        String css = Files.readString(Path.of("src/main/webapp/roller-ui/styles/roller.css"));
        for (String s : List.of("published", "draft", "pending", "scheduled", "trashed")) {
            assertTrue(css.contains(".status-pill.status-" + s), "roller.css lacks .status-pill.status-" + s);
        }
    }
}
```
- [ ] **Step 2: Run to fail.**
- [ ] **Step 3: Implement.** CSS (tokens only, per `components-pills.html`):
```css
/* ---- Status pills (components-pills card) ---- */
.status-pill { display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px; border-radius: 99px; font-family: var(--font-ui); font-size: 12px; font-weight: 600; letter-spacing: .04em; color: var(--ink); border: 1px solid transparent; white-space: nowrap; }
.status-pill .status-when { font-family: var(--font-data); font-weight: 450; font-variant-numeric: tabular-nums; color: var(--ink-soft); }
.status-pill.status-published { background: color-mix(in srgb, var(--good) 18%, var(--surface)); border-color: color-mix(in srgb, var(--good) 45%, var(--surface)); }
.status-pill.status-draft { background: color-mix(in srgb, var(--warn) 18%, var(--surface)); border-color: color-mix(in srgb, var(--warn) 45%, var(--surface)); }
.status-pill.status-pending { background: var(--accent-quiet); border-color: color-mix(in srgb, var(--accent) 40%, var(--surface)); }
.status-pill.status-scheduled { background: var(--paper); border-color: var(--line); }
.status-pill.status-trashed { background: color-mix(in srgb, var(--bad) 12%, var(--surface)); border-color: color-mix(in srgb, var(--bad) 35%, var(--surface)); }
.status-pill.status-unsaved { background: var(--paper); border-color: var(--line); color: var(--ink-soft); }
```
JSP: a single include `WEB-INF/jsps/editor/StatusPill.jsp` taking request attribute `pillStatus` (the `PubStatus` name) and optional `pillWhen` (a `Date`): renders `<span class="status-pill status-${fn:toLowerCase(pillStatus)}"><spring:message code="weblogEdit.${…}"/><c:if test="${not empty pillWhen}"> <span class="status-when"><roller:date value="${pillWhen}"/></span></c:if></span>` — until B5 adds `<roller:date>`, use `<fmt:formatDate value="${pillWhen}" pattern="yyyy-MM-dd HH:mm"/>`. Map `PUBLISHED→published`, `DRAFT→draft`, `PENDING→pending`, `SCHEDULED→scheduled`, `TRASHED→trashed`; the message keys are the existing `weblogEdit.published/draft/pending/scheduled` (add `weblogEdit.trashed=Trashed` to all bundles). Use it from `Entries.jsp` (status cell, Scheduled passes `pillWhen=post.pubTime`), `Pages.jsp`, and `EntryEdit.jsp`'s `.editor-statusrow` (`unsaved` when no status). Delete the legend `<p style="text-align:center">…` and the `<tr class="…entry">` branches (plain `<tr>`).
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='JspConsistencyTest,DesignTokenTest,MessageKeyTest'` → PASS; `grep -rn "EntryBox\|draftentry\|badge bg-" it-selenium/src/test/java` and fix any IT selector; `mvn verify -Pit -DskipUnitTests -Dit.test='BulkEntryActionsIT,PageIT,ScheduledEntryIT'`.
- [ ] **Step 5: Commit** — `git commit -m "feat(admin): one status-pill component replaces badges, legend and row tints"`.

---

### Task B2: Three button buckets

**Files:** every JSP with `btn-success` (`grep -rl btn-success app/src/main/webapp/WEB-INF/jsps`), `MediaFileView.jsp` (the `<input type="button">` action bar), `Members.jsp` (grant form as one inline row with `btn btn-secondary` "Add member"; table Save is the single `btn-primary`), `Pages.jsp` (hide the top button when `empty pages`), `roller.css` (the `.btn-success` alias rule can go once no JSP uses it; keep `.btn-primary`), `it-selenium` (any `btn-success` selector → `btn-primary`), `JspConsistencyTest`.

- [ ] **Step 1: Failing scan**
```java
@Test
void buttonsUseThreeBucketsOnly() throws IOException {
    for (Path jsp : jsps().toList()) {
        String src = Files.readString(jsp);
        assertFalse(src.contains("btn-success"), jsp + ": btn-success -> btn-primary");
        assertFalse(Pattern.compile("class=\"btn\"[^>]*>").matcher(src).find()
                && jsp.getFileName().toString().equals("MediaFileView.jsp"), jsp + ": bare .btn");
        assertFalse(src.contains("<input type=\"button\" class=\"btn"), jsp + ": <input type=button> as a button");
        assertFalse(src.contains("<input id=\"toggleButton\""), jsp + ": media action bar still inputs");
    }
    String pages = Files.readString(JSPS.resolve("editor/Pages.jsp"));
    assertTrue(pages.contains("<c:if test=\"${not empty pages}\">\n    <a href=\"${addUrl}\" class=\"btn btn-primary btn-sm\">") || pages.contains("not empty pages") , "Pages hides the top primary when empty");
    String members = Files.readString(JSPS.resolve("editor/Members.jsp"));
    assertEquals(1, countOccurrences(members, "btn btn-primary"), "Members has one primary");
}
```
- [ ] Steps 2–5: run/fail → edit (bare `btn` → `btn btn-secondary`; media bar → `<button type="button" class="btn btn-secondary" id="toggleButton">…` etc., destructive `btn btn-danger` only for Delete selected; ITs updated: `grep -rn "btn-success\|#toggleButton\|#deleteButton\|#moveButton\|#newEntryButton" it-selenium`) → `JspConsistencyTest` + `mvn verify -Pit -DskipUnitTests -Dit.test='GalleryIT,MediaCropIT,MultiUserJourneyIT,WeblogConfigMatrixIT,PageIT'` → commit `feat(admin): three button buckets, one primary per screen`.

---

### Task B3: One selection bar

**Files:** `roller.js` (delegated handler), `roller.css` (`.selection-bar`), `Entries.jsp`, `Submissions.jsp`, `Trash.jsp` (add checkboxes + Restore selected / Delete forever selected — controller support: `TrashController` gains `restoreSelected`/`deleteSelected` accepting `selectedEntries`, each looping `lookupEntry`… **if** `TrashController` already has bulk handlers; if not, Trash keeps per-row buttons and only Empty trash moves into the bar — decide by reading `TrashController` and record the choice in the commit message), `MediaFileView.jsp` (the action bar becomes the selection bar), `JspConsistencyTest`, ITs (`BulkEntryActionsIT`, `MediaBulkUploadIT`, `TrashIT`).

Markup contract:
```html
<div class="selection-bar" data-selection-bar="entriesBulkForm" hidden>
  <span class="selection-count" data-template="<spring:message code='selection.count'/>"></span>
  …action buttons (secondary), destructive last as .btn-danger or .delete-link…
</div>
```
`roller.js`:
```js
// Selection bars: shown while any checkbox in the form named by
// data-selection-bar is checked; the count text comes from the bar's own
// data-template ("{0} selected"), so the string stays in the bundle.
document.addEventListener('change', function (event) {
    var box = event.target;
    if (!(box instanceof HTMLInputElement) || box.type !== 'checkbox') { return; }
    var form = box.form;
    if (!form) { return; }
    var bar = document.querySelector('.selection-bar[data-selection-bar="' + form.id + '"]');
    if (!bar) { return; }
    var checked = form.querySelectorAll('input[type=checkbox]:checked:not([data-select-all])').length;
    bar.hidden = checked === 0;
    var count = bar.querySelector('.selection-count');
    if (count) { count.textContent = count.dataset.template.replace('{0}', checked); }
});
```
Key `selection.count={0} selected` (arity 1; no apostrophes). Scan: every `.selection-bar` has `data-selection-bar` naming a form id present in the same JSP; `Entries.jsp` has no `entriesBulkActions` div. IT gate: `mvn verify -Pit -DskipUnitTests -Dit.test='BulkEntryActionsIT,MediaBulkUploadIT,TrashIT,ContactFormIT'`. Commit `feat(admin): one selection bar for every list`.

---

### Task B4: Native date inputs; jQuery UI leaves

**Files:** `EntriesSidebar.jsp` (two inputs → `type="date"`, no `readonly`, no `datepicker()` script), `EntriesBean` (parse `yyyy-MM-dd`; keep accepting `MM/dd/yy` for a bookmarked URL — try ISO first), `head.jsp` (remove the two jquery-ui lines), `app/pom.xml` (remove `jquery-ui` dependency; keep `jquery-validation` and check its `jquery-patch.js` need — the pom comment says jquery-ui 1.14 ships `jquery-patch.js` for jQuery 4; verify `jquery-validation` still works without it by running `CreateWeblog` IT (`MultiUserJourneyIT`) — if it needs the patch, keep `jquery-ui`'s dependency but load only `jquery-patch.js`, and say so in the commit), `WebjarReferenceTest`, `JspConsistencyTest`.

- [ ] **Step 1: Failing unit test** in `EntriesBeanTest`:
```java
@Test
void isoDatesFromANativeDateInputParse() {
    EntriesBean bean = new EntriesBean();
    bean.setStartDateString("2026-09-01");
    assertEquals(LocalDate.of(2026, 9, 1), bean.getStartDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
}
@Test
void theOldSlashFormatStillParsesForBookmarkedUrls() { … "09/01/26" … }
```
Scan: no JSP contains `datepicker(`; `head.jsp` has no `jquery-ui`.
- [ ] Steps: run/fail → implement (`private static Date parseFilterDate(String s)` trying `DateTimeFormatter.ISO_LOCAL_DATE` then `MM/dd/yy`) → JSP → `mvn -pl app test -Dtest='EntriesBeanTest,JspConsistencyTest,WebjarReferenceTest'` → `mvn verify -Pit -DskipUnitTests -Dit.test='BulkEntryActionsIT,MultiUserJourneyIT,UserAdminIT'` → commit `feat(admin): native date filters; jQuery UI is gone`.

---

### Task B5: Entries list feel and the date tag

**Files:** `Entries.jsp`, `EntriesSidebar.jsp` (remove status radios; sort → `<select>`), `EntriesController` (chips carry `bean.text`/`bean.tagsAsString`/dates — extend `chipUrl` params; the controller's `filterParams` helper already builds the pager base URL, reuse it for the chips via a model attribute `filterQuery`), `WEB-INF/rollerConfig.tld` (+ `roller` tag `date`), `app/src/main/java/org/apache/roller/weblogger/ui/tags/DateTag.java` (renders `<time datetime="ISO" class="data">yyyy-MM-dd HH:mm</time>` in the weblog's or the user's timezone — use the request's `actionWeblog` timezone when present), every admin JSP with `fmt:formatDate` or `weblogEntryQuery.date.toStringFormat` (Entries, Trash, Submissions, EntryEdit's `.editor-when`, Pages), `roller.css` (`.data` mono + tabular-nums already; ensure `td.data`, `time.data`), `JspConsistencyTest`.

- [ ] Failing tests: `DateTagTest` (renders ISO `datetime` and the display pattern; null → empty); `EntriesJspTest` source scan: the title cell's first `<a` targets `entryEdit.rol`; a `View` link exists for published rows; no `weblogEntryQuery.date.toStringFormat` left in editor JSPs; `EntriesControllerTest`: the chip URLs contain `bean.text=` when the bean has text.
- [ ] Implementation notes: title cell becomes
```jsp
<td>
  <a class="entry-title" href="${editUrl}"><str:truncateNicely upper="80">${post.displayTitle}</str:truncateNicely></a>
  <div class="entry-meta"><span class="data">${post.anchor}</span>
    <c:if test="${post.status.name() == 'PUBLISHED'}"> &#183; <a class="quiet-link" href="${urls.entry(post)}" target="_blank" rel="noopener"><spring:message code="generic.view"/></a></c:if>
  </div>
</td>
```
Drop the pencil column. `generic.view=View` if absent. Sort select posts `bean.sortBy` on change (`onchange` is inline JS — use a `data-submit-on-change` attribute handled in `roller.js`).
- [ ] Run unit + `mvn verify -Pit -DskipUnitTests -Dit.test='BulkEntryActionsIT,DuplicateEntryIT,ScheduledEntryIT,TrashIT,ContactFormIT,RouteSweepIT'` → commit `feat(entries): title edits, chips carry every filter, one date tag`.

---

### Task B6: Sidebars on the rail's grammar

**Files:** `EntriesSidebar.jsp`, `MediaFileSidebar.jsp`, `CategoriesSidebar.jsp`, `TemplatesSidebar.jsp`, `MainMenuSidebar.jsp` (h3+icon headers → caps-labels), `roller.css` (`.sidebar-group`, `.sidebar-label`), `tiles-tabbedpage.jsp`/`tiles-mainmenupage.jsp` (the sidebar `<div class="card">` → `<aside class="sidebar">`), `Routes.java` (any sidebar markers), `JspConsistencyTest` (no `<h3>` without `class="section-head"` in `*Sidebar.jsp`; no `<hr/>` in sidebars).

Media sidebar specifics: "Add new folder" → one `.input-group` (text + `btn btn-secondary` Create, enabled-state script kept); search: Name, Type, `Larger than` (`bean.size` number + `bean.sizeUnit` select in one input-group; `bean.sizeFilterType` fixed to "greater" as a hidden input unless the bean supports only that — read `MediaFileSearchBean`), Tags, Search button `btn btn-primary` (the sidebar's one primary — the page's primary is "Add a photo"; acceptable because the sidebar is a separate form; note it in the scan's exemption list with the reason).

Run `mvn -pl app test -Dtest=JspConsistencyTest`, `mvn verify -Pit -DskipUnitTests -Dit.test='GalleryIT,CategoryIT,TemplateIT,RouteSweepIT'` → commit `feat(admin): sidebars use the rail's caps-label grammar`.

---

### Task B7: Modals in one shape; data-confirm everywhere

**Files:** the ten JSPs with modals (`grep -rl 'class="modal' app/src/main/webapp/WEB-INF/jsps`), `roller.css` (`.modal-title` → caps-label role; `.modal-footer` order), `Templates.jsp`, `MediaFileEdit.jsp`, `Members.jsp`, `Trash.jsp` (the `window.confirm` functions → `data-confirm` on the control/form; Members: the removal confirm depends on which radio is chosen — put `data-confirm` on the form and have `roller.js`'s submit handler honour a `data-confirm-when="input[value='-1']:checked"` selector attribute: prompt only if the form has a match), `JspConsistencyTest` (zero `confirm(` outside `roller.js`; every `.modal-title` is a `<p class="rail-group-label">` or carries `.modal-title-quiet`).

Run `mvn -pl app test -Dtest=JspConsistencyTest` → `mvn verify -Pit -DskipUnitTests -Dit.test='TemplateIT,MediaCropIT,MultiUserJourneyIT,TrashIT,CategoryIT,ContactFormIT,EntryRevisionIT'` → commit `feat(admin): one modal shape and data-confirm as the only confirm idiom`.

---

### Task B8: Errors point at fields

**Files:** `BaseController` (`addFieldError(Model, String field, String key, HttpServletRequest)` → adds the message via `addError` AND appends `field` to a `Set<String>` model attribute `invalidFields`), `messages.jsp` (unchanged), `roller.js` (on load: for each id in `document.body.dataset.invalidFields` split — rendered by `tiles-tabbedpage.jsp`/`tiles-simplepage.jsp` as `<body data-invalid-fields="${fn:join(invalidFields, ' ')}">` — add `.is-invalid` + `aria-invalid="true"`, focus the first), controllers: `WeblogConfigController.myValidate` (newsletter uuid → `weblog_bean_newsletterListUuid`; handle/email fields likewise), `CreateWeblogController`, `CategoriesController`/`CategoryEditController`, `PagesController`/`PageEditController` (slug), `TemplatesController`, `MembersController` (username), `UserEditController`/`ProfileController` (password confirm/email), `EntryEditController` (`entry_bean_pubTimeLocal`), `JspConsistencyTest` (both tiles layouts render `data-invalid-fields`).

- [ ] Failing tests: `BaseControllerTest.addFieldErrorRecordsTheField`; `WeblogConfigControllerTest.anInvalidListUuidNamesItsField` (model attribute `invalidFields` contains `weblog_bean_newsletterListUuid`); IT `WeblogConfigMatrixIT.anInvalidListUuidMarksAndFocusesTheField`: `$("#weblog_bean_newsletterListUuid").shouldHave(cssClass("is-invalid"))` and `Selenide.executeJavaScript("return document.activeElement.id")` equals it.
- Field ids: verify each named id exists in the JSP (`grep id="…"`) — the test for each controller asserts the id it names appears in its JSP (source read).
- Run unit + `mvn verify -Pit -DskipUnitTests -Dit.test='WeblogConfigMatrixIT,ErrorCasesIT,CategoryIT,PageIT'` → commit `feat(admin): validation errors mark and focus the field they name`.

---

### Task B9: Layout consistency (Inquiries rail, Profile rail, Global Config rail, Media add, Theme cards, Main menu rows)

Split into three commits inside one task:

**B9a — layouts.** `RollerViewResolver`: `.Submissions` → `.tiles-tabbedpage` with `menu` `/WEB-INF/jsps/tiles/empty.jsp` (copy `.Trash`'s attribute map); `.Profile` → `.tiles-mainmenupage`. `GlobalConfig.jsp`: wrap in `<form class="settings-grid form-stacked">` + `<aside class="settings-rail">` with a section index built from `globalConfigDef.displayGroups` (`<a href="#cfg-${dg.key}">`), `<h3 class="section-head" id="cfg-${dg.key}">`, checkboxes as `<div class="form-check"><input …><label …></div>` with the label beside, Save (`btn btn-primary w-100`) in the rail; keep `formChanged()` validation but attach through `data-validate` handled in the page script (no inline `onchange`). `Routes.java`: `/roller-ui/admin/globalConfig.rol` marker → `.settings-rail`; `/roller-ui/authoring/submissions.rol` marker unchanged (+ `#adminRail` present asserted by the rail smoke test which iterates tabbed routes — check how `adminRailIsPresent…` picks its route). Tests: `RollerViewResolverTest` (definitions), `RouteSweepIT`, `GlobalConfigMatrixIT`.

**B9b — Media add + Theme.** `MediaFileAdd.jsp`: drop zone first; description/copyright/tags in `<details class="editor-drawer-details"><summary class="editor-drawer">Details</summary>…</details>`; folder select stays above the drop zone. `ThemeDataServlet`: write JSON with `com.fasterxml.jackson.databind.ObjectMapper` (already on the classpath via Spring Boot) — `mapper.writeValue(pw, list or single map)`; unit test `ThemeDataServletTest.aDescriptionWithNewlineAndQuoteIsValidJson` (mock `ThemeManager` returning a `SharedTheme` whose description is `"line one\nline \"two\""`, parse the response with the same mapper). `ThemeEdit.jsp`: when `!customThemeAllowed && !customTheme`, omit the radio card row entirely; replace the `<select>` + description + thumbnail with a `.theme-cards` grid: one `<label class="theme-card">` per theme with `<input type="radio" name="selectedThemeId" value="${opt.id}">`, `<img src="${siteURL}/themes/${opt.id}/${opt.previewImagePath}" alt="">` (expose `previewImagePath` on the `themes` view objects — read `ThemeEditController` for what `themes` holds; `SharedTheme.getPreviewImage().getPath()` is the source), name (600), description (14.5 soft). The existing `proposeSharedThemeChange(id)` is called from a delegated `change` listener. `ThemeIT` and `ThemeMatrixIT` select by `#themeSelector` today — update `ThemeIT`'s helper to click `label.theme-card input[value='…']`. Tests: `ThemeDataServletTest`, `ThemeIT`, `ThemeMatrixIT`, `RouteSweepIT` (marker for `themeEdit.rol` → `.theme-cards`).

**B9c — Main menu.** `MainMenu.jsp`: each weblog is a `<div class="weblog-row">` — name (`h3.mm_weblog_name section-head`, marker kept), handle in `.data`, role sentence, and a `.weblog-actions` row of quiet links (`New entry` as the only `btn btn-primary btn-sm`, others `quiet-link`); no raw URL line (the name links to the weblog). `MultiUserJourneyIT`/`RouteSweepIT` green.

Commit each as `feat(admin): …`.

---

### Task B10: Weblog switcher in the top bar

**Files:** `BaseController.populateCommonModel` (add `userWeblogs`: `List<Weblog>` from `weblogger.getUserManager().getWeblogPermissions(user)` mapped to weblogs, sorted by handle, only when size > 1), `bannerStatus.jsp` (a `<div class="dropdown weblog-switcher">` after the brand, shown when `not empty userWeblogs`; each item links to the current `actionName` for that weblog when `actionName` starts with a weblog action, else `entries.rol`), `roller.css`, `BaseControllerTest`, `MultiUserJourneyIT` (`theSwitcherAppearsOnlyWithTwoWeblogsAndKeepsTheScreen`), `JspConsistencyTest` (bannerStatus has `weblog-switcher`).

Target URL rule: `<c:url value="/roller-ui/authoring/${fn:startsWith(actionName,'entry') or actionName == 'weblogConfig' ? 'entries' : actionName}.rol"><c:param name="weblog" value="${w.handle}"/></c:url>` — simplify: if the current action is one of the rail's items (`entries, trash, submissions, categories, pages, mediaFileView, themeEdit, weblogConfig, members`) keep it, else `entries`. Keys: `switcher.label=Switch weblog`.

Run unit + `mvn verify -Pit -DskipUnitTests -Dit.test='MultiUserJourneyIT,RouteSweepIT'` → commit `feat(admin): a quiet weblog switcher in the top bar`.

---

### Task B11: Small bugs, each failing-first

One commit per bullet; each has a unit test that fails before the fix.

- `MediaFileAddController.cancel`: `URLEncoder.encode(directoryId, UTF_8)`; test: a directory id containing `&` round-trips.
- `TemplatesController` line 251 / `MediaFileViewController` line 107: `StringEscapeUtils.escapeHtml4(...)` on the argument; tests assert the model error/message contains `&lt;` for a `<b>` name.
- `weblog.vm` line 1191: `#if($model.weblogCategory && $cat.name == $model.weblogCategory.name)` (mirror line 1158); rendering test: search results page with a category selected marks the right `<option selected>` and no NPE-style literal appears.
- `TemplateEdit.jsp`: the missing `</div>` — find it by counting per-block (29 `<div` vs 28 `</div>`), add it; test: a tag-balance scan over `TemplateEdit.jsp` (count `<div` == count `</div>`) added to `JspConsistencyTest` for all editor JSPs (allowlist none).
- Heading-level skips: the screens flagged by the prior sweep (`grep -n "<h4\|<h5" app/src/main/webapp/WEB-INF/jsps`): `h4.card-title` → `h3.section-head`, `h5#cropSectionTitle` → `h3`; `Routes.java` markers updated; scan: no `<h4`/`<h5` in the JSP tree.

Run `mvn -pl app test -Dtest='MediaFileAddControllerTest,TemplatesControllerTest,MediaFileViewControllerTest,JspConsistencyTest,*RenderingTest'` and `RouteSweepIT`.

---

### Task B12: Docs, full gates, package report

- CLAUDE.md "Admin UI": add bullets for status pills (`StatusPill.jsp`), the selection bar contract (`data-selection-bar`), `data-confirm` + `data-confirm-when`, `<roller:date>`, field errors (`addFieldError` + `data-invalid-fields`), the switcher; delete the sentence that says the two confirm idioms coexist.
- `mvn -pl app verify` (PMD/CPD/SpotBugs/JaCoCo) → BUILD SUCCESS.
- `mvn verify -Pit` full, then `mvn verify -Pit -Dit.context.path=roller`.
- `bin/check-diff-coverage.sh <base>` on the package's range; record the number.
- Write `docs/superpowers/reports/2026-09-09-package-b.md` (what shipped, gate outputs, anything deferred) in the main checkout. Commit `docs: package B invariants in CLAUDE.md`.

Package A does the same in A10 (report `2026-09-09-package-a.md`), plus `mvn verify -Pit -Dit.context.path=roller` because the preview shell and upload URL are context-path-sensitive.

---

## Merge and ship (top-level session only)

1. Verify both worktrees against the pinned base with the CLAUDE.md `comm` / `git merge-tree --write-tree` check.
2. Merge A into master (`git merge --no-ff`), run `mvn -pl app verify` and `mvn verify -Pit`.
3. Rebase B's bundle hunks if needed, merge B, run both again plus `-Dit.context.path=roller`.
4. `bin/check-diff-coverage.sh <base>` over the whole range; the expectation is ≥ 85% (new logic, not a mechanical sweep).
5. Update memory; do not push — report "ready to push".
