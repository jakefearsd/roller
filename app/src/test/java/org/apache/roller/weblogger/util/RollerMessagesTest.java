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

package org.apache.roller.weblogger.util;

import org.apache.roller.weblogger.util.RollerMessages.RollerMessage;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the message collector passed through comment validation and mailing.
 *
 * <p>Errors and status messages are kept in separate lists on purpose: the
 * comment pipeline decides whether to reject a comment by asking for the error
 * count, so a message landing in the wrong list changes behaviour rather than
 * just wording.
 */
public class RollerMessagesTest {

    @Test
    public void aFreshCollectorHasNothingInIt() {
        RollerMessages messages = new RollerMessages();
        assertEquals(0, messages.getErrorCount());
        assertEquals(0, messages.getMessageCount());
        assertFalse(messages.getErrors().hasNext());
        assertFalse(messages.getMessages().hasNext());
        assertEquals("", messages.toString());
    }

    @Test
    public void errorsAndMessagesAreCountedSeparately() {
        // Comment moderation branches on getErrorCount(); a status message
        // must never push a comment into the rejected pile.
        RollerMessages messages = new RollerMessages();
        messages.addError("error.one");
        messages.addMessage("status.one");
        messages.addMessage("status.two");

        assertEquals(1, messages.getErrorCount());
        assertEquals(2, messages.getMessageCount());
    }

    @Test
    public void aKeyWithNoArgumentsCarriesNullArguments() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.key");

        RollerMessage message = messages.getErrors().next();
        assertEquals("error.key", message.getKey());
        assertNull(message.getArgs(),
                "A message added without arguments must carry null args; an empty array "
                        + "would make MessageFormat substitute nothing where the caller "
                        + "expected the raw text.");
    }

    @Test
    public void aSingleArgumentIsWrappedIntoAOneElementArray() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.key", "arg");
        messages.addMessage("status.key", "arg");

        assertArrayEquals(new String[]{"arg"}, messages.getErrors().next().getArgs());
        assertArrayEquals(new String[]{"arg"}, messages.getMessages().next().getArgs());
    }

    @Test
    public void anArgumentArrayIsKeptAsGiven() {
        RollerMessages messages = new RollerMessages();
        messages.addError("error.key", new String[]{"a", "b"});
        messages.addMessage("status.key", new String[]{"c", "d"});

        assertArrayEquals(new String[]{"a", "b"}, messages.getErrors().next().getArgs());
        assertArrayEquals(new String[]{"c", "d"}, messages.getMessages().next().getArgs());
    }

    @Test
    public void messagesComeBackInTheOrderTheyWereAdded() {
        // The order is what the reader sees on the page.
        RollerMessages messages = new RollerMessages();
        messages.addMessage("first");
        messages.addMessage("second");

        Iterator<RollerMessage> it = messages.getMessages();
        assertEquals("first", it.next().getKey());
        assertEquals("second", it.next().getKey());
        assertFalse(it.hasNext());
    }

    @Test
    public void toStringListsStatusMessagesBeforeErrors() {
        // This string ends up in the log when a comment is rejected, so the
        // keys have to all be there and in a predictable order.
        RollerMessages messages = new RollerMessages();
        messages.addError("error.one");
        messages.addMessage("status.one");

        assertEquals("status.one : error.one : ", messages.toString());
    }

    @Test
    public void aMessageCanBeRewrittenInPlace() {
        // The setters exist for callers that adjust a message after adding it;
        // if they stopped working the change would be silently dropped.
        RollerMessage message = new RollerMessage("original.key", new String[]{"a"});
        message.setKey("replacement.key");
        message.setArgs(new String[]{"b"});

        assertEquals("replacement.key", message.getKey());
        assertArrayEquals(new String[]{"b"}, message.getArgs());
    }

    @Test
    public void twoCollectorsDoNotShareTheirLists() {
        // The lists are instance state; a static slip here would leak one
        // request's validation errors into another's.
        RollerMessages first = new RollerMessages();
        RollerMessages second = new RollerMessages();
        first.addError("only.on.first");

        assertEquals(0, second.getErrorCount());
        assertTrue(second.toString().isEmpty());
    }
}
