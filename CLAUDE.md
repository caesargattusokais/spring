# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

The project uses Gradle 9.5.1 with a custom buildSrc. The root `build.gradle` applies conventions from `org.springframework.build.conventions` to all Java projects.

```bash
# Full build: compile, test, checkstyle, architecture checks, javadoc
./gradlew check

# Build docs (Asciidoctor/Antora)
./gradlew antora

# Run all tests in a specific module
./gradlew :spring-beans:test

# Run a single test class
./gradlew :spring-beans:test --tests "org.springframework.beans.factory.xml.XmlBeanDefinitionTests"

# Run tests matching a pattern (e.g., all tests in a package)
./gradlew :spring-core:test --tests "org.springframework.core.io.*"

# Run RuntimeHints tests with agent
./gradlew runtimeHintsTest

# Aggregate javadoc across all modules
./gradlew :framework-api:javadoc

# Enable test groups (e.g., long-running tests)
./gradlew :spring-core:test -PtestGroups=longRunning

# Faster local builds (skip docs, javadoc, checkstyle, architecture checks)
./gradlew build -x antora -x javadoc -x checkstyleMain -x checkstyleTest -x checkArchitectureMain
```

**Gradle Java toolchain**: The build uses Gradle's Java toolchain to compile against Java 17 (baseline) with MultiRelease JAR support for Java 21 and 24 in `spring-core`.

**Java version**: The project targets Java 17 baseline. `spring-core` uses the `me.champeau.mrjar` plugin for multi-release JARs with Java 21 and 24 variants. Do NOT place Java 21+ only code in the main source set — use `src/main/java21/` or `src/main/java24/`.

**Test naming**: tests are in JUnit Jupiter and follow the pattern `**/*Tests.class` or `**/*Test.class`. Test retry (3x) is only enabled in CI.

## Module Architecture

The framework comprises ~23 modules with a strict dependency layering. Modules with `spring-` prefix are published; `framework-*` modules are build infrastructure.

### Foundation Layer (no Spring dependencies)

- **spring-core**: `org.springframework.core`, `io`, `env`, `codec`, `convert`, `task`, `aot`. Core utilities, type resolution (`ResolvableType`), reactive adapters, AOT hint system. Repackages CGLIB, ASM, Objenesis, and JavaPoet under internal packages.
- **spring-expression**: SpEL (Spring Expression Language) parser and evaluation engine.

### Container Layer

- **spring-beans**: `org.springframework.beans.factory` — the IoC container: `BeanFactory`, `DefaultListableBeanFactory`, `BeanDefinition`, `BeanPostProcessor`, dependency injection, bean scopes, property editors. `DefaultListableBeanFactory` is the central implementation from which all context types descend.
- **spring-context**: `org.springframework.context` — `ApplicationContext` hierarchy (`GenericApplicationContext`, `AnnotationConfigApplicationContext`), `@Configuration`/`@Bean` processing, component scanning, `@Autowired`, scheduling, JNDI, validation, JMX, scripting. The `context/annotation/ConfigurationClassPostProcessor` is the core of Java-config support.
- **spring-context-indexer**: Annotation processor for generating `META-INF/spring.components` index files at compile time.

### AOP and Instrumentation

- **spring-aop**: AOP alliance interfaces, pointcuts, method matchers, proxy-based AOP (JDK and CGLIB proxies).
- **spring-aspects**: AspectJ integration — annotation-driven aspects (`@Transactional`, `@Cacheable`), `@Configurable` load-time weaving. Written in AspectJ (`.aj` sources).
- **spring-instrument**: Java instrumentation agent for classloader-level weaving.

### Data Layer

- **spring-tx**: Transaction abstraction: `PlatformTransactionManager`, `TransactionDefinition`, reactive transaction support. Hierarchical transaction management.
- **spring-jdbc**: JDBC abstraction: `JdbcTemplate`, `NamedParameterJdbcTemplate`, `DataSource` utilities, row mapping.
- **spring-orm**: Hibernate/JPA integration, `LocalSessionFactoryBean`, `@PersistenceContext`.
- **spring-r2dbc**: Reactive relational database client.
- **spring-oxm**: Object/XML mapping support (JAXB, XStream, etc.).
- **spring-jms**: JMS message listener containers, `JmsTemplate`.

### Web Layer

- **spring-web**: `org.springframework.http`, `org.springframework.web` — common HTTP types (`HttpStatus`, `MediaType`, `ResponseEntity`), `RestClient`, `WebClient`, filters, CORS, multipart, `WebApplicationInitializer`.
- **spring-webmvc**: `org.springframework.web.servlet` — DispatcherServlet, annotated controllers (`@RequestMapping`), view resolution, `@RestController`.
- **spring-webflux**: Reactive web framework on Project Reactor (`Mono`, `Flux`). `WebFilter`, `RouterFunction`, `WebSocket` support.
- **spring-websocket**: WebSocket/STOMP messaging infrastructure.
- **spring-messaging**: Message channels, handlers, `@MessageMapping`, websocket/pub-sub abstraction.

### Test and Support

- **spring-test**: `org.springframework.test.context` — `SpringJUnitConfig`, `@ContextConfiguration`, `MockMvc`, `MockWebServer` (OkHttp3), `@Sql`, `@DynamicPropertySource`. Core test utilities in `org.springframework.test.web`, `mock`, `context`.
- **spring-core-test**: Small utilities built on spring-core for use in tests. Provides the `RuntimeHintsAgent` Java agent for testing reflection hints.

## Build Conventions and Plugins

The `buildSrc/` directory contains custom plugins and conventions applied via `org.springframework.build.conventions`:

- **ArchUnit**: Architecture rules enforced via `ArchitectureRules` - violations prevent CI passes. Architecture violations are defined in `buildSrc/src/main/groovy/org/springframework/build/architecture/`.
- **Java/Kotlin Conventions**: Unified compiler settings and configuration across modules
- **Test Conventions**: Standardized test setup and execution patterns
- **Optional Dependencies**: `org.springframework.build.optional-dependencies` plugin adds dependencies to compile/runtime but not transitive classpath (uses `optional` configuration instead of `provided`)
- **MultiRelease Jar**: `org.springframework.build.multiReleaseJar` plugin configures MultiRelease JAR support with `releaseVersions` DSL:
  ```groovy
  multiRelease {
      releaseVersions 21, 24
  }
  ```
- **RuntimeHints Agent**: `RuntimeHintsAgentPlugin` runs tests tagged with `"RuntimeHintsTests"` (usually annotated with `@EnabledIfRuntimeHintsAgent`) to verify reflection hints at runtime. Configure with:
  ```groovy
  runtimeHintsAgent {
      includedPackages = ["org.springframework", "io.spring"]
      excludedPackages = ["org.example"]
  }
  ```

**Enabling Java Preview Features**: Some modules support preview features via the `springFramework { enableJavaPreviewFeatures = true }` DSL in their build file.

## Key Dependency Graph

```
spring-expression ← spring-context-indexer
spring-core ← spring-beans ← spring-context ← (web/data modules)
spring-core ← spring-aop ← spring-aspects
spring-beans ← spring-tx ← spring-jdbc/spring-orm
spring-expression ← spring-web ← spring-webmvc/spring-webflux
```

## AOT / Native Image Support

Spring Framework has deep AOT (Ahead-of-Time) processing for GraalVM native images:

- `spring-core/.../aot/hint/` — runtime hint system: `ReflectionHints`, `ResourceHints`, `ProxyHints`, `SerializationHints`.
- `spring-beans/.../factory/aot/` — bean registration AOT: `BeanDefinitionMethodGenerator`, `BeanRegistrationAotProcessor`, `InstanceSupplierCodeGenerator`.
- `spring-context/.../context/aot/` — `ApplicationContextAotGenerator`, `ContextAotProcessor`, `RuntimeHintsBeanFactoryInitializationAotProcessor`.

AOT processors generate code at build time that replaces runtime reflection, classpath scanning, and dynamic proxies. See `BeanFactoryInitializationAotContribution` and `BeanRegistrationAotContribution` for the SPI.

## Development Conventions

- **Code style**: Apache 2.0 license header on all `.java` files. Checkstyle configuration at `buildSrc/config/checkstyle/checkstyle.xml` using `io.spring.javaformat` checks. Editor settings at `src/eclipse/` (Eclipse) and documented in the [IntelliJ IDEA Editor Settings wiki page](https://github.com/spring-projects/spring-framework/wiki/IntelliJ-IDEA-Editor-Settings).
- **Git commits**: Subject line ≤ 55 chars, body wrapped at 72 chars. Must include `Signed-off-by` trailer (DCO) to indicate DCO agreement. Target `main` branch — backports go to version branches like `7.0.x`. Format: `Subject line\n\nBody line 1\nBody line 2\n\nCloses gh-NNNN` for PRs fixing issues.
- **Null-safety**: Using `org.jspecify.annotations.Nullable` (JSpecify 1.0). The `io.spring.nullability` Gradle plugin enforces conventions.
- **Kotlin**: `spring-core` and `spring-webflux` use Kotlin for coroutines support. Kotlin version is in `gradle.properties` (`kotlinVersion`).
- **Property editors**: Custom property editors are registered via `META-INF/spring.properties` and implemented in `org.springframework.beans.propertyeditors`.

## Key Interfaces to Know

| Interface | Module | Role |
|---|---|---|
| `BeanFactory` / `DefaultListableBeanFactory` | beans | Core IoC container |
| `ApplicationContext` | context | Higher-level container with event publishing, i18n |
| `BeanPostProcessor` | beans | Bean lifecycle customization hook |
| `BeanFactoryPostProcessor` | beans | Container configuration-time hook |
| `FactoryBean<T>` | beans | Factory pattern for bean creation |
| `ImportBeanDefinitionRegistrar` | context | Programmatic bean registration via `@Import` |
| `Environment` | core | Property sources and profiles |
| `Resource` | core | Abstract file/classpath/URL resource |
| `HttpMessageConverter` | web | HTTP request/response body conversion |
| `HandlerMapping` | webmvc | Map requests to handler methods |

## Test Infrastructure

Tests use JUnit Jupiter with `@ExtendWith(SpringExtension.class)`. The `SpringJUnitConfig` annotation combines `@ExtendWith` + `@ContextConfiguration`. Test fixtures are published via `java-test-fixtures` but excluded from Maven publications.

## Documentation

Reference documentation is authored in [Asciidoctor](https://asciidoctor.org/) format using [Antora](https://docs.antora.org/antora/latest/). The source files reside in `framework-docs/modules/ROOT`.

For local documentation changes:
```bash
./gradlew antora
```
Then browse the results under `framework-docs/build/site/index.html`.

Asciidoctor also supports live editing. See [AsciiDoc Tooling](https://asciidoctor.org/asciidoctor/latest/tooling/) for details.
