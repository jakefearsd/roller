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
package org.apache.roller.weblogger.pojos;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Place coordinates are kept to two decimals (about 1 km), HALF_UP. */
class WeblogPlaceTest {

    @Test
    void latitudeIsRoundedToTwoDecimals() {
        Weblog w = new Weblog();
        w.setPlaceLat(new BigDecimal("38.71234"));
        assertEquals(new BigDecimal("38.71"), w.getPlaceLat());
    }

    @Test
    void longitudeRoundsHalfUpAwayFromZero() {
        Weblog w = new Weblog();
        w.setPlaceLng(new BigDecimal("-9.145"));
        assertEquals(new BigDecimal("-9.15"), w.getPlaceLng());
    }

    @Test
    void nullStaysNull() {
        Weblog w = new Weblog();
        w.setPlaceLat(new BigDecimal("1.234"));
        w.setPlaceLat(null);
        w.setPlaceLng(null);
        assertNull(w.getPlaceLat());
        assertNull(w.getPlaceLng());
    }
}
