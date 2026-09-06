plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Apply the Application plugin to add support for building an executable JVM application.
    application

    // Serialization plugin for DTOs
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Logging facade and the binding that the application ships.
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.encoder)

    implementation(project(":core"))
    implementation(project(":capture"))
    implementation(project(":config"))
    implementation(project(":postgres"))
    implementation(project(":rabbitmq"))
    implementation(project(":kafka"))
    implementation(project(":nats"))
    implementation(project(":outbox-service"))
    implementation(project(":inbox-service"))

    implementation(libs.bundles.ktorServerEcosystem)
    implementation(libs.bundles.kotlinxEcosystem)

    // Micrometer metrics
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.prometheus)

    // HikariCP for database connection pooling
    implementation(libs.hikaricp)

    // Testing (E2E)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.logback.classic)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    testImplementation(libs.testcontainers.junit)

    // Exposed ORM for E2E database assertions
    testImplementation(libs.exposed.core)
    testImplementation(libs.exposed.jdbc)
    testImplementation(libs.exposed.json)
    testImplementation(libs.exposed.kotlin.datetime)

    // RabbitMQ client for E2E tests
    testImplementation(libs.amqp.client)

    // Ktor CIO engine for HTTP client in E2E tests
    testImplementation(libs.ktor.client.cio)

    // F-084: `IntegrationDocSqlTest` executes every SQL statement of docs/integration.md against
    // the shipped schema. The SQL Server dialect needs its own container, driver and migration
    // support.
    // Tenth review gate B3. `:sqlserver` was a TEST dependency only, so the published image held
    // no SQL Server provider and no driver. `DatabaseProviderFactory` loads the provider by
    // reflection, so `type: sqlserver` threw `MissingDatabaseProviderException` at startup, while
    // README.md, docs/configuration.md and docs/getting-started.md all promised the support.
    // The image ships both providers now, which is what the documents claim.
    implementation(project(":sqlserver"))
    testImplementation(libs.testcontainers.mssqlserver)
    implementation(libs.mssql.jdbc)
    implementation(libs.flyway.sqlserver)
    testImplementation(libs.mssql.jdbc)
    testImplementation(libs.flyway.sqlserver)
}

// F-072 and F-084: several tests assert that a document and the code agree. Gradle does not know
// that a Markdown file is an input of the test task, so an edit to a document alone left the task
// UP-TO-DATE and the samples went unchecked. Declare the documents that the tests read.
tasks.test {
    // Seventh review gate, second finding. The list named eight documents while
    // `DocumentedExamplesTest` walks README.md and EVERY Markdown file under `docs/`, which is 24
    // today. An edit to one of the sixteen undeclared files left this task UP-TO-DATE, so the
    // check that guards the documentation did not re-run. That is the same shape as the coverage
    // gate defect: the check is wired, and the input that must trigger it is invisible to Gradle.
    // The tree covers a document that somebody adds tomorrow as well.
    // Eighth review gate N6. `GuaranteesTest` resolves a test class of another module by reading
    // its SOURCE file, so those sources are inputs too. A renamed test method there left this
    // task UP-TO-DATE and the guarantee check did not re-run.
    //
    // Ninth review gate N5 records the cost: an edit to one unit test in another module re-runs
    // `:app:test`, which starts containers. That is the price of the source fallback, and a
    // correct check that runs too often beats a fast check that misses a defect. A narrower
    // declaration is not possible, because the set of files the test reads depends on the
    // content of README.md.
    inputs.files(
        rootProject.fileTree("docs") { include("**/*.md") },
        rootProject.files(
            "postgres/src/test/kotlin",
            "sqlserver/src/test/kotlin",
            "outbox-service/src/test/kotlin",
            "inbox-service/src/test/kotlin",
            "core/src/test/kotlin",
            "rabbitmq/src/test/kotlin",
            // Ninth review gate N4. `sourceFileOf` walks the whole repository, so every module
            // that holds a test source is an input. `config` was missing.
            "config/src/test/kotlin"
        ),
        rootProject.files(
            "README.md",
            "docker-compose.yml",
            "docker-compose.override.yml",
            "docker-compose.release.yml",
            ".env.example"
        ),
        // `DocumentedExamplesTest` reads the workflow files, so a workflow edit must re-run this
        // task. Without the declaration the guard that keeps a test-harness variable out of the
        // configuration namespace stays UP-TO-DATE and never sees the change.
        rootProject.fileTree(".github/workflows") { include("**/*.yml", "**/*.yaml") }
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * Fails the build when a documented database provider is missing from the SHIPPED class path.
 *
 * Tenth review gate B3. A test cannot hold this property, because the test class path carries
 * every `testImplementation` too, so a provider that ships only to the tests still resolves there.
 * The runtime configuration is the thing the image contains, so the check reads that.
 */
val verifyShippedProviders by tasks.registering {
    group = "verification"
    description = "Checks that every documented database provider reaches the runtime class path"

    // The component identifiers, not the artifact file names. A project shows as "project
    // :sqlserver", so the check cannot be satisfied by a look-alike such as
    // "flyway-database-sqlserver".
    val runtimeNames = configurations.named("runtimeClasspath").map { configuration ->
        configuration.incoming.resolutionResult.allComponents.map { it.id.displayName }
    }

    doLast {
        val names = runtimeNames.get()
        val required = mapOf(
            "the PostgreSQL provider module" to "project :postgres",
            "the SQL Server provider module" to "project :sqlserver",
            "the PostgreSQL driver" to "org.postgresql:postgresql",
            "the SQL Server driver" to "com.microsoft.sqlserver:mssql-jdbc"
        )
        val missing = required.filterValues { needle -> names.none { it.contains(needle) } }
        require(missing.isEmpty()) {
            "The runtime class path misses ${missing.keys}. The documents promise both databases, " +
                "and DatabaseProviderFactory loads a provider by reflection, so a missing module " +
                "fails at startup rather than at compile time. Either ship it or remove the claim."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyShippedProviders)
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "org.nxtspec.app.AppKt"
}
