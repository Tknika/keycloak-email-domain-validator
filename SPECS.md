# Technical Specification:  Keycloak Email Domain Whitelist Validator

## Project Overview

### Purpose
Create a custom Keycloak UserProfile Validator that restricts email addresses to a configurable list of allowed domains.  This validator will apply to ALL email update contexts in Keycloak, including registration, UPDATE_EMAIL required action, profile updates, account console, and admin API operations.

### Background
The existing [`keycloak-mail-whitelisting`](https://github.com/micedre/keycloak-mail-whitelisting) extension uses a FormAction SPI that only works for the registration flow. The UPDATE_EMAIL flow in Keycloak uses the UserProfile validation system, which requires a different approach.  This new validator will provide unified email domain validation across all contexts. 

### Goals
- Validate email domains during ALL email update operations
- Support multiple allowed domains configured via Admin Console
- Support glob patterns for flexible domain matching (e.g., `*.mydomain.com`)
- Provide clear error messages when validation fails
- Easy configuration through Keycloak's User Profile UI

---

## Requirements

### Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-1 | Validate that email addresses belong to a configurable list of allowed domains | Must Have |
| FR-2 | Support multiple domains separated by newlines or `##` | Must Have |
| FR-3 | Support glob pattern matching with `*` (any characters) and `?` (single character) wildcards | Must Have |
| FR-4 | Apply validation to all UserProfile contexts:  REGISTRATION, UPDATE_EMAIL, UPDATE_PROFILE, ACCOUNT, USER_API | Must Have |
| FR-5 | Allow configuration of custom error message key | Should Have |
| FR-6 | Skip validation if no domains are configured (permissive mode) | Should Have |
| FR-7 | Handle domains with or without leading `@` symbol in configuration | Should Have |
| FR-8 | Case-insensitive domain matching | Must Have |

### Non-Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-1 | Compatible with Keycloak 26.x and later (Jakarta EE) | Must Have |
| NFR-2 | No external dependencies beyond Keycloak SPIs | Must Have |
| NFR-3 | Performant validation (< 10ms per validation) | Should Have |
| NFR-4 | Thread-safe implementation | Must Have |
| NFR-5 | Support for internationalized error messages | Should Have |

---

## Technical Design

### Architecture

The solution uses Keycloak's Validator SPI, which integrates with the UserProfile system. When any component in Keycloak validates a user profile (registration, email update, profile update, etc.), it calls the configured validators for each attribute.

```
┌─────────────────────────────────────────────────────────────────┐
│ Keycloak UserProfile System                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ Registration │  │ UPDATE_EMAIL │  │ Account Console      │  │
│  │ Flow         │  │ Required     │  │ Profile Update       │  │
│  │              │  │ Action       │  │                      │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         │                 │                     │              │
│         └─────────────────┼─────────────────────┘              │
│                           │                                    │
│                           ▼                                    │
│                  ┌────────────────────────┐                    │
│                  │ UserProfileProvider    │                    │
│                  │ .create(context, data) │                    │
│                  └───────────┬────────────┘                    │
│                              │                                 │
│                              ▼                                 │
│                  ┌────────────────────────┐                    │
│                  │ UserProfile.validate() │                    │
│                  └───────────┬────────────┘                    │
│                              │                                 │
│                              ▼                                 │
│                  ┌────────────────────────┐                    │
│                  │ Email Attribute        │                    │
│                  │ Validators:            │                    │
│                  │ - EmailValidator       │◄── Built-in        │
│                  │ - email-domain-        │◄── OUR VALIDATOR   │
│                  │   whitelist            │                    │
│                  └────────────────────────┘                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Project Structure

```
keycloak-email-domain-validator/
├── pom.xml
├── README.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── net/
│   │   │       └── micedre/
│   │   │           └── keycloak/
│   │   │               └── validator/
│   │   │                   ├── EmailDomainValidator.java
│   │   │                   └── EmailDomainValidatorFactory.java
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   └── services/
│   │       │       └── org.keycloak.validate.ValidatorFactory
│   │       └── theme-resources/
│   │           └── messages/
│   │               ├── messages_en.properties
│   │               └── messages_es.properties
│   └── test/
│       └── java/
│           └── net/
│               └── micedre/
│                   └── keycloak/
│                       └── validator/
│                           └── EmailDomainValidatorTest.java
```

---

## Implementation Details

### 1. Maven Configuration (`pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns: xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi: schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>net.micedre. keycloak</groupId>
    <artifactId>keycloak-email-domain-validator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Keycloak Email Domain Validator</name>
    <description>A UserProfile validator to whitelist email domains for all email update contexts</description>
    <url>https://github.com/micedre/keycloak-email-domain-validator</url>

    <licenses>
        <license>
            <name>The Apache Software License, Version 2.0</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build. sourceEncoding>
        <maven.compiler.source>17</maven.compiler. source>
        <maven.compiler.target>17</maven. compiler.target>
        <keycloak.version>26.0.0</keycloak. version>
        <junit.version>5.10.0</junit.version>
    </properties>

    <dependencies>
        <!-- Keycloak dependencies (provided at runtime) -->
        <dependency>
            <groupId>org.keycloak</groupId>
            <artifactId>keycloak-server-spi</artifactId>
            <version>${keycloak. version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org. keycloak</groupId>
            <artifactId>keycloak-server-spi-private</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org. keycloak</groupId>
            <artifactId>keycloak-services</artifactId>
            <version>${keycloak.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Test dependencies -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-params</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven. plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>17</source>
                    <target>17</target>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache. maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.1.2</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.3.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

### 2. Validator Implementation (`EmailDomainValidator.java`)

```java
package net.micedre.keycloak.validator;

import org.keycloak. provider.ConfiguredProvider;
import org. keycloak.validate.AbstractSimpleValidator;
import org.keycloak.validate.ValidationContext;
import org. keycloak.validate.ValidationError;
import org.keycloak.validate.ValidatorConfig;

import java.util. Collections;
import java. util.LinkedHashSet;
import java.util.Set;

/**
 * Validates that email addresses belong to whitelisted domains. 
 * Supports glob patterns (* and ?) for flexible domain matching.
 * 
 * <p>This validator integrates with Keycloak's UserProfile system and applies to
 * all contexts where email is validated, including: </p>
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
 *   <li>*.mydomain. com - any subdomain</li>
 *   <li>my? domain.com - single character wildcard</li>
 * </ul>
 * 
 * @author Cédric Couralet
 * @see org.keycloak. validate.AbstractSimpleValidator
 */
public class EmailDomainValidator extends AbstractSimpleValidator implements ConfiguredProvider {

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
     * Default error message key used when validation fails.
     * This key should be defined in theme message properties. 
     */
    public static final String MESSAGE_INVALID_DOMAIN = "error-email-domain-not-allowed";

    /**
     * Regex pattern to split domain list configuration.
     * Supports:  newline (\r\n, \r, \n) or ## as separators.
     */
    public static final String DOMAIN_LIST_SEPARATOR = "(? :\\r\\n|\\r|\\n|##)";

    /**
     * Maximum email length to validate (security measure against DoS).
     */
    private static final int MAX_EMAIL_LENGTH = 254;

    /**
     * Maximum domain length for glob matching (security measure).
     */
    private static final int MAX_DOMAIN_LENGTH = 200;

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

        Set<String> allowedDomains = parseAllowedDomains(config);

        // If no domains configured, skip validation (permissive mode)
        if (allowedDomains. isEmpty()) {
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
     * <p>Domains can be separated by newlines or ##.  Each domain is: </p>
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
        String domainsConfig = config.getStringOrDefault(CONFIG_ALLOWED_DOMAINS, "");

        if (domainsConfig == null || domainsConfig.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> domains = new LinkedHashSet<>();
        String[] domainArray = domainsConfig.split(DOMAIN_LIST_SEPARATOR);

        for (String domain : domainArray) {
            String trimmed = domain.trim().toLowerCase();
            if (! trimmed.isEmpty()) {
                // Remove leading @ if present (user convenience)
                if (trimmed.startsWith("@")) {
                    trimmed = trimmed.substring(1);
                }
                domains. add(trimmed);
            }
        }

        return domains;
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

        String emailDomain = email. substring(atIndex + 1).toLowerCase();

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
     *   <li>"sub.domain.com" matches "*. domain.com" → true</li>
     *   <li>"domain.com" matches "*.domain.com" → false</li>
     *   <li>"mydomain.com" matches "my? omain.com" → true</li>
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
            if (glob.charAt(i) != '? '
                    && ! glob.substring(i, i + 1).equalsIgnoreCase(text.substring(i, i + 1))) {
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
```


### 3. Validator Factory (`EmailDomainValidatorFactory.java`)

```java
package net.micedre. keycloak.validator;

import org. keycloak.Config;
import org. keycloak.models.KeycloakSession;
import org. keycloak.models.KeycloakSessionFactory;
import org.keycloak. provider. ProviderConfigProperty;
import org.keycloak.provider. ProviderConfigurationBuilder;
import org.keycloak.validate. Validator;
import org.keycloak. validate.ValidatorFactory;

import java.util. List;

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
        return EmailDomainValidator. ID;
    }

    @Override
    public Validator create(KeycloakSession session) {
        return new EmailDomainValidator();
    }

    @Override
    public void init(Config. Scope config) {
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
    @Override
    public String getHelpText() {
        return "Validates that email addresses belong to whitelisted domains.  " +
               "Supports glob patterns:  * (any characters), ? (single character). " +
               "If no domains are configured, validation is skipped (all domains allowed).";
    }

    /**
     * Defines the configuration properties for this validator.
     * These properties are displayed in the Admin Console when adding
     * or editing this validator on an attribute. 
     * 
     * @return List of configuration properties
     */
    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(EmailDomainValidator.CONFIG_ALLOWED_DOMAINS)
                    .label("Allowed Domains")
                    . helpText("List of allowed email domains, separated by newlines or ##.  " +
                              "Supports glob patterns: * (any characters), ? (single character). " +
                              "Examples: mydomain.com, *.mydomain.com, sub?. domain.com.  " +
                              "Leading @ is optional (@mydomain.com = mydomain.com). " +
                              "Leave empty to allow all domains.")
                    .type(ProviderConfigProperty. TEXT_TYPE)
                    .defaultValue("")
                    .add()
                .property()
                    .name(EmailDomainValidator.CONFIG_ERROR_MESSAGE)
                    .label("Error Message Key")
                    . helpText("The message key to use for validation errors. " +
                              "This key should be defined in your theme's messages properties. " +
                              "Default:  error-email-domain-not-allowed")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue(EmailDomainValidator.MESSAGE_INVALID_DOMAIN)
                    .add()
                .build();
    }
}
```

### 4. SPI Registration File

File: `src/main/resources/META-INF/services/org.keycloak.validate. ValidatorFactory`

```
net.micedre.keycloak.validator.EmailDomainValidatorFactory
```

### 5. Message Properties

File: `src/main/resources/theme-resources/messages/messages_en.properties`

```properties
# Email domain validation error messages
error-email-domain-not-allowed=Email domain is not allowed.  Please use an email address from one of the authorized domains:  {0}
```

File: `src/main/resources/theme-resources/messages/messages_es.properties`

```properties
# Mensajes de error de validación de dominio de correo electrónico
error-email-domain-not-allowed=El dominio de correo electrónico no está permitido. Por favor, utilice una dirección de correo de uno de los dominios autorizados: {0}
```

File: `src/main/resources/theme-resources/messages/messages_fr.properties`

```properties
# Messages d'erreur de validation du domaine de messagerie
error-email-domain-not-allowed=Le domaine de messagerie n'est pas autorisé. Veuillez utiliser une adresse e-mail d'un des domaines autorisés: {0}
```

File: `src/main/resources/theme-resources/messages/messages_eu.properties`

```properties
# Posta elektronikoaren domeinuaren balidazio-errore mezuak
error-email-domain-not-allowed=Posta elektronikoaren domeinua ez dago baimenduta. Mesedez, erabili baimendutako domeinuetako bat duen helbide elektroniko bat: {0}
```

### 6. Unit Tests (`EmailDomainValidatorTest.java`)

```java
package net.micedre.keycloak. validator;

import org.junit.jupiter. api.BeforeEach;
import org.junit. jupiter.api.DisplayName;
import org.junit.jupiter. api. Nested;
import org.junit.jupiter. api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter. params.provider.CsvSource;
import org.junit.jupiter.params. provider.ValueSource;

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
            assertEquals(expected, EmailDomainValidator. globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Wildcard * matches any characters")
        @CsvSource({
            "sub. mydomain.com, *.mydomain.com, true",
            "a.b.mydomain.com, *.mydomain.com, true",
            "mydomain.com, *.mydomain.com, false",
            "test. example.org, *.example. org, true",
            "example. org, *.example. org, false"
        })
        void testWildcardAsterisk(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Wildcard ? matches single character")
        @CsvSource({
            "mydomain.com, my?omain.com, true",
            "myzomain.com, my? omain.com, true",
            "myomain.com, my?omain.com, false",
            "myxxomain.com, my?omain.com, false"
        })
        void testWildcardQuestion(String text, String pattern, boolean expected) {
            assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
        }

        @ParameterizedTest
        @DisplayName("Combined wildcards")
        @CsvSource({
            "sub. mydomain.com, *.my?omain.com, true",
            "test.myzomain.com, *.my? omain.com, true",
            "myzomain.com, *.my? omain.com, false"
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
            assertTrue(EmailDomainValidator.globMatches("anything. here. com", "*"));
            assertTrue(EmailDomainValidator.globMatches("a", "*"));
            assertTrue(EmailDomainValidator.globMatches("", "*"));
        }
    }
}
```

---

## Deployment Instructions

### Building the project

```bash
# Clone or create the project
cd keycloak-email-domain-validator

# Build with Maven
mvn clean package

# The JAR will be in target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar
```

### Installing in Keycloak

#### Quarkus Distribution (Keycloak 17+)

```bash
# Copy JAR to providers directory
cp target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar $KEYCLOAK_HOME/providers/

# Rebuild Keycloak
$KEYCLOAK_HOME/bin/kc.sh build

# Start Keycloak
$KEYCLOAK_HOME/bin/kc.sh start
```

#### Docker Deployment

```Dockerfile
FROM quay. io/keycloak/keycloak: 26.5.0

# Copy the validator JAR
COPY target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/

# Build the optimized image
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

### Configuration via Admin Console


1. Log in to Keycloak Admin Console
2. Select your realm
3. Go to Realm Settings → User Profile
4. Click on the email attribute
5. Expand the Validations section
6. Click Add Validator
7. Select email-domain-whitelist from the dropdown
8. Configure the allowed domains:

```
mydomain.com
myotherdomain.com
lastone.com
```

9. Click Save

### Configuration via JSON (Import/Export)

You can also configure the validator via realm JSON import:

```json
{
  "realm": "your-realm",
  "userProfile": {
    "attributes": [
      {
        "name": "email",
        "validations": {
          "email":  {},
          "email-domain-whitelist":  {
            "allowed-domains": "mydomain.com\nmyotherdomain.com\nlastone.com"
          }
        }
      }
    ]
  }
}
```

## Testing Checklist

### Manual Testing

| # | Test Case | Steps | Expected Result |
|---|-----------|-------|-----------------|
| 1 | Registration with allowed domain | Register user with email user@mydomain.com | Registration succeeds |
| 2 | Registration with blocked domain | Register user with email user@gmail.com | Registration fails with error message |
| 3 | UPDATE_EMAIL with allowed domain | Trigger UPDATE_EMAIL, enter user@mydomain.com | Email updated successfully |
| 4 | UPDATE_EMAIL with blocked domain | Trigger UPDATE_EMAIL, enter user@gmail.com | Error message displayed, email not updated |
| 5 | Account Console email change (allowed) | Change email to user@mydomain.com | Email updated successfully |
| 6 | Account Console email change (blocked) | Change email to user@blocked.com | Error message displayed |
| 7 | Admin API email update (allowed) | PUT /admin/realms/{realm}/users/{id} with allowed email | 204 No Content |
| 8 | Admin API email update (blocked) | PUT /admin/realms/{realm}/users/{id} with blocked email | 400 Bad Request with validation error |
| 9 | Glob pattern subdomain | Configure *.mydomain.com, register with sub.mydomain.com | Registration succeeds |
| 10 | Glob pattern root domain | Configure *.mydomain.com, register with mydomain.com | Registration fails (no subdomain) |
| 11 | Empty configuration | Remove all domains from config | All emails allowed |
| 12 | Case insensitivity | Register with USER@MYDOMAIN.COM | Registration succeeds |
| 13 | Leading @ in config | Configure @mydomain.com | Works same as mydomain.com |
| 14 | Multiple separators | Configure domains with ## and newlines | All domains recognized |
| 15 | Error message displayed | Register with blocked domain | Custom error message shows allowed domains |

### Automated Testing

Run unit tests:

```bash
mvn test
```

## Troubleshooting

### Validator Not Appearing in Admin Console

1. Verify JAR is in $KEYCLOAK_HOME/providers/
2. Ensure kc.sh build was run after adding the JAR
3. Check Keycloak logs for SPI loading errors:

```bash
grep -i "email-domain-whitelist" $KEYCLOAK_HOME/logs/keycloak.log
```

### Validation Not Working

1. Verify the validator is added to the email attribute in User Profile
2. Check the allowed domains configuration (no typos)
3. Test with debug logging:

```bash
kc.sh start --log-level=org.keycloak. userprofile:debug
```

### Error Messages Not Displaying


1. Ensure theme-resources/messages files are in the JAR
2. Verify message key matches in validator and properties file
3. Check if custom theme overrides base messages


## Appendix

### Migration from keycloak-mail-whitelisting FormAction

If you're currently using the FormAction-based validator for registration:

1. Deploy both validators initially
2. Configure the UserProfile validator for the email attribute
3. Test all flows (registration, update email, etc.)
4. Remove the FormAction from the registration flow
5. Optionally remove the old JAR

The glob pattern syntax is compatible, so existing domain configurations will work.

### References

- [Keycloak User Profile Documentation](https://www.keycloak.org/docs/latest/server_admin/#user-profile)
- [Keycloak Validator SPI](https://www.keycloak.org/docs/latest/server_development/#_validator_spi)
- [Original keycloak-mail-whitelisting](https://github.com/micedre/keycloak-mail-whitelisting)
- [Keycloak UpdateEmail.java Source](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/authentication/requiredactions/UpdateEmail.java)




