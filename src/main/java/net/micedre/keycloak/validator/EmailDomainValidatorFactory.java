package net.micedre.keycloak.validator;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ConfiguredProvider;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.validate.Validator;
import org.keycloak.validate.ValidatorFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Factory for creating {@link EmailDomainValidator} instances.
 * 
 * <p>This factory is registered via SPI and provides the validator to Keycloak's
 * UserProfile system. It also defines the configuration properties that appear
 * in the Admin Console when configuring the validator.</p>
 * 
 * @author Cédric Couralet
 * @see EmailDomainValidator
 * @see ValidatorFactory
 */
public class EmailDomainValidatorFactory implements ValidatorFactory, ConfiguredProvider {

    private static final String WHITELIST_FILE_PATH = "/opt/keycloak/data/whitelist.json";

    private String defaultPrivateWhitelist = "";
    private String defaultPublicWhitelist = "";

    @Override
    public String getId() {
        return EmailDomainValidator.ID;
    }

    @Override
    public Validator create(KeycloakSession session) {
        return new EmailDomainValidator();
    }

    @Override
    public void init(Config.Scope config) {
        File file = new File(WHITELIST_FILE_PATH);
        if (file.exists() && file.isFile()) {
            try {
                String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                defaultPrivateWhitelist = extractJsonValue(raw, EmailDomainValidator.CONFIG_PRIVATE_WHITELIST);
                defaultPublicWhitelist = extractJsonValue(raw, EmailDomainValidator.CONFIG_PUBLIC_WHITELIST);
                org.jboss.logging.Logger.getLogger(getClass()).infof(
                    "Email whitelist defaults loaded from file: %s", WHITELIST_FILE_PATH);
            } catch (IOException e) {
                org.jboss.logging.Logger.getLogger(getClass()).warnf(
                    "Failed to load whitelist defaults from file %s: %s", WHITELIST_FILE_PATH, e.getMessage());
            }
        }
    }

    private String extractJsonValue(String raw, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = raw.indexOf(search);
        if (keyIdx == -1) return "";
        int colon = raw.indexOf(':', keyIdx + search.length());
        if (colon == -1) return "";
        int open = raw.indexOf('"', colon + 1);
        if (open == -1) return "";
        int close = open + 1;
        while (close < raw.length()) {
            char c = raw.charAt(close);
            if (c == '\\') { close += 2; continue; }
            if (c == '"') break;
            close++;
        }
        return raw.substring(open + 1, close)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\\", "\\")
                .replace("\\\"", "\"");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // No post-initialization needed
    }

    @Override
    public void close() {
        // No cleanup needed
    }

    /**
     * Provides help text displayed in the Admin Console.
     * 
     * @return Description of the validator's purpose
     */
    public String getHelpText() {
         return "Validates that email addresses belong to whitelisted domains. " +
             "Supports glob patterns: * (any characters), ? (single character). " +
             "Public registrations use the public whitelist; all other contexts use the private whitelist. " +
             "If a whitelist is empty, no domains are allowed for that context.";
    }

    /**
     * Defines the configuration properties for this validator.
     * These properties are displayed in the Admin Console when adding
     * or editing this validator on an attribute.
     * 
     * @return List of configuration properties
     */
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(EmailDomainValidator.CONFIG_PRIVATE_WHITELIST)
                    .label("Private Whitelist (Invitations + Other Contexts)")
                    .helpText("List of allowed email domains for invitations (/login-actions/registration) " +
                              "and all non-public contexts. This whitelist always includes the public whitelist. " +
                              "Separated by newlines or ##. Supports glob patterns: * (any characters), ? (single character). " +
                              "Examples: mydomain.com, *.mydomain.com, sub?.domain.com. " +
                              "Leading @ is optional (@mydomain.com = mydomain.com). " +
                              "Leave empty to allow no domains.")
                    .type(ProviderConfigProperty.TEXT_TYPE)
                    .defaultValue(defaultPrivateWhitelist)
                    .add()
                .property()
                    .name(EmailDomainValidator.CONFIG_PUBLIC_WHITELIST)
                    .label("Public Whitelist (Public Registration)")
                    .helpText("List of allowed email domains for public registration " +
                              "(/protocol/openid-connect/registrations). Separated by newlines or ##. " +
                              "Supports glob patterns: * (any characters), ? (single character). " +
                              "Examples: mydomain.com, *.mydomain.com, sub?.domain.com. " +
                              "Leading @ is optional (@mydomain.com = mydomain.com). " +
                              "Leave empty to allow no domains.")
                    .type(ProviderConfigProperty.TEXT_TYPE)
                    .defaultValue(defaultPublicWhitelist)
                    .add()
                .property()
                    .name(EmailDomainValidator.CONFIG_ERROR_MESSAGE)
                    .label("Error Message Key")
                    .helpText("The message key to use for validation errors. " +
                              "This key should be defined in your theme's messages properties. " +
                              "Default: error-email-domain-not-allowed")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue(EmailDomainValidator.MESSAGE_INVALID_DOMAIN)
                    .add()
                .build();
    }
}
