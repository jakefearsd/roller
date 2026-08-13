/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

/*
 * Local draft recovery for the entry and page editors.
 *
 * Deliberately local: see docs/superpowers/specs/2026-08-13-w3-autosave-design.md.
 * The short version is that a server-side autosave would multiply
 * weblogentry_revision rows by the autosave rate against a retention default of
 * -1 (keep everything), and would have to invent a real entry row for anything
 * anyone started typing. The work that actually gets lost -- tab crash, wrong
 * tab closed, session expired while the laptop slept -- is all lost in the same
 * browser it was typed in, which is exactly what localStorage covers.
 *
 * Used in: EntryEdit.jsp, PageEdit.jsp. Loaded as a plain static script rather
 * than a JSP include (unlike ajax-user.js) because nothing here needs JSP
 * interpolation -- every translated string arrives through data- attributes on
 * the bar element.
 */
(function (window, document) {
    'use strict';

    var PREFIX = 'roller.draft.v1:';
    var DEBOUNCE_MS = 2000;
    var MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

    /*
     * localStorage is not merely "might be full": reading window.localStorage
     * at all throws when storage is blocked (some enterprise policies, some
     * private-browsing modes). Probe it once, and treat any failure as "this
     * page has no autosave" rather than letting it break the editor.
     */
    function openStorage() {
        try {
            var store = window.localStorage;
            var probe = PREFIX + 'probe';
            store.setItem(probe, '1');
            store.removeItem(probe);
            return store;
        } catch (e) {
            return null;
        }
    }

    /*
     * Structural fields, by name. Not by type="hidden": bean.featuredImageId
     * and bean.ogImageId are hidden inputs carrying real author choices made
     * through the image pickers. bean.status is deliberately absent -- on
     * PageEdit it is a visible <select> the author sets, and on EntryEdit the
     * submit buttons' formaction decides the status regardless of the field.
     */
    function isStructural(el, csrfName) {
        if (!el.name) {
            return true;
        }
        if (el.type === 'file' || el.type === 'submit'
                || el.type === 'button' || el.type === 'reset') {
            return true;
        }
        return el.name === csrfName || el.name === 'bean.id' || el.name === 'weblog';
    }

    /*
     * Radios share a name, so keying by name alone would let the last one in
     * the group overwrite the rest.
     */
    function fieldKey(el) {
        return el.type === 'radio' ? el.name + '=' + el.value : el.name;
    }

    function readFields(form, csrfName) {
        var fields = {};
        var els = form.elements;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (isStructural(el, csrfName)) {
                continue;
            }
            fields[fieldKey(el)] =
                (el.type === 'checkbox' || el.type === 'radio') ? !!el.checked : el.value;
        }
        return fields;
    }

    function writeFields(form, csrfName, fields) {
        var els = form.elements;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (isStructural(el, csrfName)) {
                continue;
            }
            var key = fieldKey(el);
            if (!Object.prototype.hasOwnProperty.call(fields, key)) {
                continue;
            }
            if (el.type === 'checkbox' || el.type === 'radio') {
                el.checked = !!fields[key];
            } else {
                el.value = fields[key];
            }
        }
    }

    /*
     * Content comparison, not timestamps. A timestamp comparison against the
     * entry's updateTime has to reconcile a browser clock, a server clock and
     * the weblog's timezone, and is wrong whenever any of the three drift.
     * Either the browser is holding something the server is not, or it is not.
     */
    function differs(stored, current) {
        if (stored.text !== current.text) {
            return true;
        }
        var seen = {};
        var keys = Object.keys(stored.fields).concat(Object.keys(current.fields));
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (seen[key]) {
                continue;
            }
            seen[key] = true;
            if (String(stored.fields[key]) !== String(current.fields[key])) {
                return true;
            }
        }
        return false;
    }

    function readSnapshot(store, key) {
        var snapshot;
        try {
            var raw = store.getItem(key);
            if (!raw) {
                return null;
            }
            snapshot = JSON.parse(raw);
        } catch (e) {
            return null;
        }
        if (!snapshot || !snapshot.fields || typeof snapshot.text !== 'string') {
            return null;
        }
        return snapshot;
    }

    /*
     * Without this, every draft ever abandoned on this installation stays in
     * the browser forever. Best-effort: housekeeping must never be the reason
     * the editor fails to load.
     */
    function sweep(store) {
        try {
            var now = new Date().getTime();
            var doomed = [];
            for (var i = 0; i < store.length; i++) {
                var key = store.key(i);
                if (!key || key.indexOf(PREFIX) !== 0) {
                    continue;
                }
                var snapshot = readSnapshot(store, key);
                if (!snapshot || typeof snapshot.at !== 'number'
                        || (now - snapshot.at) > MAX_AGE_MS) {
                    doomed.push(key);
                }
            }
            for (var j = 0; j < doomed.length; j++) {
                store.removeItem(doomed[j]);
            }
        } catch (e) {
            /* nothing to do */
        }
    }

    function install(options) {
        if (!options || !options.form || !options.key
                || typeof options.getText !== 'function') {
            return;
        }
        var store = openStorage();
        if (!store) {
            return;
        }

        var form = options.form;
        var key = options.key;
        var csrfName = options.csrfName || '_csrf';
        var bar = options.bar || null;
        var timer = null;
        var stopped = false;

        sweep(store);

        function current() {
            return { at: 0, fields: readFields(form, csrfName), text: options.getText() };
        }

        function drop(which) {
            try {
                store.removeItem(which);
            } catch (e) {
                /* nothing to do */
            }
        }

        function save() {
            if (stopped) {
                return;
            }
            var snapshot = current();
            snapshot.at = new Date().getTime();
            try {
                store.setItem(key, JSON.stringify(snapshot));
            } catch (e) {
                // Quota exhausted mid-session. Stop trying rather than
                // throwing on every keystroke; the editor matters more than
                // the safety net.
                stopped = true;
            }
        }

        function schedule() {
            if (timer) {
                window.clearTimeout(timer);
            }
            timer = window.setTimeout(save, DEBOUNCE_MS);
        }

        function offer(snapshot) {
            if (!bar) {
                return;
            }
            var label = bar.querySelector('.draft-bar-text');
            var restoreButton = bar.querySelector('.draft-bar-restore');
            var discardButton = bar.querySelector('.draft-bar-discard');
            if (!label || !restoreButton || !discardButton) {
                return;
            }

            var when = new Date(snapshot.at || new Date().getTime());
            label.textContent = (label.getAttribute('data-template') || '')
                    .replace('{0}', when.toLocaleString());

            restoreButton.addEventListener('click', function () {
                writeFields(form, csrfName, snapshot.fields);
                if (typeof options.setText === 'function') {
                    options.setText(snapshot.text);
                }
                // Restoring hands the text back; it deliberately does NOT
                // save. What to do with recovered work is the author's call.
                bar.textContent = bar.getAttribute('data-restored') || '';
                drop(key);
            });

            discardButton.addEventListener('click', function () {
                drop(key);
                bar.hidden = true;
            });

            bar.hidden = false;
        }

        var now = current();

        /*
         * A snapshot that matches what the server just rendered is one whose
         * save went through: drop it silently. That single rule is also how a
         * finished "new entry" draft gets cleaned up -- saving a new entry
         * redirects to entryEdit under a DIFFERENT key, so the entryAdd:new
         * snapshot would otherwise linger for 30 days and be offered to
         * whoever started the next entry. EntryEdit/PageEdit pass it in
         * staleKeys for exactly that reason.
         */
        var keys = [key].concat(options.staleKeys || []);
        var offerable = null;
        for (var i = 0; i < keys.length; i++) {
            var snapshot = readSnapshot(store, keys[i]);
            if (!snapshot || !differs(snapshot, now)) {
                drop(keys[i]);
            } else if (keys[i] === key) {
                offerable = snapshot;
            }
        }
        if (offerable) {
            offer(offerable);
        }

        form.addEventListener('input', schedule, true);
        form.addEventListener('change', schedule, true);
        if (typeof options.onEditorChange === 'function') {
            options.onEditorChange(schedule);
        }

        /*
         * Save on submit rather than clearing. Clearing here would delete the
         * draft in the one case it is most needed: an expired session turns
         * the POST into a redirect to the login page, and everything typed
         * since the last save would be gone. Saving instead costs nothing --
         * a submit that succeeds comes back rendering the saved values, and
         * the matching snapshot is dropped on that load by the rule above.
         */
        form.addEventListener('submit', function () {
            if (timer) {
                window.clearTimeout(timer);
                timer = null;
            }
            save();
        });
    }

    window.rollerDraft = { install: install };

}(window, document));
