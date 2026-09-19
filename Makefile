# Named-verb dispatch layer — the poe-task / npm-script analog, rust-base's `cargo cmd` analog.
# Each target calls ./mvnw (or a standalone CLI) directly — no nested-JVM indirection.

MVNW := ./mvnw

.PHONY: dev depcheck format lint typecheck audit-src audit-packages security checks \
        unit integration test cover review commit

dev:
	$(MVNW) spring-boot:run

depcheck:
	$(MVNW) dependency:analyze-only

format:
	$(MVNW) spotless:apply

lint:
	$(MVNW) pmd:check

typecheck:
	$(MVNW) -DskipTests compile

# bug-pattern (SpotBugs) + SAST (Semgrep CLI — see readme "Pre-Requisites").
audit-src:
	$(MVNW) spotbugs:check
	semgrep scan --config p/java --error src tests

# advisory (OWASP dependency-check) — not wired into `mvn verify`'s gate, see pom.xml comment.
# Reads NVD_API_KEY from the environment automatically (nvdApiKeyEnvironmentVariable) — no -D flag needed.
audit-packages:
	$(MVNW) dependency-check:check

security: audit-src audit-packages

checks: depcheck format lint typecheck security

unit:
	$(MVNW) test

integration:
	$(MVNW) failsafe:integration-test

test: unit integration

# runs the unit suite with the JaCoCo agent attached and reports + gates in one shot.
cover:
	$(MVNW) test jacoco:report jacoco:check

review:
	claude run code-reviewer agent against diff for quality and elegance

commit: checks test review
	git add -A
	git cz
	git push
