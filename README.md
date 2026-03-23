# Keycloak Email Domain Whitelist Validator

[![CI](https://github.com/Tknika/keycloak-email-domain-validator/actions/workflows/ci.yml/badge.svg)](https://github.com/Tknika/keycloak-email-domain-validator/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A custom Keycloak UserProfile Validator that restricts email addresses to a configurable list of allowed domains. This validator applies to **ALL** email update contexts in Keycloak, including registration, UPDATE_EMAIL required action, profile updates, account console, and admin API operations.

![Select validator](public/assets/screenshot_select_validator.png)
![Configure validator](public/assets/screenshot_configure_validator.png)

This project has been inspired by the [keycloak-mail-whitelisting](https://github.com/micedre/keycloak-mail-whitelisting) project, which works great for the registration flow, but not for the UPDATE_EMAIL flow. This project is a UserProfile validator that applies to all email update contexts so it can be used in all contexts.

## Features

- ✅ Validates email domains across all Keycloak contexts
- ✅ **Dual whitelist**: separate `public-whitelist` and `private-whitelist` for context-aware validation
- ✅ **Context detection**: public registration uses only the public whitelist; invitations and all other contexts use private + public combined
- ✅ **Restrictive by default**: an empty whitelist blocks all domains (no permissive fallback)
- ✅ **File-based defaults**: pre-populate whitelist fields from a JSON file via environment variable at validator creation time
- ✅ Support for multiple allowed domains
- ✅ Glob pattern matching with `*` (any characters) and `?` (single character) wildcards
- ✅ Case-insensitive domain matching
- ✅ Internationalized error messages (English, Spanish, French, Basque)
- ✅ Easy configuration through Keycloak's User Profile UI

## Requirements

- Keycloak 26.x or later (Jakarta EE)
- Java 17+
- Docker and Docker Compose (for building)

## Building the Project

This project uses Docker for all build operations to ensure a consistent development environment.

### Build the JAR

```bash
# Build the Docker image
docker compose build

# Build the project and create the JAR
docker compose run --rm maven clean package
```

The JAR will be available at `target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar`

### Run Tests

```bash
# Run all unit tests
docker compose run --rm maven test

# Run tests with verbose output
docker compose run --rm maven test -X
```

### Other Maven Commands

```bash
# Clean the build
docker compose run --rm maven clean

# Verify the build
docker compose run --rm maven verify

# Install to local Maven repository
docker compose run --rm maven install
```

## Installation

### Keycloak Quarkus Distribution (26.x+)

1. Copy the JAR to the Keycloak providers directory:

```bash
cp target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar $KEYCLOAK_HOME/providers/
```

2. Rebuild Keycloak to load the new provider:

```bash
$KEYCLOAK_HOME/bin/kc.sh build
```

3. Start Keycloak:

```bash
$KEYCLOAK_HOME/bin/kc.sh start
```

### Docker Deployment

Create a Dockerfile extending the official Keycloak image:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0.0

# Copy the validator JAR
COPY target/keycloak-email-domain-validator-1.0.0-SNAPSHOT.jar /opt/keycloak/providers/

# Build the optimized image
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Build and run:

```bash
docker build -t keycloak-with-domain-validator .
docker run -p 8080:8080 keycloak-with-domain-validator start-dev
```

## Configuration

### Via Admin Console

1. Log in to the Keycloak Admin Console
2. Select your realm
3. Go to **Realm Settings** → **User Profile**
4. Click on the **email** attribute
5. Expand the **Validations** section
6. Click **Add Validator**
7. Select **email-domain-whitelist** from the dropdown
8. Configure the two whitelist fields (see [Configuration Options](#configuration-options) below)
9. Click **Save**

> **Tip:** if you use file-based defaults (see below), the whitelist fields will be pre-populated from the JSON file when you open the "Add Validator" form.

### Via JSON Import/Export

You can configure the validator via realm JSON import:

```json
{
  "realm": "your-realm",
  "userProfile": {
    "attributes": [
      {
        "name": "email",
        "validations": {
          "email": {},
          "email-domain-whitelist": {
            "private-whitelist": "mydomain.com\nmyotherdomain.com",
            "public-whitelist": "mydomain.com",
            "error-message": "error-email-domain-not-allowed"
          }
        }
      }
    ]
  }
}
```

## Configuration Options

### Public Whitelist (`public-whitelist`)

Domains allowed for **public self-registration** (`/login-actions/registration` without an invitation token).

- Multiple domains separated by newlines or `##`
- Supports glob patterns (see below)
- **Leave empty to block all public registrations**

### Private Whitelist (`private-whitelist`)

Domains allowed for **invitations and all other contexts** (email changes, account console, Admin API, etc.).

At runtime, the effective allowed set for these contexts is `private-whitelist ∪ public-whitelist`.

- Multiple domains separated by newlines or `##`
- Supports glob patterns (see below)
- **Leave empty to allow only the domains in the public whitelist for these contexts**

### Domain Pattern Syntax

Domains in both whitelists support:

- **Exact match**: `mydomain.com`
- **Subdomain wildcard**: `*.mydomain.com` (matches `sub.mydomain.com` but not `mydomain.com`)
- **Single character wildcard**: `my?domain.com` (matches `my1domain.com`, etc.)
- **Combined patterns**: `*.sub?.mydomain.com`

**Notes:**
- Leading `@` is optional (`@mydomain.com` = `mydomain.com`)
- Domain matching is case-insensitive
- An **empty whitelist is restrictive** — it blocks all emails for that context (no permissive fallback)

### Error Message (`error-message`)

Customize the error message key (optional):
- Default: `error-email-domain-not-allowed`
- The key must be defined in your theme's message properties
- The message can include `{0}` placeholder for the list of allowed domains

### File-Based Defaults (`EMAIL_WHITELIST_FILE`)

Set the `EMAIL_WHITELIST_FILE` environment variable to a JSON file path so that the whitelist fields are **automatically pre-populated** every time the "Add Validator" form is opened in the Admin Console.

```bash
EMAIL_WHITELIST_FILE=/opt/keycloak/data/whitelist.json
```

**JSON format:**

```json
{
  "private-whitelist": "mydomain.com\nmyotherdomain.com",
  "public-whitelist": "mydomain.com",
  "error-message": "error-email-domain-not-allowed"
}
```

**Important notes:**
- The file is read **each time the form opens** — no Keycloak restart required after updating the file, only delete and re-add the validator in the UI.
- If the file does not exist or cannot be read, fields are pre-populated as empty (still usable).
- This does **not** override the saved validator configuration — it only sets the default values shown in the form.

## Examples

### Example 1: Public registration restricted, invitations open

**`public-whitelist`:**
```
company.com
```

**`private-whitelist`:**
```
company.com
partner.com
*.contractor.com
```

- Public registration: only `@company.com` allowed
- Invitations / email changes: `@company.com`, `@partner.com`, any `@*.contractor.com` allowed

### Example 2: Wildcard Subdomains

**`public-whitelist`:**
```
*.company.com
company.com
```

**Allowed** (public registration):
- `user@company.com`
- `user@dev.company.com`
- `user@staging.company.com`

**Blocked:**
- `user@company.co.uk`

### Example 3: Pattern Matching

**`private-whitelist`:**
```
*.dev?.company.com
prod.company.com
```

**Allowed** (invitations):
- `user@dev1.company.com`
- `user@deva.company.com`
- `user@prod.company.com`

**Blocked:**
- `user@development.company.com` (more than one character after "dev")
- `user@dev.company.com` (no character after "dev")

## Validation Contexts

The validator distinguishes between registration contexts and applies the appropriate whitelist:

| Context | Detection | Whitelist Used |
|---------|-----------|----------------|
| **Public Registration** | `/login-actions/registration` (no `token=` in query) | `public-whitelist` only |
| **Invitation Registration** | Any path with `token=` in query string | `private-whitelist` ∪ `public-whitelist` |
| **OpenID Connect Registration** | `/protocol/openid-connect/registrations` | `private-whitelist` ∪ `public-whitelist` |
| **Account Console** | Path contains `/account/` | `private-whitelist` ∪ `public-whitelist` |
| **UPDATE_EMAIL / Admin API / Other** | All other paths | `private-whitelist` ∪ `public-whitelist` |

> **Key rule:** if the effective allowed set for a context is empty, **all domains are blocked** — there is no permissive fallback.

## Troubleshooting

### Validator Not Appearing in Admin Console

1. Verify the JAR is in `$KEYCLOAK_HOME/providers/`
2. Ensure you ran `kc.sh build` after adding the JAR
3. Check Keycloak logs for errors:

```bash
grep -i "email-domain-whitelist" $KEYCLOAK_HOME/logs/keycloak.log
```

### Validation Not Working

1. Verify the validator is added to the email attribute in User Profile
2. Check that the whitelist is not empty — **an empty whitelist blocks all domains**
3. Make sure you are checking the correct whitelist for the context (public vs private)
4. Check Keycloak logs for the context detection line:
   ```
   Email validation - Path: ..., Query: ...
   ```
5. Test with a simple exact match domain first
6. Enable debug logging:

```bash
kc.sh start --log-level=org.keycloak.userprofile:debug
```

### File-Based Defaults Not Loading

1. Confirm `EMAIL_WHITELIST_FILE` environment variable is set inside the container:
   ```bash
   docker exec keycloak printenv | grep EMAIL_WHITELIST
   ```
2. Confirm the file exists at the path shown and is valid JSON:
   ```json
   {
     "private-whitelist": "domain1.com\ndomain2.com",
     "public-whitelist": "domain1.com"
   }
   ```
3. Defaults are loaded every time the "Add Validator" form opens — **delete and re-add** the validator after editing the file; no Keycloak restart is needed.
4. Search logs for `Email whitelist defaults loaded from file` to confirm the file was read.

### Error Messages Not Displaying

1. Ensure `theme-resources/messages` files are in the JAR
2. Verify the message key matches in the validator configuration
3. Check if a custom theme overrides the base messages

### Build Issues with Docker

```bash
# Rebuild the Docker image
docker compose build --no-cache

# Clear Maven cache volume
docker compose down -v
docker volume rm keycloak-email-domain-whitelist-validator_maven-cache

# Rebuild
docker compose build
docker compose run --rm maven clean package
```

## Migration from keycloak-mail-whitelisting

If you're currently using the FormAction-based validator:

1. Deploy this UserProfile validator alongside the existing one
2. Configure it for the email attribute in User Profile
3. Test all flows (registration, email update, etc.)
4. Remove the FormAction from the registration flow
5. Optionally remove the old JAR

The glob pattern syntax is fully compatible with the existing implementation.

## Migration from `allowed-domains` (single-field) to dual whitelist

Earlier versions of this plugin used a single `allowed-domains` field. The new version replaces it with two fields:

| Old field | New field | Used for |
|-----------|-----------|---------|
| `allowed-domains` | `public-whitelist` | Public self-registration |
| `allowed-domains` | `private-whitelist` | Invitations, email changes, Admin API, etc. |

To migrate:
1. Note your current `allowed-domains` value
2. Delete the existing validator from the email attribute
3. Add `email-domain-whitelist` again
4. Set both `public-whitelist` and `private-whitelist` as needed
5. Save

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Continuous Integration

This project uses GitHub Actions for CI/CD:

- **CI Workflow**: Runs on every push and pull request
  - Builds Docker image
  - Runs all tests
  - Creates JAR artifact
  - Generates test reports

- **Release Workflow**: Runs on version tags (e.g., `v1.0.0`)
  - Runs full test suite
  - Builds release JAR
  - Creates GitHub release with artifacts

All workflows use Docker to ensure consistency with local development.

## Development

### Project Structure

```
keycloak-email-domain-validator/
├── Dockerfile                     # Maven build environment
├── docker-compose.yml             # Docker Compose configuration
├── pom.xml                        # Maven project configuration
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── net/micedre/keycloak/validator/
│   │   │       ├── EmailDomainValidator.java
│   │   │       └── EmailDomainValidatorFactory.java
│   │   └── resources/
│   │       ├── META-INF/services/
│   │       │   └── org.keycloak.validate.ValidatorFactory
│   │       └── theme-resources/messages/
│   │           ├── messages_en.properties
│   │           ├── messages_es.properties
│   │           ├── messages_fr.properties
│   │           └── messages_eu.properties
│   └── test/
│       └── java/
│           └── net/micedre/keycloak/validator/
│               └── EmailDomainValidatorTest.java
└── README.md
```

### Running Tests During Development

```bash
# Run tests
docker compose run --rm maven test

# Run specific test class
docker compose run --rm maven test -Dtest=EmailDomainValidatorTest

# Run with coverage
docker compose run --rm maven verify
```

## License

Apache License 2.0

## References

- [Keycloak User Profile Documentation](https://www.keycloak.org/docs/latest/server_admin/#user-profile)
- [Keycloak Validator SPI](https://www.keycloak.org/docs/latest/server_development/#_validator_spi)
- [Original keycloak-mail-whitelisting](https://github.com/micedre/keycloak-mail-whitelisting)
