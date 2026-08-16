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
 * limitations under the License.
 */
package org.apache.roller.weblogger.ui.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pins that Roller cannot be configured to store a plaintext password.
 *
 * <p>Four paths could once do it: {@code passwds.encryption.enabled=false}
 * (which flipped the encoding id to {@code noop}), the unconditional
 * {@code noop} encoder registration (so a {@code {noop}} value authenticated
 * however the flag was set), {@code lazyUpgradeFrom=plaintext} (a no-op encoder
 * on the NULL prefix, so an unprefixed string authenticated), and the config
 * lines that turned the first one on in dev and in the unit-test JVM.
 *
 * <p>Deleting the capability does not prevent its return; this is the guard
 * that does. Same role as {@code ProductionComposeTest} and
 * {@code DesignTokenTest}.
 */
class PasswordEncodingTest {

    /** Tests run with the app module as the working directory. */
    private static Path repoRoot() {
        Path candidate = Paths.get("..");
        return Files.isDirectory(candidate.resolve("bin/db")) ? candidate : Paths.get(".");
    }

    @Test
    void theBuiltEncoderNeverReturnsItsInput() {
        PasswordEncoder encoder = RollerContext.createPasswordEncoder();
        String raw = "a-plaintext-password";
        String encoded = encoder.encode(raw);
        assertFalse(encoded.contains(raw),
                "the encoder emitted its own input: " + encoded);
        assertTrue(encoder.matches(raw, encoded),
                "the encoder cannot verify what it just encoded");
    }

    /**
     * A {@code {noop}} row does not merely fail to match -- Spring refuses the
     * unknown id outright, which is the stronger outcome. Either result means
     * "does not authenticate"; this asserts the one that actually happens, so
     * the test fails loudly if the encoder is ever re-registered.
     */
    @Test
    void aNoopStoredValueDoesNotAuthenticate() {
        PasswordEncoder encoder = RollerContext.createPasswordEncoder();
        assertThrows(IllegalArgumentException.class,
                () -> encoder.matches("secret", "{noop}secret"),
                "a {noop} stored value was accepted for matching -- the noop encoder is registered again");
    }

    @Test
    void anExplicitlySetEncryptionFlagIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> RollerContext.rejectRemovedEncryptionFlag("false"),
                "an explicitly-set passwds.encryption.enabled must fail loudly");
        assertThrows(IllegalStateException.class,
                () -> RollerContext.rejectRemovedEncryptionFlag("true"),
                "even =true must be rejected: the property no longer exists");
        RollerContext.rejectRemovedEncryptionFlag(null); // absent: must not throw
    }

    @Test
    void noPropertiesFileMentionsTheRemovedFlagOrPlaintextUpgrade() throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot())) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".properties"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> {
                        String body = read(p);
                        return body.contains("passwds.encryption.enabled")
                                || body.contains("lazyUpgradeFrom=plaintext");
                    })
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertTrue(offenders.isEmpty(),
                    "properties files still reference the removed plaintext path: " + offenders);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + p, e);
        }
    }
}
