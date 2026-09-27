package net.micedre.keycloak.validator;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationInvitationModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.organization.InvitationManager;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.provider.ConfiguredProvider;
import org.keycloak.validate.AbstractSimpleValidator;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidationError;
import org.keycloak.validate.ValidatorConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates that email addresses belong to whitelisted domains.
 * Supports glob patterns (* and ?) for flexible domain matching.
 * 
 * <p>This validator integrates with Keycloak's UserProfile system and applies to
 * all contexts where email is validated, including:</p>
 * <ul>
 *   <li>User Registration</li>
 *   <li>UPDATE_EMAIL required action</li>
 *   <li>Update Profile</li>
 *   <li>Account Console</li>
 *   <li>Admin API</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>allowed-domains</b>: Newline or ## separated list of allowed domains (textarea)</li>
 *   <li><b>domains-file</b>: Path to an external file with one allowed domain per line
 *       (optional). When set and readable, the file is the single source of truth and the
 *       textarea is ignored. {@code #} starts a line comment. Lets you add a domain with a
 *       one-line file edit instead of the Admin Console remove/re-add flow.</li>
 *   <li><b>bypass-on-org-invitation</b>: When true (default), skip the whitelist if the email
 *       has a pending organization invitation, so invitations to non-whitelisted domains can
 *       register. Disable to enforce the whitelist everywhere.</li>
 *   <li><b>error-message</b>: Custom error message key (optional)</li>
 * </ul>
 * 
 * <h2>Example Domains</h2>
 * <ul>
 *   <li>mydomain.com - exact match</li>
 *   <li>*.mydomain.com - any subdomain</li>
 *   <li>my?domain.com - single character wildcard</li>
 * </ul>
 * 
 * @author Cédric Couralet
 * @see org.keycloak.validate.AbstractSimpleValidator
 */
public class EmailDomainValidator extends AbstractSimpleValidator {

    /**
     * Unique identifier for this validator.
     * Used in User Profile configuration to reference this validator.
     */
    public static final String ID = "email-domain-whitelist";

    /**
     * Configuration property name for the list of allowed domains.
     */
    public static final String CONFIG_ALLOWED_DOMAINS = "allowed-domains";

    /**
     * Configuration property name for custom error message key.
     */
    public static final String CONFIG_ERROR_MESSAGE = "error-message";

    /**
     * Configuration property name for the organization-invitation bypass.
     * When true (default), the whitelist is skipped if the email being validated
     * has a pending organization invitation — i.e. registration by invitation
     * accepts any domain. Disable to enforce the whitelist everywhere.
     */
    public static final String CONFIG_BYPASS_ORG_INVITATION = "bypass-on-org-invitation";

    /**
     * Default value for the organization-invitation bypass (enabled).
     */
    public static final boolean DEFAULT_BYPASS_ORG_INVITATION = true;

    /**
     * Configuration property name for an external domains file. When set to a
     * readable file path, the file becomes the single source of truth for the
     * allowed domains (the {@link #CONFIG_ALLOWED_DOMAINS} textarea is ignored).
     * One domain per line; {@code #} starts a line comment. This avoids the
     * remove/re-add dance in the Admin Console when adding a domain.
     */
    public static final String CONFIG_DOMAINS_FILE = "domains-file";

    /**
     * Default error message key used when validation fails.
     * This key should be defined in theme message properties.
     */
    public static final String MESSAGE_INVALID_DOMAIN = "error-email-domain-not-allowed";

    /**
     * Regex pattern to split domain list configuration.
     * Supports: newline (\r\n, \r, \n) or ## as separators.
     */
    public static final String DOMAIN_LIST_SEPARATOR = "(?:\\r\\n|\\r|\\n|##)";

    /**
     * Maximum email length to validate (security measure against DoS).
     */
    private static final int MAX_EMAIL_LENGTH = 254;

    /**
     * Maximum domain length for glob matching (security measure).
     */
    private static final int MAX_DOMAIN_LENGTH = 200;

    /**
     * In-memory cache of parsed domains files, keyed by file path. Static because
     * validator instances are created per KeycloakSession but we want to share the
     * (relatively expensive) file read across sessions.
     */
    private static final ConcurrentHashMap<String, FileCache> FILE_CACHE = new ConcurrentHashMap<>();

    /**
     * Time-to-live for a cached file entry, in milliseconds. The file is re-read at
     * most this often. Package-private so tests can shorten it to force re-reads.
     */
    static long cacheTtlMs = 60_000L;

    @Override
    public String getId() {
        return ID;
    }

    /**
     * Performs the actual validation logic.
     * 
     * @param value     The email value to validate (already confirmed to be a non-empty String)
     * @param inputHint The attribute name (typically "email")
     * @param context   The validation context for adding errors
     * @param config    The validator configuration containing allowed domains
     */
    @Override
    protected void doValidate(Object value, String inputHint, ValidationContext context, ValidatorConfig config) {
        String email = (String) value;

        // Security check: prevent processing extremely long emails
        if (email.length() > MAX_EMAIL_LENGTH) {
            context.addError(new ValidationError(ID, inputHint, MESSAGE_INVALID_DOMAIN));
            return;
        }

        // Organization invitation bypass: when a user is registering by accepting a
        // pending organization invitation, the email was pre-authorized by an admin
        // (and RegistrationUserCreation forces it to match the invitation). Skip the
        // whitelist so invitations to non-whitelisted domains can register. This only
        // triggers when an organization is in context AND a non-expired invitation
        // exists for this exact email — plain self-registration is unaffected.
        boolean bypassEnabled = config.getBooleanOrDefault(CONFIG_BYPASS_ORG_INVITATION, DEFAULT_BYPASS_ORG_INVITATION);
        if (bypassEnabled && isInvitationAcceptance(context, email)) {
            return;
        }

        Set<String> allowedDomains = resolveAllowedDomains(config);

        // If no domains configured, skip validation (permissive mode)
        if (allowedDomains.isEmpty()) {
            return;
        }

        if (!isEmailDomainAllowed(email, allowedDomains)) {
            String errorMessage = config.getStringOrDefault(CONFIG_ERROR_MESSAGE, MESSAGE_INVALID_DOMAIN);
            // Pass allowed domains as parameter for potential use in error message
            context.addError(new ValidationError(ID, inputHint, errorMessage, formatDomainsForMessage(allowedDomains)));
        }
    }

    /**
     * Determines if validation should be skipped for the given value.
     * Validation is skipped for null or blank values - let the "required" validator handle those.
     * 
     * @param value  The value to check
     * @param config The validator configuration
     * @return true if validation should be skipped, false otherwise
     */
    @Override
    protected boolean skipValidation(Object value, ValidatorConfig config) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).isBlank();
        }
        return true;
    }

    /**
     * Resolves the set of allowed domains for a validation, preferring an external
     * file when {@link #CONFIG_DOMAINS_FILE} is set and falling back to the
     * {@link #CONFIG_ALLOWED_DOMAINS} textarea otherwise.
     *
     * <p>When a file path is configured and readable, it is the single source of
     * truth (the textarea is ignored). An empty file means "allow all domains"
     * (consistent with an empty textarea). If the path is set but the file cannot
     * be read, the validator falls back to the textarea so a missing file never
     * causes an unexpected lockout or fail-open beyond the existing behavior.</p>
     *
     * @param config the validator configuration
     * @return set of allowed domain patterns (possibly empty = allow all)
     */
    private Set<String> resolveAllowedDomains(ValidatorConfig config) {
        String filePath = config.getStringOrDefault(CONFIG_DOMAINS_FILE, "");
        if (filePath != null && !filePath.isBlank()) {
            Set<String> fromFile = readDomainsFromFile(filePath);
            if (fromFile != null) {
                return fromFile;
            }
            // file configured but not readable -> fall back to textarea
        }
        return parseDomainString(config.getStringOrDefault(CONFIG_ALLOWED_DOMAINS, ""));
    }

    /**
     * Parses a raw domain list (from the textarea or a file's contents) into a set
     * of normalized domain patterns.
     *
     * <p>Normalization: split on newlines or {@code ##}, strip {@code #} line
     * comments, trim, lowercase, and strip a leading {@code @}. Blank entries are
     * skipped. Insertion order is preserved.</p>
     *
     * @param raw the raw domain list text
     * @return set of normalized domain patterns (never null, possibly empty)
     */
    private Set<String> parseDomainString(String raw) {
        Set<String> domains = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return domains;
        }
        for (String part : raw.split(DOMAIN_LIST_SEPARATOR)) {
            int hash = part.indexOf('#');
            if (hash >= 0) {
                part = part.substring(0, hash);
            }
            String trimmed = part.trim().toLowerCase();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("@")) {
                trimmed = trimmed.substring(1);
            }
            domains.add(trimmed);
        }
        return domains;
    }

    /**
     * Reads the allowed domains from a file, with an in-memory cache.
     *
     * <p>The file is re-read at most once per {@link #cacheTtlMs} per path. The cache
     * is static because validator instances are created per KeycloakSession.</p>
     *
     * @param filePath the path to the domains file
     * @return the parsed domains (empty set if the file is readable but has no
     *         domains), or {@code null} if the file cannot be read (caller falls
     *         back to the textarea)
     */
    private Set<String> readDomainsFromFile(String filePath) {
        FileCache cached = FILE_CACHE.compute(filePath, (path, existing) -> {
            long now = System.currentTimeMillis();
            if (existing != null && now - existing.lastChecked < cacheTtlMs) {
                return existing;
            }
            File file = new File(path);
            if (!file.isFile() || !file.canRead()) {
                return new FileCache(null, now);
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    sb.append(line).append('\n');
                }
                return new FileCache(parseDomainString(sb.toString()), now);
            } catch (IOException e) {
                return new FileCache(null, now);
            }
        });
        return cached == null ? null : cached.domains;
    }

    /**
     * Clears the in-memory domains-file cache. Intended for tests.
     */
    static void clearFileCache() {
        FILE_CACHE.clear();
    }

    /**
     * Immutable cache entry for a domains file.
     */
    private static final class FileCache {
        /** Parsed domains, or {@code null} when the file could not be read. */
        final Set<String> domains;
        /** When the cache entry was last validated (epoch ms). */
        final long lastChecked;

        FileCache(Set<String> domains, long lastChecked) {
            this.domains = domains;
            this.lastChecked = lastChecked;
        }
    }

    /**
     * Checks if the email domain is in the allowed list.
     * Supports both exact matching and glob pattern matching.
     * 
     * @param email          The email address to validate
     * @param allowedDomains Set of allowed domain patterns
     * @return true if the email domain is allowed, false otherwise
     */
    private boolean isEmailDomainAllowed(String email, Set<String> allowedDomains) {
        int atIndex = email.lastIndexOf('@');

        // Invalid email format - let the email format validator handle it
        if (atIndex == -1 || atIndex == email.length() - 1) {
            return true;
        }

        String emailDomain = email.substring(atIndex + 1).toLowerCase();

        for (String allowedDomain : allowedDomains) {
            // Check exact match first (most common case)
            if (emailDomain.equals(allowedDomain)) {
                return true;
            }

            // Check glob pattern match if pattern contains wildcards
            if (allowedDomain.contains("*") || allowedDomain.contains("?")) {
                if (globMatches(emailDomain, allowedDomain)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Glob pattern matching supporting * (any characters) and ? (single character).
     * 
     * <p>This implementation is ported from the existing keycloak-mail-whitelisting
     * project to maintain compatibility with existing configurations.</p>
     * 
     * <p>Examples:</p>
     * <ul>
     *   <li>"sub.domain.com" matches "*.domain.com" → true</li>
     *   <li>"domain.com" matches "*.domain.com" → false</li>
     *   <li>"mydomain.com" matches "my?omain.com" → true</li>
     * </ul>
     * 
     * @param text The text to match against
     * @param glob The glob pattern
     * @return true if the text matches the pattern, false otherwise
     */
    protected static boolean globMatches(String text, String glob) {
        // Security: limit text length to prevent ReDoS
        if (text.length() > MAX_DOMAIN_LENGTH) {
            return false;
        }

        String rest = null;
        int pos = glob.indexOf('*');
        if (pos != -1) {
            rest = glob.substring(pos + 1);
            glob = glob.substring(0, pos);
        }

        if (glob.length() > text.length()) {
            return false;
        }

        // Handle the part up to the first *
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) != '?'
                    && !glob.substring(i, i + 1).equalsIgnoreCase(text.substring(i, i + 1))) {
                return false;
            }
        }

        // Recurse for the part after the first *, if any
        if (rest == null) {
            return glob.length() == text.length();
        } else {
            for (int i = glob.length(); i <= text.length(); i++) {
                if (globMatches(text.substring(i), rest)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Formats the allowed domains for inclusion in error messages.
     * 
     * @param domains Set of allowed domains
     * @return Formatted string of domains
     */
    private String formatDomainsForMessage(Set<String> domains) {
        return String.join(", ", domains);
    }

    /**
     * Determines whether the current email validation is happening in the context of
     * accepting a pending organization invitation.
     *
     * <p>When a user registers by accepting an org invitation, Keycloak's
     * {@code RegistrationUserCreation} sets the organization into the session context
     * (and forces the registered email to match the invitation) <em>before</em> the
     * user-profile validators run. We detect that situation and, if a non-expired
     * invitation exists for this email in the in-context organization, bypass the
     * domain whitelist so invitations to non-whitelisted domains can register.</p>
     *
     * <p>Uses only public SPIs from {@code keycloak-server-spi}
     * ({@link OrganizationProvider}, {@link InvitationManager}). Returns {@code false}
     * for any non-organization context (plain self-registration, profile updates,
     * UPDATE_EMAIL, account console, admin API) so the whitelist still applies there.</p>
     *
     * @param context the validation context
     * @param email   the email being validated
     * @return {@code true} if the email has a pending (non-expired) organization
     *         invitation in the in-context organization
     */
    private boolean isInvitationAcceptance(ValidationContext context, String email) {
        KeycloakSession session = context.getSession();
        if (session == null) {
            return false;
        }

        OrganizationModel organization = session.getContext().getOrganization();
        if (organization == null || !organization.isEnabled()) {
            return false;
        }

        try {
            OrganizationProvider orgProvider = session.getProvider(OrganizationProvider.class);
            if (orgProvider == null) {
                return false;
            }
            InvitationManager invitationManager = orgProvider.getInvitationManager();
            if (invitationManager == null) {
                return false;
            }
            OrganizationInvitationModel invitation = invitationManager.getByEmail(organization, email);
            return invitation != null && !invitation.isExpired();
        } catch (RuntimeException e) {
            // organizations feature disabled / provider unavailable -> never bypass
            return false;
        }
    }
}
