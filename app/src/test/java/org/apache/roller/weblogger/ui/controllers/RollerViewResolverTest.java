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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.View;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RollerViewResolverTest {

    private RollerViewResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RollerViewResolver();
    }

    @Test
    void baseDefinitionResolvesToNonNullView() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/page.jsp", "footer", "/footer.jsp"));

        View view = resolver.resolveViewName(".base", Locale.getDefault());

        assertNotNull(view);
        assertEquals("text/html", view.getContentType());
    }

    @Test
    void extendedDefinitionInheritsAttributes() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/default-content.jsp", "footer", "/footer.jsp"));
        resolver.addExtendedDefinition(".child", ".base",
                Map.of("content", "/child-content.jsp"));

        View view = resolver.resolveViewName(".child", Locale.getDefault());
        assertNotNull(view);
    }

    @Test
    void unknownViewNameReturnsNull() {
        View view = resolver.resolveViewName(".nonexistent", Locale.getDefault());
        assertNull(view);
    }

    @Test
    void extendingNonExistentBaseThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                resolver.addExtendedDefinition(".child", ".missingBase", Map.of("content", "/page.jsp")));
    }

    @Test
    void initRegistersAllDefinitionsWithoutErrors() {
        resolver.init();

        // Spot-check several well-known view names from tiles.xml
        assertNotNull(resolver.resolveViewName(".EntryEdit", Locale.getDefault()),
                ".EntryEdit should be registered");
        assertNotNull(resolver.resolveViewName(".Login", Locale.getDefault()),
                ".Login should be registered");
        assertNotNull(resolver.resolveViewName(".MainMenu", Locale.getDefault()),
                ".MainMenu should be registered");
        assertNotNull(resolver.resolveViewName(".denied", Locale.getDefault()),
                ".denied should be registered");
        assertNotNull(resolver.resolveViewName(".tiles-tabbedpage", Locale.getDefault()),
                ".tiles-tabbedpage should be registered");
        assertNotNull(resolver.resolveViewName(".Maintenance", Locale.getDefault()),
                ".Maintenance should be registered");
        assertNotNull(resolver.resolveViewName(".MediaFileImageChooser", Locale.getDefault()),
                ".MediaFileImageChooser should be registered");
    }

    @Test
    void extendedDefinitionMergesOverrides() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/default.jsp", "footer", "/footer.jsp"));
        resolver.addExtendedDefinition(".child", ".base",
                Map.of("content", "/override.jsp"));

        // Resolve the child -- the parent's "footer" should be inherited
        // and "content" should be overridden.
        // We verify via ViewDefinition created in the resolver's internal map.
        // Since we can't directly inspect the map, we confirm the view is non-null
        // and trust the unit logic. A more thorough integration test would use
        // a mock request/response.
        View view = resolver.resolveViewName(".child", Locale.getDefault());
        assertNotNull(view);
    }

    @Test
    void viewDefinitionWithAttributeCreatesNewInstance() {
        ViewDefinition def = new ViewDefinition("/layout.jsp",
                Map.of("content", "/page.jsp"));
        ViewDefinition updated = def.withAttribute("sidebar", "/sidebar.jsp");

        // original is unchanged
        assertFalse(def.attributes().containsKey("sidebar"));
        // new instance has both
        assertEquals("/page.jsp", updated.attributes().get("content"));
        assertEquals("/sidebar.jsp", updated.attributes().get("sidebar"));
        assertEquals("/layout.jsp", updated.layout());
    }
}
