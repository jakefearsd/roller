/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code addFieldError} is {@code addError} plus the one extra fact the page
 * needs to point at the field that was refused: the DOM id of the control.
 *
 * <p>Two model attributes, deliberately: {@code invalidFields} is the ordered
 * set a test (or a future JSP) can ask questions of, and
 * {@code invalidFieldIds} is the space-joined string the three layouts render
 * into {@code <body data-invalid-fields>}. JSTL's {@code fn:join} only takes a
 * {@code String[]}, so the join happens here rather than in the page.
 */
class BaseControllerTest {

    private TestController controller;
    private Model model;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        // Nothing registered: getText falls back to the key, so the assertions
        // below read as message keys rather than English.
        controller.messageSource = new StaticMessageSource();
        model = new ExtendedModelMap();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.addPreferredLocale(Locale.US);
        request = mockRequest;
    }

    @Test
    void addFieldErrorRecordsTheFieldAndTheJoinedIds() {
        controller.error(model, "weblog_bean_newsletterListUuid",
                "websiteSettings.newsletterListUuid.invalid", request);

        assertEquals(List.of("websiteSettings.newsletterListUuid.invalid"), errors(model));
        assertEquals(List.of("weblog_bean_newsletterListUuid"), invalidFields(model));
        assertEquals("weblog_bean_newsletterListUuid", model.getAttribute("invalidFieldIds"));
        assertTrue(controller.errorsPresent(model),
                "A field error must satisfy hasErrors() exactly the way addError does");
    }

    @Test
    void severalFieldErrorsJoinInTheOrderTheyWereAdded() {
        controller.error(model, "handle", "createWeblog.error.invalidHandle", request);
        controller.error(model, "emailAddress", "error.add.user.badEmail", request);

        assertEquals(List.of("handle", "emailAddress"), invalidFields(model));
        assertEquals("handle emailAddress", model.getAttribute("invalidFieldIds"));
    }

    @Test
    void theSameFieldNamedTwiceIsMarkedOnce() {
        controller.error(model, "handle", "createWeblog.error.invalidHandle", request);
        controller.error(model, "handle", "createWeblog.error.handleExists", request);

        assertEquals(List.of("handle"), invalidFields(model),
                "invalidFields is a set: two complaints about one control still mark it once");
        assertEquals("handle", model.getAttribute("invalidFieldIds"));
        assertEquals(2, errors(model).size(),
                "...but both messages are still shown, exactly as addError would");
    }

    @Test
    void theArgumentCarryingOverloadStillFormatsItsMessage() {
        controller.registerMessage("categoryForm.error.duplicateName", "Already have a {0}");

        controller.error(model, "category_bean_name", "categoryForm.error.duplicateName",
                new Object[]{"Travel"}, request);

        assertEquals(List.of("Already have a Travel"), errors(model));
        assertEquals(List.of("category_bean_name"), invalidFields(model));
    }

    @Test
    void aModelWithNoFieldErrorsCarriesNeitherAttribute() {
        controller.plainError(model, "generic.error.check.logs", request);

        assertTrue(controller.errorsPresent(model));
        assertNull(model.getAttribute("invalidFields"),
                "addError must not start naming fields it was never given");
        assertNull(model.getAttribute("invalidFieldIds"));
    }

    @Test
    void hasErrorsIsUnchangedOnAnUntouchedModel() {
        assertFalse(controller.errorsPresent(model));
    }

    @SuppressWarnings("unchecked")
    private static List<String> errors(Model model) {
        return (List<String>) model.getAttribute("errors");
    }

    @SuppressWarnings("unchecked")
    private static List<String> invalidFields(Model model) {
        Collection<String> fields = (Collection<String>) model.getAttribute("invalidFields");
        return fields == null ? null : List.copyOf(fields);
    }

    /**
     * The protected helpers are only reachable from a subclass, and this test
     * lives in the controllers package so it can be one.
     */
    private static final class TestController extends BaseController {

        void error(Model model, String fieldId, String key, HttpServletRequest request) {
            addFieldError(model, fieldId, key, request);
        }

        void error(Model model, String fieldId, String key, Object[] args, HttpServletRequest request) {
            addFieldError(model, fieldId, key, args, request);
        }

        void plainError(Model model, String key, HttpServletRequest request) {
            addError(model, key, request);
        }

        boolean errorsPresent(Model model) {
            return hasErrors(model);
        }

        void registerMessage(String key, String pattern) {
            ((StaticMessageSource) messageSource).addMessage(key, Locale.US, pattern);
        }
    }
}
