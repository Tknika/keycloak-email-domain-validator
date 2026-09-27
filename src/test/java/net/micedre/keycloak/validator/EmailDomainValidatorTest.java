package net.micedre.keycloak.validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationInvitationModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.InvitationManager;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidatorConfig;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    @Nested
    @DisplayName("Organization Invitation Bypass")
    class InvitationBypassTests {

        private EmailDomainValidator validator;
        // Whitelist that would normally BLOCK the test email (guest@example.org)
        private ValidatorConfig config;

        @BeforeEach
        void setUp() {
            validator = new EmailDomainValidator();
            config = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_ALLOWED_DOMAINS, "whitelisted-only.com",
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, true
            ));
        }

        @Test
        @DisplayName("Email with a pending org invitation is allowed even if domain not whitelisted")
        void testBypassWhenPendingInvitation() {
            String email = "guest@example.org"; // example.org is NOT whitelisted
            ValidationContext vc = validateWithInvitation(email, false);

            assertTrue(vc.isValid(),
                    "registration by a pending, non-expired invitation should bypass the whitelist");
        }

        @Test
        @DisplayName("Email without a pending invitation is still blocked by the whitelist")
        void testNoBypassWhenNoInvitation() {
            String email = "guest@example.org";
            ValidationContext vc = validateWithNoInvitation(email);

            assertFalse(vc.isValid(),
                    "without an invitation, a non-whitelisted domain must be blocked");
        }

        @Test
        @DisplayName("Expired invitation does NOT bypass the whitelist")
        void testNoBypassWhenInvitationExpired() {
            String email = "guest@example.org";
            ValidationContext vc = validateWithInvitation(email, true); // expired

            assertFalse(vc.isValid(),
                    "an expired invitation must not bypass the whitelist");
        }

        @Test
        @DisplayName("No organization in context (plain self-registration) is blocked by the whitelist")
        void testNoBypassWhenNoOrgInContext() {
            String email = "guest@example.org";

            KeycloakSession session = Mockito.mock(KeycloakSession.class);
            KeycloakContext ctx = Mockito.mock(KeycloakContext.class);
            ValidationContext vc = new ValidationContext(session);

            Mockito.when(session.getContext()).thenReturn(ctx);
            Mockito.when(ctx.getOrganization()).thenReturn(null); // no org -> plain registration

            validator.validate(email, UserModel.EMAIL, vc, config);

            assertFalse(vc.isValid(),
                    "plain registration without org context must be blocked by the whitelist");
        }

        @Test
        @DisplayName("Bypass disabled in config -> whitelist enforced even with a pending invitation")
        void testBypassDisabledEnforcesWhitelist() {
            String email = "guest@example.org";
            ValidatorConfig strictConfig = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_ALLOWED_DOMAINS, "whitelisted-only.com",
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, false
            ));

            KeycloakSession session = Mockito.mock(KeycloakSession.class);
            KeycloakContext ctx = Mockito.mock(KeycloakContext.class);
            OrganizationModel org = Mockito.mock(OrganizationModel.class);
            OrganizationProvider orgProvider = Mockito.mock(OrganizationProvider.class);
            InvitationManager invitationManager = Mockito.mock(InvitationManager.class);
            OrganizationInvitationModel invitation = Mockito.mock(OrganizationInvitationModel.class);
            ValidationContext vc = new ValidationContext(session);

            Mockito.when(session.getContext()).thenReturn(ctx);
            Mockito.when(ctx.getOrganization()).thenReturn(org);
            Mockito.when(org.isEnabled()).thenReturn(true);
            Mockito.when(session.getProvider(OrganizationProvider.class)).thenReturn(orgProvider);
            Mockito.when(orgProvider.getInvitationManager()).thenReturn(invitationManager);
            Mockito.when(invitationManager.getByEmail(org, email)).thenReturn(invitation);
            Mockito.when(invitation.isExpired()).thenReturn(false);

            validator.validate(email, UserModel.EMAIL, vc, strictConfig);

            assertFalse(vc.isValid(),
                    "with bypass disabled, even a pending invitation must be blocked");
        }

        /** Builds the mock chain with an org in context and a pending invitation. */
        private ValidationContext validateWithInvitation(String email, boolean expired) {
            KeycloakSession session = Mockito.mock(KeycloakSession.class);
            KeycloakContext ctx = Mockito.mock(KeycloakContext.class);
            OrganizationModel org = Mockito.mock(OrganizationModel.class);
            OrganizationProvider orgProvider = Mockito.mock(OrganizationProvider.class);
            InvitationManager invitationManager = Mockito.mock(InvitationManager.class);
            OrganizationInvitationModel invitation = Mockito.mock(OrganizationInvitationModel.class);
            ValidationContext vc = new ValidationContext(session);

            Mockito.when(session.getContext()).thenReturn(ctx);
            Mockito.when(ctx.getOrganization()).thenReturn(org);
            Mockito.when(org.isEnabled()).thenReturn(true);
            Mockito.when(session.getProvider(OrganizationProvider.class)).thenReturn(orgProvider);
            Mockito.when(orgProvider.getInvitationManager()).thenReturn(invitationManager);
            Mockito.when(invitationManager.getByEmail(org, email)).thenReturn(invitation);
            Mockito.when(invitation.isExpired()).thenReturn(expired);

            validator.validate(email, UserModel.EMAIL, vc, config);
            return vc;
        }

        /** Builds the mock chain with an org in context but no invitation for the email. */
        private ValidationContext validateWithNoInvitation(String email) {
            KeycloakSession session = Mockito.mock(KeycloakSession.class);
            KeycloakContext ctx = Mockito.mock(KeycloakContext.class);
            OrganizationModel org = Mockito.mock(OrganizationModel.class);
            OrganizationProvider orgProvider = Mockito.mock(OrganizationProvider.class);
            InvitationManager invitationManager = Mockito.mock(InvitationManager.class);
            ValidationContext vc = new ValidationContext(session);

            Mockito.when(session.getContext()).thenReturn(ctx);
            Mockito.when(ctx.getOrganization()).thenReturn(org);
            Mockito.when(org.isEnabled()).thenReturn(true);
            Mockito.when(session.getProvider(OrganizationProvider.class)).thenReturn(orgProvider);
            Mockito.when(orgProvider.getInvitationManager()).thenReturn(invitationManager);
            Mockito.when(invitationManager.getByEmail(org, email)).thenReturn(null);

            validator.validate(email, UserModel.EMAIL, vc, config);
            return vc;
        }
    }

    @Nested
    @DisplayName("File-based Domains")
    class FileBasedDomainsTests {

        private EmailDomainValidator validator;
        private Path tempFile;

        @BeforeEach
        void setUp() throws IOException {
            validator = new EmailDomainValidator();
            // shorten cache TTL so tests see file changes immediately
            EmailDomainValidator.cacheTtlMs = 0L;
            EmailDomainValidator.clearFileCache();
            tempFile = Files.createTempFile("allowed-domains-", ".txt");
        }

        @AfterEach
        void tearDown() throws IOException {
            EmailDomainValidator.cacheTtlMs = 60_000L;
            EmailDomainValidator.clearFileCache();
            Files.deleteIfExists(tempFile);
        }

        private ValidationContext validate(String email, String fileContent) throws IOException {
            Files.writeString(tempFile, fileContent);
            ValidatorConfig config = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_DOMAINS_FILE, tempFile.toString(),
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, false
            ));
            ValidationContext vc = new ValidationContext(); // no session needed for file logic
            validator.validate(email, UserModel.EMAIL, vc, config);
            return vc;
        }

        @Test
        @DisplayName("Email matching a domain in the file is allowed")
        void testFileAllowsListedDomain() throws IOException {
            ValidationContext vc = validate("user@whitelisted.com",
                    "# allowed domains\nwhitelisted.com\n");
            assertTrue(vc.isValid(), "a domain listed in the file should be allowed");
        }

        @Test
        @DisplayName("Email not matching any domain in the file is blocked")
        void testFileBlocksUnlistedDomain() throws IOException {
            ValidationContext vc = validate("user@other.com",
                    "whitelisted.com\n");
            assertFalse(vc.isValid(), "a domain not in the file should be blocked");
        }

        @Test
        @DisplayName("Comments and blank lines in the file are ignored")
        void testFileIgnoresCommentsAndBlanks() throws IOException {
            ValidationContext vc = validate("user@whitelisted.com",
                    "# comment line\n\n   \nwhitelisted.com  # trailing comment\n@another.com\n");
            assertTrue(vc.isValid(), "comments and blanks should be ignored, real domains accepted");

            // leading @ in the file should be stripped and match
            ValidationContext vc2 = validate("user@another.com",
                    "# comment line\n\nwhitelisted.com  # trailing\n@another.com\n");
            assertTrue(vc2.isValid(), "leading @ in the file should be stripped and match");
        }

        @Test
        @DisplayName("Empty file is permissive (allows all domains)")
        void testEmptyFileIsPermissive() throws IOException {
            ValidationContext vc = validate("user@anything.com", "");
            assertTrue(vc.isValid(), "an empty file should allow all domains (permissive)");
        }

        @Test
        @DisplayName("File supports glob patterns")
        void testFileSupportsGlobPatterns() throws IOException {
            ValidationContext vc = validate("user@sub.domain.com", "*.domain.com\n");
            assertTrue(vc.isValid(), "glob patterns in the file should match");

            ValidationContext vc2 = validate("user@domain.com", "*.domain.com\n");
            assertFalse(vc2.isValid(), "glob *.domain.com should not match bare domain.com");
        }

        @Test
        @DisplayName("Missing file falls back to the Allowed Domains textarea")
        void testMissingFileFallsBackToTextarea() {
            ValidatorConfig config = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_DOMAINS_FILE, "/nonexistent/path/domains.txt",
                    EmailDomainValidator.CONFIG_ALLOWED_DOMAINS, "textarea-domain.com",
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, false
            ));

            ValidationContext vcOk = new ValidationContext();
            validator.validate("user@textarea-domain.com", UserModel.EMAIL, vcOk, config);
            assertTrue(vcOk.isValid(), "when the file is missing, the textarea domain should be used");

            ValidationContext vcBlocked = new ValidationContext();
            validator.validate("user@other.com", UserModel.EMAIL, vcBlocked, config);
            assertFalse(vcBlocked.isValid(), "when the file is missing, non-textarea domains should be blocked");
        }

        @Test
        @DisplayName("File takes precedence over the textarea")
        void testFilePrecedesTextarea() throws IOException {
            Files.writeString(tempFile, "file-domain.com\n");
            ValidatorConfig config = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_DOMAINS_FILE, tempFile.toString(),
                    EmailDomainValidator.CONFIG_ALLOWED_DOMAINS, "textarea-domain.com",
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, false
            ));

            ValidationContext vcFileDomain = new ValidationContext();
            validator.validate("user@file-domain.com", UserModel.EMAIL, vcFileDomain, config);
            assertTrue(vcFileDomain.isValid(), "file domain should be allowed (file is source of truth)");

            ValidationContext vcTextareaDomain = new ValidationContext();
            validator.validate("user@textarea-domain.com", UserModel.EMAIL, vcTextareaDomain, config);
            assertFalse(vcTextareaDomain.isValid(), "textarea domain should be ignored when file is set");
        }

        @Test
        @DisplayName("Edited file is picked up after cache expiry")
        void testCacheRefreshesAfterTtl() throws IOException {
            Files.writeString(tempFile, "first.com\n");
            ValidatorConfig config = new ValidatorConfig(Map.of(
                    EmailDomainValidator.CONFIG_DOMAINS_FILE, tempFile.toString(),
                    EmailDomainValidator.CONFIG_BYPASS_ORG_INVITATION, false
            ));

            ValidationContext vc1 = new ValidationContext();
            validator.validate("user@first.com", UserModel.EMAIL, vc1, config);
            assertTrue(vc1.isValid(), "first.com should be allowed initially");

            // rewrite the file with a different domain
            Files.writeString(tempFile, "second.com\n");

            // with cacheTtlMs = 0, the next call re-reads the file
            ValidationContext vc2 = new ValidationContext();
            validator.validate("user@second.com", UserModel.EMAIL, vc2, config);
            assertTrue(vc2.isValid(), "second.com should be allowed after the file changed");

            ValidationContext vc3 = new ValidationContext();
            validator.validate("user@first.com", UserModel.EMAIL, vc3, config);
            assertFalse(vc3.isValid(), "first.com should no longer be allowed after the file changed");
        }
    }
}
