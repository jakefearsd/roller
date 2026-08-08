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
package org.apache.roller.weblogger.util;

import java.util.Arrays;
import java.util.List;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.startup.MockMailProvider;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Roller actually puts in the mail it sends.
 *
 * <p>Mail is optional to configure and so was easy to leave uncovered, but when
 * it is configured it carries the things a user cannot get any other way: the
 * link in a password reset, who an invitation came from, what a comment
 * notification is about. A mistake here is invisible to every other test in the
 * suite and shows up as an email nobody can act on.
 *
 * <p>The provider is installed through {@link MockMailProvider}, which records
 * messages instead of sending them. The session is a real JavaMail one so the
 * headers asserted below are genuinely the headers that would go out.
 */
class MailUtilTest {

    private MockMailProvider mail;
    private Weblog weblog;
    private User invitee;

    @BeforeEach
    void setUp() {
        mail = MockMailProvider.install();

        weblog = new Weblog();
        weblog.setHandle("mailblog");
        weblog.setName("Mail Blog");
        weblog.setEmailAddress("owner@example.invalid");
        weblog.setLocale("en_US");

        invitee = new User();
        invitee.setUserName("dana");
        invitee.setScreenName("Dana D");
        invitee.setEmailAddress("dana@example.invalid");
    }

    @AfterEach
    void tearDown() {
        MockMailProvider.uninstall();
    }

    // ------------------------------------------------------- configured or not

    @Test
    void mailIsReportedConfiguredOnlyWhenAProviderIsInstalled() {
        assertTrue(MailUtil.isMailConfigured(),
                "a provider is installed for this test");

        MockMailProvider.uninstall();
        assertFalse(MailUtil.isMailConfigured(),
                "callers branch on this before composing anything; with no provider "
                        + "it must say so rather than fail later");
        mail = MockMailProvider.install();
    }

    /**
     * Sending with no provider is a no-op, not a crash. Every caller checks
     * {@code isMailConfigured} first, but the low-level send is public and an
     * installation without mail must not break because something forgot.
     */
    @Test
    void sendingWithNoProviderDoesNothingQuietly() throws Exception {
        MockMailProvider.uninstall();
        try {
            MailUtil.sendTextMessage("from@example.invalid",
                    new String[]{"to@example.invalid"}, null, null, "subject", "body");
        } finally {
            mail = MockMailProvider.install();
        }
    }

    /**
     * The invitation is the one message that throws rather than returning
     * quietly when mail is unconfigured -- the caller has told a user their
     * invitation was sent, so silently dropping it would be worse than failing.
     */
    @Test
    void anInvitationWithNoProviderFailsLoudlyRatherThanSilently() {
        MockMailProvider.uninstall();
        try {
            assertThrows(WebloggerException.class,
                    () -> MailUtil.sendWeblogInvitation(weblog, invitee));
        } finally {
            mail = MockMailProvider.install();
        }
    }

    // ------------------------------------------------------------ addressing

    @Test
    void aMessageCarriesItsFromToCcAndSubject() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"first@example.invalid", "second@example.invalid"},
                new String[]{"copied@example.invalid"},
                new String[]{"blind@example.invalid"},
                "A subject line", "The body.");

        MimeMessage message = mail.onlyMessage();
        assertEquals("A subject line", message.getSubject());
        assertEquals("sender@example.invalid", message.getFrom()[0].toString());
        assertEquals(List.of("first@example.invalid", "second@example.invalid"),
                addresses(message, Message.RecipientType.TO));
        assertEquals(List.of("copied@example.invalid"),
                addresses(message, Message.RecipientType.CC));
    }

    /**
     * A blind copy must not appear in the headers the other recipients receive.
     * That is the whole point of it, and it is one {@code setRecipients} call
     * away from being wrong.
     */
    @Test
    void aBlindCopyIsNotVisibleInTheToOrCcHeaders() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"visible@example.invalid"}, null,
                new String[]{"hidden@example.invalid"},
                "subject", "body");

        MimeMessage message = mail.onlyMessage();
        assertFalse(addresses(message, Message.RecipientType.TO).contains("hidden@example.invalid"),
                "a bcc must not be in To");
        assertFalse(addresses(message, Message.RecipientType.CC).contains("hidden@example.invalid"),
                "nor in Cc");
    }

    /**
     * The transport is still asked to deliver to the blind recipient -- hidden
     * from the headers, not dropped.
     */
    @Test
    void aBlindCopyIsStillDelivered() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"visible@example.invalid"}, null,
                new String[]{"hidden@example.invalid"},
                "subject", "body");

        List<String> delivered = Arrays.stream(mail.recipients().get(0))
                .map(Object::toString).toList();
        assertTrue(delivered.contains("hidden@example.invalid"),
                "the bcc must still be delivered to: " + delivered);
    }

    // -------------------------------------------------------------- content

    @Test
    void aTextMessageIsSentAsPlainTextAndAnHtmlOneAsHtml() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "plain", "just words");
        assertTrue(mail.onlyMessage().getContentType().startsWith("text/plain"),
                "got: " + mail.onlyMessage().getContentType());

        MockMailProvider.uninstall();
        mail = MockMailProvider.install();

        MailUtil.sendHTMLMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "rich", "<p>markup</p>");
        assertTrue(mail.onlyMessage().getContentType().startsWith("text/html"),
                "got: " + mail.onlyMessage().getContentType());
    }

    @Test
    void theBodyReachesTheMessageIntact() throws Exception {
        MailUtil.sendTextMessage("sender@example.invalid",
                new String[]{"to@example.invalid"}, null, null,
                "subject", "Line one.\nLine two.");

        assertEquals("Line one.\nLine two.", mail.onlyMessage().getContent().toString());
    }

    // ----------------------------------------------------------- reply-to

    /**
     * A contact-form notification is sent as the weblog, but a reply must
     * reach the submitter, not the weblog's own from address. This is the
     * one path in the whole class that reaches the private widened
     * {@code sendMessage} with a non-null {@code replyTo}.
     */
    @Test
    void aReplyToOverloadSetsTheHeaderWhenProvided() throws Exception {
        MailUtil.sendTextMessage("owner@example.invalid", "submitter@example.invalid",
                new String[]{"owner@example.invalid"}, "subject", "body");

        MimeMessage message = mail.onlyMessage();
        assertEquals(List.of("submitter@example.invalid"), replyToAddresses(message));
    }

    /**
     * Every pre-existing overload keeps delegating with a null replyTo, so
     * none of them must start emitting a Reply-To header just because the
     * private method underneath grew a parameter.
     */
    @Test
    void existingOverloadsSetNoReplyToHeader() throws Exception {
        MailUtil.sendTextMessage("owner@example.invalid",
                new String[]{"to@example.invalid"}, null, null, "subject", "body");

        assertEquals(List.of(), replyToAddresses(mail.onlyMessage()));
    }

    // ----------------------------------------------------------- invitations

    /**
     * An invitation goes to the invited user and copies the weblog's own
     * address, so the owner has a record of who they invited.
     */
    @Test
    void anInvitationIsAddressedToTheInviteeAndCopiesTheWeblog() throws Exception {
        MailUtil.sendWeblogInvitation(weblog, invitee);

        MimeMessage message = mail.onlyMessage();
        assertEquals(List.of("dana@example.invalid"),
                addresses(message, Message.RecipientType.TO));
        assertEquals(List.of("owner@example.invalid"),
                addresses(message, Message.RecipientType.CC));
        assertEquals("owner@example.invalid", message.getFrom()[0].toString());
    }

    /**
     * The invitation has to say which weblog it is for and give the recipient
     * somewhere to go. An invitation naming neither is one a user cannot act
     * on, which is indistinguishable from not sending it.
     */
    @Test
    void anInvitationNamesTheWeblogAndLinksSomewhereToAcceptIt() throws Exception {
        MailUtil.sendWeblogInvitation(weblog, invitee);

        String subject = mail.onlyMessage().getSubject();
        String body = mail.onlyMessage().getContent().toString();

        assertTrue(subject.contains("Mail Blog") || subject.contains("mailblog"),
                "the subject must identify the weblog: " + subject);
        assertTrue(body.contains("mailblog") || body.contains("Mail Blog"),
                "and so must the body: " + body);
        assertTrue(body.contains("menu.rol"),
                "the body must point the recipient at somewhere to accept: " + body);
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> addresses(MimeMessage message, Message.RecipientType type)
            throws Exception {
        if (message.getRecipients(type) == null) {
            return List.of();
        }
        return Arrays.stream(message.getRecipients(type)).map(Object::toString).toList();
    }

    private static List<String> replyToAddresses(MimeMessage message) throws Exception {
        if (message.getHeader("Reply-To") == null) {
            return List.of();
        }
        return Arrays.stream(message.getReplyTo()).map(Object::toString).toList();
    }
}
