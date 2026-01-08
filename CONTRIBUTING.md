# Contributing to Keycloak Email Domain Validator

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing to this project.

## Development Setup

This project uses Docker for all development tasks to ensure consistency across environments.

### Prerequisites

- Docker
- Docker Compose

### Getting Started

1. Fork and clone the repository:

```bash
git clone https://github.com/YOUR_USERNAME/keycloak-email-domain-validator.git
cd keycloak-email-domain-validator
```

2. Build the Docker image:

```bash
docker compose build
```

3. Run tests to verify your setup:

```bash
docker compose run --rm maven mvn test
```

## Development Workflow

### Making Changes

1. Create a new branch for your feature or fix:

```bash
git checkout -b feature/your-feature-name
```

2. Make your changes to the code

3. Run tests to ensure everything works:

```bash
docker compose run --rm maven mvn test
```

4. Build the package to verify compilation:

```bash
docker compose run --rm maven mvn clean package
```

### Running Tests

```bash
# Run all tests
docker compose run --rm maven mvn test

# Run specific test class
docker compose run --rm maven mvn test -Dtest=EmailDomainValidatorTest

# Run with verbose output
docker compose run --rm maven mvn test -X
```

### Code Style

- Follow Java naming conventions
- Use meaningful variable and method names
- Add Javadoc comments for public methods and classes
- Keep methods focused and concise

## Continuous Integration

This project uses GitHub Actions for CI/CD:

### CI Workflow

Triggered on:
- Push to `main` or `develop` branches
- Pull requests to `main` or `develop` branches

The CI workflow:
1. Builds the Docker image
2. Runs all unit tests
3. Builds the JAR package
4. Uploads the JAR as an artifact
5. Generates test reports

### Release Workflow

Triggered on:
- Push of version tags (e.g., `v1.0.0`)

The release workflow:
1. Runs all tests
2. Builds the release JAR
3. Creates a GitHub release
4. Attaches the JAR to the release

## Submitting Changes

### Pull Request Process

1. Ensure all tests pass locally:

```bash
docker compose run --rm maven mvn clean test
```

2. Push your branch to your fork:

```bash
git push origin feature/your-feature-name
```

3. Open a Pull Request on GitHub with:
   - Clear description of the changes
   - Reference to any related issues
   - Screenshots (if applicable)

4. Wait for CI checks to pass
5. Address any review comments
6. Once approved, your PR will be merged

### Commit Messages

Use clear and descriptive commit messages:

- **feat**: New feature (e.g., `feat: add support for regex patterns`)
- **fix**: Bug fix (e.g., `fix: correct glob matching for subdomain wildcards`)
- **docs**: Documentation changes (e.g., `docs: update configuration examples`)
- **test**: Test additions or changes (e.g., `test: add edge case for empty domains`)
- **refactor**: Code refactoring (e.g., `refactor: simplify domain parsing logic`)
- **chore**: Maintenance tasks (e.g., `chore: update dependencies`)

## Testing Guidelines

### Writing Tests

- Use JUnit 5 and parameterized tests where appropriate
- Test edge cases and error conditions
- Use descriptive test names with `@DisplayName`
- Group related tests with `@Nested` classes

Example:

```java
@Nested
@DisplayName("Glob Pattern Matching")
class GlobMatchingTests {
    
    @ParameterizedTest
    @DisplayName("Wildcard * matches any characters")
    @CsvSource({
        "sub.domain.com, *.domain.com, true",
        "domain.com, *.domain.com, false"
    })
    void testWildcardAsterisk(String text, String pattern, boolean expected) {
        assertEquals(expected, EmailDomainValidator.globMatches(text, pattern));
    }
}
```

### Test Coverage

- Aim for high test coverage (>80%)
- All new features should include tests
- Bug fixes should include regression tests

## Reporting Issues

### Bug Reports

Include:
- Keycloak version
- Steps to reproduce
- Expected behavior
- Actual behavior
- Error messages or logs
- Configuration (allowed domains)

### Feature Requests

Include:
- Use case description
- Proposed solution
- Alternative solutions considered
- Impact on existing functionality

## Code of Conduct

- Be respectful and inclusive
- Welcome newcomers
- Focus on constructive feedback
- Maintain a positive environment

## Questions?

If you have questions:
- Check existing [Issues](https://github.com/micedre/keycloak-email-domain-validator/issues)
- Review the [README](README.md)
- Open a new issue with the `question` label

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
