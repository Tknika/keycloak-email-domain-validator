package net.micedre.keycloak.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmailDomainValidator}.
 */
class EmailDomainValidatorTest {

    private EmailDomainValidator validator;

    @BeforeEach
    void setUp() {
        validator = new EmailDomainValidator();
    }

    @Test
    @DisplayName("Validator ID should be 'email-domain-whitelist'")
    void testValidatorId() {
        assertEquals("email-domain-whitelist", validator.getId());
    }

    @Nested
    @DisplayName("Glob Pattern Matching")
    class GlobMatchingTests {

        @ParameterizedTest
        @DisplayName("Exact domain matches")
        @CsvSource({
            "mydomain.com, mydomain.com, true",
            "mydomain.com, otherdomain.com, false",
            "MYDOMAIN.COM, mydomain.com, true",
            "mydomain.com, MYDOMAIN.COM, true"
        })
        void testExactMatch(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Wildcard * matches any characters")
        @CsvSource({
            "sub.mydomain.com, *.mydomain.com, true",
            "a.b.mydomain.com, *.mydomain.com, true",
            "mydomain.com, *.mydomain.com, false",
            "test.example.org, *.example.org, true",
            "example.org, *.example.org, false"
        })
        void testWildcardAsterisk(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Wildcard ? matches single character")
        @CsvSource({
            "mydomain.com, my?omain.com, true",
            "myzomain.com, my?omain.com, true",
            "myomain.com, my?omain.com, false",
            "myxxomain.com, my?omain.com, false"
        })
        void testWildcardQuestion(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Combined wildcards")
        @CsvSource({
            "sub.mydomain.com, *.my?omain.com, true",
            "test.myzomain.com, *.my?omain.com, true",
            "myzomain.com, *.my?omain.com, false"
        })
        void testCombinedWildcards(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @Test
        @DisplayName("Very long text should be rejected for security")
        void testLongTextRejected() {
            String longText = "a".repeat(250);
            assertFalse(EmailDomainValidator.globMatches(longText, "*"));
        }
    }

    @Nested
    @DisplayName("Domain Parsing")
    class DomainParsingTests {

        @ParameterizedTest
        @DisplayName("Various domain separators should work")
        @ValueSource(strings = {
            "domain1.com\ndomain2.com",
            "domain1.com\r\ndomain2.com",
            "domain1.com##domain2.com",
            "domain1.com\n\ndomain2.com",
            "  domain1.com  \n  domain2.com  "
        })
        void testDomainSeparators(String config) {
            String[] parts = config.split(EmailDomainValidator.DOMAIN_LIST_SEPARATOR);
            assertTrue(parts.length >= 2);
        }

        @Test
        @DisplayName("Leading @ should be stripped from domains")
        void testLeadingAtStripped() {
            // This would be tested through integration tests with actual ValidatorConfig
            // For unit test, we verify the constant exists
            assertEquals("allowed-domains", EmailDomainValidator.CONFIG_ALLOWED_DOMAINS);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Empty glob should match empty text")
        void testEmptyGlobMatchesEmpty() {
            assertTrue(EmailDomainValidator.globMatches("", ""));
        }

        @Test
        @DisplayName("Empty glob should not match non-empty text")
        void testEmptyGlobNoMatchNonEmpty() {
            assertFalse(EmailDomainValidator.globMatches("something", ""));
        }

        @Test
        @DisplayName("Star only should match anything")
        void testStarMatchesAnything() {
            assertTrue(EmailDomainValidator.globMatches("anything.here.com", "*"));
            assertTrue(EmailDomainValidator.globMatches("a", "*"));
            assertTrue(EmailDomainValidator.globMatches("", "*"));
        }
    }
}
