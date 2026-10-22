#!/usr/bin/env bash
# Runs the API locally with variables from .env (Spring Boot does not read .env itself).
set -euo pipefail
cd "$(dirname "$0")"
set -a; source .env; set +a
exec ./mvnw spring-boot:run "$@"
