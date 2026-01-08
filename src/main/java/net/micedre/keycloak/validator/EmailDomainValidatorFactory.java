package net.micedre.keycloak.validator;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.validate.Validator;
import org.keycloak.validate.ValidatorFactory;

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
public class EmailDomainValidatorFactory implements ValidatorFactory {

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
        // No initialization needed
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
               "If no domains are configured, validation is skipped (all domains allowed).";
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
                    .name(EmailDomainValidator.CONFIG_ALLOWED_DOMAINS)
                    .label("Allowed Domains")
                    .helpText("List of allowed email domains, separated by newlines or ##. " +
                              "Supports glob patterns: * (any characters), ? (single character). " +
                              "Examples: mydomain.com, *.mydomain.com, sub?.domain.com. " +
                              "Leading @ is optional (@mydomain.com = mydomain.com). " +
                              "Leave empty to allow all domains.")
                    .type(ProviderConfigProperty.TEXT_TYPE)
                    .defaultValue("")
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
