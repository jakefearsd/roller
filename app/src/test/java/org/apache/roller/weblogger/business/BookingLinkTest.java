/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.pojos.Business;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The blog's booking link: resolution order and UTM tagging. */
class BookingLinkTest {

    private static Weblog weblog(String own, String business) {
        Weblog w = new Weblog();
        w.setHandle("stay");
        w.setBookingUrl(own);
        if (business != null) {
            Business b = new Business();
            b.setBookingUrl(business);
            w.setBusiness(b);
        }
        return w;
    }

    @Test
    void theWeblogsOwnUrlWinsOverTheBusinesss() {
        assertEquals("https://own.example/b",
                BookingLink.resolve(weblog("https://own.example/b", "https://biz.example/b")));
    }

    @Test
    void theBusinessUrlIsTheFallback() {
        assertEquals("https://biz.example/b",
                BookingLink.resolve(weblog(null, "https://biz.example/b")));
        assertEquals("https://biz.example/b",
                BookingLink.resolve(weblog("  ", "https://biz.example/b")));
    }

    @Test
    void nothingConfiguredResolvesToNull() {
        assertNull(BookingLink.resolve(weblog(null, null)));
        assertNull(BookingLink.resolve(weblog(null, "")));
        assertNull(BookingLink.resolve(null));
    }

    @Test
    void utmGoesBeforeTheFragmentAndNeverDuplicatesAnExistingParameter() {
        assertEquals("https://b.example/x?ref=a&utm_source=keep&utm_medium=blog&utm_campaign=s#dates",
                BookingLink.withUtmParams("https://b.example/x?ref=a&utm_source=keep#dates",
                        weblog(null, null), "s"));
    }

    @Test
    void destHostDropsUserinfoPortPathAndCase() {
        assertEquals("book.example.com",
                BookingLink.destHost("https://User@Book.Example.com:8443/p?q=1"));
        assertNull(BookingLink.destHost("not a url"));
    }
}
