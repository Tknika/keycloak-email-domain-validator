package net.micedre.keycloak.validator;

import org.keycloak.validate.AbstractSimpleValidator;
import org.keycloak.validate.ValidationContext;
import org.keycloak.validate.ValidationError;
import org.keycloak.validate.ValidatorConfig;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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
 *   <li><b>allowed-domains</b>: Newline or ## separated list of allowed domains</li>
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
    public static final String CONFIG_PRIVATE_WHITELIST = "private-whitelist";

    public static final String CONFIG_PUBLIC_WHITELIST = "public-whitelist";

    public static final String CONFIG_ALLOWED_DOMAINS_LEGACY = "allowed-domains";

    /**
     * Configuration property name for custom error message key.
     */
    public static final String CONFIG_ERROR_MESSAGE = "error-message";

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

    private static final String INVITATION_PATH = "/login-actions/registration";

    private static final String PUBLIC_REGISTRATION_PATH = "/protocol/openid-connect/registrations";

    private enum FlowContext {
        INVITATION_REGISTRATION,
        PUBLIC_REGISTRATION,
        OTHER
    }

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

        FlowContext flowContext = detectFlowContext(context);
        Set<String> publicWhitelist = parseAllowedDomains(config, CONFIG_PUBLIC_WHITELIST);
        Set<String> privateWhitelist = parseAllowedDomains(config, CONFIG_PRIVATE_WHITELIST);

        if (privateWhitelist.isEmpty() && publicWhitelist.isEmpty()) {
            Set<String> legacyWhitelist = parseAllowedDomains(config, CONFIG_ALLOWED_DOMAINS_LEGACY);
            privateWhitelist = legacyWhitelist;
            publicWhitelist = legacyWhitelist;
        }

        // Select whitelist based on context:
        // PUBLIC_REGISTRATION (new users): use strict public whitelist
        // INVITATION_REGISTRATION (invitations): use permissive private whitelist
        // OTHER (email changes, etc.): use permissive private whitelist
        Set<String> allowedDomains = flowContext == FlowContext.PUBLIC_REGISTRATION
                ? publicWhitelist
                : privateWhitelist;

        if (allowedDomains.isEmpty()) {
            String errorMessage = config.getStringOrDefault(CONFIG_ERROR_MESSAGE, MESSAGE_INVALID_DOMAIN);
            context.addError(new ValidationError(ID, inputHint, errorMessage, ""));
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
     * Parses the allowed domains from the validator configuration.
     * 
     * <p>Domains can be separated by newlines or ##. Each domain is:</p>
     * <ul>
     *   <li>Trimmed of whitespace</li>
     *   <li>Converted to lowercase</li>
     *   <li>Stripped of leading @ if present</li>
     * </ul>
     * 
     * @param config The validator configuration
     * @return Set of allowed domains (preserves insertion order)
     */
    private Set<String> parseAllowedDomains(ValidatorConfig config) {
        return parseAllowedDomains(config, CONFIG_ALLOWED_DOMAINS_LEGACY);
    }

    private Set<String> parseAllowedDomains(ValidatorConfig config, String configKey) {
        String domainsConfig = config.getStringOrDefault(configKey, "");

        if (domainsConfig == null || domainsConfig.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> domains = new LinkedHashSet<>();
        String[] domainArray = domainsConfig.split(DOMAIN_LIST_SEPARATOR);

        for (String domain : domainArray) {
            String trimmed = domain.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                // Remove leading @ if present (user convenience)
                if (trimmed.startsWith("@")) {
                    trimmed = trimmed.substring(1);
                }
                domains.add(trimmed);
            }
        }

        return domains;
    }

    private Set<String> mergeWhitelists(Set<String> privateWhitelist, Set<String> publicWhitelist) {
        if (privateWhitelist.isEmpty()) {
            return new LinkedHashSet<>(publicWhitelist);
        }
        if (publicWhitelist.isEmpty()) {
            return new LinkedHashSet<>(privateWhitelist);
        }
        Set<String> merged = new LinkedHashSet<>(privateWhitelist);
        merged.addAll(publicWhitelist);
        return merged;
    }

    private FlowContext detectFlowContext(ValidationContext context) {
        if (context == null || context.getSession() == null || context.getSession().getContext() == null) {
            return FlowContext.OTHER;
        }

        String path = null;
        String queryString = null;
        
        if (context.getSession().getContext().getUri() != null) {
            try {
                path = context.getSession().getContext().getUri().getPath();
                queryString = context.getSession().getContext().getUri().getRequestUri().getQuery();
                
                org.jboss.logging.Logger.getLogger(getClass()).infof(
                    "Email validation - Path: %s, Query: %s", path, queryString);
                
                if (path != null && !path.isBlank()) {
                    String normalizedPath = path.toLowerCase();
                    String normalizedQuery = (queryString != null) ? queryString.toLowerCase() : "";
                    
                    // PRIORITY 1: Check for TOKEN parameter (invitations)
                    // Invitations have token= in query string, uses PRIVATE whitelist
                    if (normalizedQuery.contains("token=")) {
                        org.jboss.logging.Logger.getLogger(getClass()).info(
                            "Email validation - Detected INVITATION_REGISTRATION (token present) - using PRIVATE whitelist");
                        return FlowContext.INVITATION_REGISTRATION;
                    }
                    
                    // PRIORITY 2: PUBLIC REGISTRATION: /login-actions/registration without token
                    // Uses PUBLIC whitelist (restrictive)
                    // This includes Account Console and external client registration flows
                    if (normalizedPath.contains("/login-actions/registration")) {
                        org.jboss.logging.Logger.getLogger(getClass()).info(
                            "Email validation - Detected PUBLIC_REGISTRATION (no token) - using PUBLIC whitelist");
                        return FlowContext.PUBLIC_REGISTRATION;
                    }
                    
                    // PRIORITY 3: Other paths with /protocol/openid-connect/registrations
                    if (normalizedPath.contains("/protocol/openid-connect/registrations")) {
                        org.jboss.logging.Logger.getLogger(getClass()).info(
                            "Email validation - Detected INVITATION_REGISTRATION via /protocol path - using PRIVATE whitelist");
                        return FlowContext.INVITATION_REGISTRATION;
                    }
                    
                    // EMAIL CHANGES and other account operations
                    // These are typically in /account paths
                    // Uses PRIVATE whitelist (permissive)
                    if (normalizedPath.contains("/account/")) {
                        org.jboss.logging.Logger.getLogger(getClass()).info(
                            "Email validation - Detected email change or account operation (PRIVATE)");
                        return FlowContext.INVITATION_REGISTRATION; // Reuse for other private contexts
                    }
                }
            } catch (Exception e) {
                org.jboss.logging.Logger.getLogger(getClass()).warnf(
                    "Email validation - Error detecting context: %s", e.getMessage());
            }
        }

        org.jboss.logging.Logger.getLogger(getClass()).info(
            "Email validation - Defaulting to OTHER context (PRIVATE)");
        return FlowContext.INVITATION_REGISTRATION; // Default to private (more permissive)
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
}
