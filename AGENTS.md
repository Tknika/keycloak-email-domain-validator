# Agent Instructions

## Development Environment

This project uses **Docker** and **Docker Compose** for all development tasks.

### Important Rules

- ⚠️ **DO NOT** install dependencies or tools directly on the host system
- ⚠️ **DO NOT** use `npm`, `maven`, `gradle`, or other build tools outside of Docker containers
- ✅ **DO** use `docker compose` for building the project
- ✅ **DO** use `docker compose` for installing dependencies
- ✅ **DO** use `docker compose` for running tests
- ✅ **DO** execute all commands within Docker containers

### General Workflow

All development activities (building, testing, dependency management, etc.) must be performed using Docker Compose:

```bash
# Build the project
docker compose build

# Run tests
docker compose run --rm <service-name> <test-command>

# Install dependencies
docker compose run --rm <service-name> <install-command>

# Start services
docker compose up
```

### Why Docker?

Using Docker ensures:
- Consistent development environment across all machines
- Isolation from host system dependencies
- Reproducible builds and tests
- No pollution of the host system with project-specific tools

---

**Remember**: Keep everything containerized. If you need to run a command, run it through `docker compose`.
