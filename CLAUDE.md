## Core Directive

Push back, expose my ideas weak spots, don't tell me I'm right unless I'm objectively right.

## Complexity Budget

Default to the simplest implementation that passes the tests.
Before adding any abstraction, pattern, library, or layer — STOP and ask.
Complexity requires explicit approval. Simplicity never does.

If you are about to add a base class, an interface, a factory, a manager, a service layer,
or any indirection that isn't demanded by a failing test — stop. Ask first.


## Decision Gates

STOP and present options before implementing any of the following.
Do NOT implement. Present options and wait for approval.

- Architecture or structural decisions
- Library or framework selection
- Data model design
- Protocol choices
- Anything with physical consequences
- Any decision you are uncertain about


## When Presenting Options

Lead with your recommendation and one sentence why.
Then list alternatives with their tradeoff.

Format:
> I recommend X because Y.
> Alternatives: A (tradeoff), B (tradeoff).

Never present options without a recommendation.
Never present a recommendation without a reason.


## Anti-Bias Rules

| AI Bias | Correct Practice |
|---|---|
| Adds abstraction layers preemptively | YAGNI — build what the test requires, nothing more |
| Presents options without a recommendation | Always lead with recommendation + one sentence why |
| Chains implementation without stopping | Stop at every decision gate and wait for approval |
| Splits files prematurely | 200 line limit, but don't split until you hit it |
| Uses complex patterns to appear thorough | Simple code that passes tests is the goal, not impressive code |
| Makes assumptions when context is missing | Ask. Never assume. |
| Picks a library without presenting alternatives | Always a decision gate — stop and present options |


---


## Implementation Methodology

When presented with a request YOU MUST:

1. Use context7 mcp server or websearch tool to get the latest related documentation. Understand the API deeply and all of its nuances and options.
2. Use TDD: derive expected behavior first, write the failing test, then build until it passes.
3. Start with the simplest happy path test.
4. Think about what the assert should look like.
5. See the test fail.
6. Make the smallest change possible.
7. Check if test passes.
8. Repeat steps 6-7 until it passes.
9. YOU MUST NOT move on until assertions pass.


## Debugging Methodology

### Phase I: Information Gathering
1. Understand the error.
2. Read the relevant source code: the resolved jars in `~/.m2/repository/`, or `javap` / `unzip -l` against them.
3. Look at any relevant GitHub issues for the library.

### Phase II: Testing Hypothesis
4. Develop a hypothesis that resolves the root cause. Must only chase root cause solutions. Think hard to decide if it's root cause or NOT.
5. Add debug logs to test hypothesis.
6. If not successful, YOU MUST clean up any artifacts or code attempts in this debug cycle. Then repeat steps 1-5.

### Phase III: Weigh Tradeoffs
7. If successful and fix is straightforward — apply fix.
8. If not straightforward — weigh tradeoffs and provide a recommendation using the options format above.


## Code Structure & Modularity

- **Never break up nested values.** When working with a value that is part of a larger structure, always import or pass the entire parent structure. Never extract or isolate the nested value from its parent context.
- **Get to the root of the problem.** Never write hacky workarounds.
- **Never create a file longer than 200 lines.** If a file approaches this limit, refactor by splitting into modules. Do not split prematurely.
- **Organize code into modules which can easily be added and removed** — one package per feature: `io.arcnode.mockderms.<feature>` with controller, service, `dto/` records. No repository/entities layer — this is a stateless mock, in-memory state only.
- **Strive for symmetry among all projects.** All projects, whatever the language, should follow the same patterns. The only exception is language idioms and idiosyncrasies.
- **Use `cfg.yml` for config variables. NEVER add config vars to env files.**
- **Use `template-secrets.env` to track the list of secrets.**
- **Use environment variables for secrets.** Do NOT conflate secrets with config variables.
- **Use dependency injection for testability.**
- **Keep class names generic:** `TimeseriesClient` not `TimescaleClient`.
- **Use generics judiciously.** If generics don't provide a clear benefit in code reuse, type safety, or API design — use concrete types instead.


## Testing & Reliability

When engaging in TDD:
1. Think about one useful happy path assert.
2. Write the failing test.
3. Write the method with `throw new UnsupportedOperationException("Not Implemented")`.
4. See the not-implemented error.
5. Make the smallest change until it passes.

- **Use AAA (Arrange, Act, Assert) pattern for all tests.**
- **Unit tests: `<Feature>ServiceTest` under `src/test/java/io/arcnode/mockderms/<feature>/` (Surefire, `mvn test`)** — JUnit 5 + Mockito, no Spring context.
- **Integration tests: `<Feature>ResourceIT` / `<Feature>PublishIT` under the top-level `tests/java/io/arcnode/mockderms/` (Failsafe, `mvn verify`)** — sibling to `src/`, added as a second test-source root via `build-helper-maven-plugin`'s `add-test-source` (`testSourceDirectory` is single-valued and would replace `src/test/java` wholesale, so it has to be additive). `@SpringBootTest(webEnvironment = RANDOM_PORT)`, real HTTP via `RestTestClient`.
- **Use Testcontainers for integration tests** — `AbstractBrokerIT` (in `tests/java/`) gives every `*IT` a shared real HiveMQ broker (`MqttConfig` connects on boot, so every `@SpringBootTest` context needs one reachable).
- **Mock every outbound call with WireMock**; skip the stubbed assertion when `Config.e2e()` is true (beta profile hits the real endpoint).
- **Fail fast, fail early.** Detect errors as early as possible and halt. Rely on the runtime to handle the error and provide a stack trace. Do NOT write defensive error handling without a good reason.


## Style

- **Constants:** `private static final` in `SCREAMING_SNAKE_CASE`.
- **Use `var` only when the right-hand side makes the type obvious.**
- **Use proper logging (SLF4J), not `System.out`.**


## Documentation

- **Write comments in a terse and casual tone.**
- **Comment non-obvious code.** Everything should be understandable to a mid-level developer.
- **Add an inline `// Reason:` comment** for complex logic — explain the why, not the what.
- **Write concise Javadoc primarily for an LLM to consume**, secondarily for a document generator.


## AI Behavior Rules

- **Never assume missing context. Ask.**
- **Never hallucinate API or library functions.** Only use known, verified libraries.
- **Never chain steps through a decision gate.** Stop. Present options. Wait.
- **Never declare an API broken without research and confirmation.** If something doesn't work as expected, the first assumption is that you're using it wrong. Before concluding "bug": (1) search docs, forums, and GitHub issues, (2) read the library source (the resolved jar), (3) write an isolated probe that eliminates your own usage errors. Only after all three confirm the behavior, label it a bug.


## Java Language Guidelines ☕

### Java 21 idioms
- **`record` for every DTO / value type** — requests, responses, config. No JPA entities here (stateless mock, in-memory state only).
- **`sealed` interface + pattern-matching `switch`** for a closed hierarchy — exhaustive, no `default`.
- **`var`** for locals only when the RHS makes the type obvious.
- **Text blocks** for multi-line string literals.
- **Virtual threads** are enabled (`spring.threads.virtual.enabled=true`).

### Nullness
- Return `Optional<T>` for "maybe absent" — never `null`.
- `org.jspecify` `@Nullable` marks the few fields that really are optional (e.g. a partial-update record).

### Patterns
- **Immutability:** `record`, `List.of` / `Map.of`, `final` fields, no setters on domain types.
- **Streams** for collection transforms (`map` / `filter` / `toList`) — no manual index loops.
- **`static final SCREAMING_SNAKE_CASE`** constants.
- **SLF4J only:** `private static final Logger LOG = LoggerFactory.getLogger(Foo.class)`. Never `System.out` / `System.err`.

### Testing
- **AssertJ**, actual-then-expected: `assertThat(actual).isEqualTo(expected)`.
- **Mockito** `@ExtendWith(MockitoExtension.class)`, `@Mock` / `@InjectMocks`, BDD `given(...).willReturn(...)`.

### Javadoc for an LLM
- One-line summary, then `@param` / `@return` / `@throws`, then a `{@snippet}` if it clarifies usage.


## Spring Boot Project Guidelines 🍃

### Structure
- **Constructor injection only** — `final` fields, no field `@Autowired`, no Lombok.
- Controller ↔ Service, no Repository/persistence layer — state (event windows, dwell timers, enrolled DER registry) lives in-memory in the service, since this is a mock scoped to one test run.
- **Requests / responses are `record`s** with Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Size`).
- `@RestController` + `@RequestMapping`; `@ResponseStatus` or `ResponseEntity`; errors via `ResponseStatusException` → RFC 7807 `ProblemDetail`.
- OpenAPI: springdoc annotations (`@Tag`, `@Operation`) → `/swagger-ui`, `/v3/api-docs`.

### Config
- `cfg.yml` (`local` / `beta`, selected by `$ENV`) is the source of truth for non-secrets. `Config.Loader` (an `EnvironmentPostProcessor` in `META-INF/spring.factories`, registered as `io.arcnode.mockderms.Config$Loader`) lifts it into the environment under `app.*`; `Config` itself is a `@Validated @ConfigurationProperties(prefix = "app")` record with `LogLevel` and `Loader` nested inside it — one file, matching the sibling templates' single `config.py`/`config.ts` (Java only requires one *public top-level* type per file, matching the filename — nested types can share it).
- `application.yml` holds Spring-native wiring only, referencing `${app.*}`.
- Secrets: environment only, names tracked in `template-secrets.env` (`MQTT_MOCK_DERMS_DISPATCH_API_PASSWORD`, optional `NVD_API_KEY`).

### `make` verbs
Named-verb dispatch layer (the poe-task / npm-script / `cargo cmd` analog) — every verb runs as
`make <verb>`, each target calling `./mvnw <goal>` (or a standalone CLI) directly, no lifecycle
phase binding, so none run automatically during `mvn verify`.

| verb | runs |
|---|---|
| `dev` | `spring-boot:run` |
| `depcheck` | `dependency:analyze-only` (advisory — also runs in `verify`, never fails) |
| `format` | `spotless:apply` |
| `lint` | `pmd:check` — correctness + style (SpotBugs is bug-pattern detection, a different category; it lives under `audit-src`) |
| `typecheck` | `-DskipTests compile` |
| `audit-src` | `spotbugs:check` + `semgrep scan --config p/java` |
| `audit-packages` | `dependency-check:check` (OWASP) — needs `NVD_API_KEY`, see pom.xml comment |
| `security` | `audit-src` + `audit-packages` |
| `checks` | `depcheck` + `format` + `lint` + `typecheck` + `security` |
| `unit` | `test` (`*Test`, Surefire) |
| `integration` | `failsafe:integration-test` (`*IT`, Failsafe — needs Docker) |
| `test` | `unit` + `integration` |
| `cover` | `test jacoco:report jacoco:check` — runs unit tests w/ agent, reports, gates at 70% line |
| `review` | `claude` code-reviewer agent against the diff |
| `commit` | `checks` + `test` + `review` + `git add -A && git cz && git push` |

`./mvnw verify` (format → compile → Surefire → SpotBugs → PMD → Failsafe → JaCoCo gate) still
works directly and is what CI and pre-commit habit should default to; the `make` verbs are a
convenience dispatch layer on top, not a replacement for it.

### Toolchain
- Committed `mvnw`. `maven-toolchains-plugin` pins compile/test/spotbugs/PMD to JDK 21, leaving the system default JDK untouched. Local dev: `~/.m2/toolchains.xml`. CI: generated from `$JAVA_HOME` into a repo-local `.ci-toolchains.xml`, passed with `-t`. JDK 21 on the runner comes from `openjdk-21-jdk` in `tooling-playbooks/gitlab-runner-setup.yml`. Dockerfile: writes one against the temurin base image at the default `~/.m2/toolchains.xml` path (a single in-container `mvnw` call, no nesting to worry about).

### CI gate
`make checks` (includes `security` — `audit-src` + `audit-packages`) then one `./mvnw clean
verify` call (unit+integration coverage must share one `jacoco.exec` for the 70% gate — splitting
that into separate `make cover`/`make integration` invocations runs the gate before Failsafe
contributes anything and fails it).

### Integration testing with Testcontainers 🐳
- `AbstractBrokerIT` (in `tests/java/io/arcnode/mockderms/`) gives every `*IT` a shared HiveMQ broker — started in a static initializer (Testcontainers "singleton container" pattern), never stopped explicitly (Ryuk reaps it at JVM exit). A JUnit5 `@Container` field would get independently started *and stopped* by each subclass's own `@Testcontainers` extension, killing the broker out from under a still-cached Spring context's `MqttClient` mid-suite. Same pattern as `ems-der-control-api`.
- `@Testcontainers(disabledWithoutDocker = true)` skips the class when Docker is absent.
- Seed state through the service layer, assert over HTTP with `RestTestClient` and over MQTT with an in-test subscriber.
