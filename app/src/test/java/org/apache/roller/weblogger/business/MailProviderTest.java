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
package org.apache.roller.weblogger.business;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.spi.InitialContextFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MailProvider}: where its mail session comes from (a JNDI lookup, or
 * built from {@code mail.*} startup properties), which credentials it
 * presents to the SMTP server, and that it fails startup loudly -- a
 * {@link StartupException} -- when it cannot get a session or cannot connect.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>The SMTP server is a minimal in-process fake on a loopback port that
 * records every command it receives; nothing leaves the machine.
 *
 * <p><b>JVM-global state, and why the default session is primed.</b>
 * {@code properties} mode builds its session with
 * {@code Session.getDefaultInstance}, which returns the FIRST default session
 * the JVM created and ignores the properties of every later call. The test
 * therefore primes that default session itself, pointing at the fake server,
 * so the result does not depend on which test runs first; the assertions are
 * about what goes over the wire, not the session's properties.
 */
class MailProviderTest {

    private static final String[] MAIL_KEYS = {
        "mail.configurationType", "mail.jndi.name", "mail.hostname",
        "mail.port", "mail.username", "mail.password"};

    private static FakeSmtpServer smtp;

    private final Map<String, String> savedConfig = new HashMap<>();

    @BeforeAll
    static void startFakeSmtpServer() throws IOException {
        smtp = FakeSmtpServer.start();
        Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.host", "127.0.0.1");
        defaults.setProperty("mail.smtp.port", Integer.toString(smtp.port()));
        Session.getDefaultInstance(defaults, null);
    }

    @AfterAll
    static void stopFakeSmtpServer() throws IOException {
        smtp.close();
    }

    @BeforeEach
    void saveMailConfig() throws Exception {
        for (String key : MAIL_KEYS) {
            savedConfig.put(key, config().getProperty(key));
        }
        smtp.commands.clear();
    }

    @AfterEach
    void restoreMailConfig() throws Exception {
        for (String key : MAIL_KEYS) {
            setConfig(key, savedConfig.get(key));
        }
    }

    // ------------------------------------------------------ properties mode

    @Test
    void propertiesModeWithCredentialsAndAPortAuthenticatesWithThem() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", "s3cret");

        new MailProvider();

        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials(),
                "the configured username and password must be presented to the server");
    }

    /**
     * No {@code mail.port}: the credentials are still presented, on the
     * session's port rather than an explicit one.
     */
    @Test
    void propertiesModeWithCredentialsButNoPortStillAuthenticates() throws Exception {
        propertiesMode("127.0.0.1", null, "mailer", "s3cret");

        new MailProvider();

        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials());
    }

    /** A username without a password is not a credential: connect anonymously. */
    @Test
    void propertiesModeWithAUsernameButNoPasswordConnectsWithoutAuthenticating() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", null);

        new MailProvider();

        assertTrue(smtp.commands.stream().anyMatch(c -> c.startsWith("EHLO")),
                "the provider must have connected: " + smtp.commands);
        assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("AUTH")),
                "no AUTH without a password: " + smtp.commands);
    }

    /** A non-numeric {@code mail.port} is ignored, not a startup failure. */
    @Test
    void aNonNumericPortIsIgnoredRatherThanFailingStartup() throws Exception {
        propertiesMode("127.0.0.1", "twenty-five", "mailer", "s3cret");

        new MailProvider();

        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials());
    }

    /**
     * Connecting is done at construction so a misconfigured relay fails
     * startup, not the first password-reset mail.
     */
    @Test
    void anUnreachableMailServerFailsStartup() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        propertiesMode("127.0.0.1", Integer.toString(closedPort), "mailer", "s3cret");

        StartupException ex = assertThrows(StartupException.class, MailProvider::new);

        assertEquals("ERROR connecting to mail server", ex.getMessage());
        assertInstanceOf(MessagingException.class, ex.getCause());
    }

    /** {@code getTransport()} hands back a new, connected transport each call. */
    @Test
    void getTransportReturnsAConnectedTransportForTheCaller() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", "s3cret");
        MailProvider provider = new MailProvider();
        smtp.commands.clear();

        try (Transport transport = provider.getTransport()) {
            assertTrue(transport.isConnected());
        }
        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials());
    }

    // ------------------------------------------------------------ JNDI mode

    /**
     * The shipped default: the session is whatever the container binds at
     * {@code java:comp/env/mail/Session} (a relative name gets that prefix),
     * used as-is, and connected with its own settings.
     */
    @Test
    void jndiModeUsesTheContainersSessionUnderTheCompEnvPrefix() throws Exception {
        Session containerSession = Session.getInstance(sessionPointingAtFakeServer());
        jndiMode("mail/Session");

        MailProvider provider = withJndi(Map.of("java:comp/env/mail/Session", containerSession),
                MailProvider::new);

        assertSame(containerSession, provider.getSession());
        assertTrue(smtp.commands.stream().anyMatch(c -> c.startsWith("EHLO")),
                "the provider must connect at startup: " + smtp.commands);
    }

    /** A name already in the {@code java:} namespace is looked up verbatim. */
    @Test
    void jndiModeLooksUpAJavaNamespaceNameVerbatim() throws Exception {
        Session containerSession = Session.getInstance(sessionPointingAtFakeServer());
        jndiMode("java:/mail/Relay");

        MailProvider provider = withJndi(Map.of("java:/mail/Relay", containerSession),
                MailProvider::new);

        assertSame(containerSession, provider.getSession());
    }

    @Test
    void jndiModeWithNothingBoundFailsStartupNamingTheFullName() throws Exception {
        jndiMode("mail/Missing");

        StartupException ex = assertThrows(StartupException.class,
                () -> withJndi(Map.of(), MailProvider::new));

        assertEquals("ERROR looking up mail-session with JNDI name: java:comp/env/mail/Missing",
                ex.getMessage());
        assertInstanceOf(NameNotFoundException.class, ex.getCause());
    }

    // ------------------------------------------------------------- fixtures

    private void propertiesMode(String host, String port, String user, String password)
            throws Exception {
        setConfig("mail.configurationType", "properties");
        setConfig("mail.hostname", host);
        setConfig("mail.port", port);
        setConfig("mail.username", user);
        setConfig("mail.password", password);
    }

    private void jndiMode(String name) throws Exception {
        setConfig("mail.configurationType", "jndi");
        setConfig("mail.jndi.name", name);
        setConfig("mail.port", null);
        setConfig("mail.username", null);
        setConfig("mail.password", null);
    }

    private static Properties sessionPointingAtFakeServer() {
        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.host", "127.0.0.1");
        props.setProperty("mail.smtp.port", Integer.toString(smtp.port()));
        return props;
    }

    /**
     * Runs {@code body} with {@code java.naming.factory.initial} naming
     * {@link BindingsContextFactory} over {@code bindings}, restoring the
     * system property afterwards.
     */
    private static <T> T withJndi(Map<String, Object> bindings, ThrowingSupplier<T> body)
            throws Exception {
        String previous = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        BindingsContextFactory.bindings = bindings;
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, BindingsContextFactory.class.getName());
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
            } else {
                System.setProperty(Context.INITIAL_CONTEXT_FACTORY, previous);
            }
            BindingsContextFactory.bindings = Map.of();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** A JNDI provider that answers lookups from a fixed map. */
    public static class BindingsContextFactory implements InitialContextFactory {
        static volatile Map<String, Object> bindings = Map.of();

        @Override
        public Context getInitialContext(Hashtable<?, ?> environment) {
            Context context = mock(Context.class);
            try {
                when(context.lookup(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    Object bound = bindings.get(name);
                    if (bound == null) {
                        throw new NameNotFoundException(name);
                    }
                    return bound;
                });
            } catch (javax.naming.NamingException e) {
                throw new IllegalStateException(e);
            }
            return context;
        }
    }

    private static Properties config() throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static void setConfig(String key, String value) throws Exception {
        if (value == null) {
            config().remove(key);
        } else {
            config().setProperty(key, value);
        }
    }

    /**
     * Just enough ESMTP to satisfy a JavaMail connect: greets, advertises
     * {@code AUTH PLAIN}, accepts any credentials and records every command.
     */
    private static final class FakeSmtpServer implements AutoCloseable {
        final List<String> commands = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final Thread acceptor;

        private FakeSmtpServer(ServerSocket server) {
            this.server = server;
            this.acceptor = new Thread(this::acceptLoop, "fake-smtp");
            this.acceptor.setDaemon(true);
        }

        static FakeSmtpServer start() throws IOException {
            FakeSmtpServer fake = new FakeSmtpServer(
                    new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            fake.acceptor.start();
            return fake;
        }

        int port() {
            return server.getLocalPort();
        }

        /**
         * {@code user:password} from the last SASL PLAIN response
         * ({@code authzid NUL authcid NUL passwd}), or null if none was sent.
         */
        String presentedPlainCredentials() {
            String encoded = null;
            for (int i = 0; i < commands.size(); i++) {
                String command = commands.get(i);
                if (command.startsWith("AUTH PLAIN")) {
                    String[] parts = command.split(" ");
                    encoded = parts.length > 2 ? parts[2]
                            : (i + 1 < commands.size() ? commands.get(i + 1) : null);
                }
            }
            if (encoded == null) {
                return null;
            }
            String[] fields = new String(Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8).split("\0", -1);
            return fields[1] + ":" + fields[2];
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> converse(socket), "fake-smtp-session");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void converse(Socket socket) {
            try (socket;
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                 PrintWriter out = new PrintWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
                reply(out, "220 fake ESMTP");
                boolean awaitingPlainResponse = false;
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line);
                    if (awaitingPlainResponse) {
                        awaitingPlainResponse = false;
                        reply(out, "235 accepted");
                    } else if (line.startsWith("EHLO")) {
                        out.print("250-fake\r\n");
                        reply(out, "250 AUTH PLAIN");
                    } else if (line.startsWith("HELO")) {
                        reply(out, "250 fake");
                    } else if (line.startsWith("AUTH PLAIN")) {
                        if (line.split(" ").length > 2) {
                            reply(out, "235 accepted");
                        } else {
                            awaitingPlainResponse = true;
                            reply(out, "334 ");
                        }
                    } else if (line.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        return;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException ignored) {
                // the client hung up; nothing to record
            }
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
