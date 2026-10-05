package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.testing.ValidationEnvironment;
import org.hibernate.migration.testing.RecipeExecutionContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.*;
import org.openrewrite.groovy.GroovyParser;
import org.openrewrite.kotlin.KotlinParser;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies coordinate mapping, script/catalog coordination, preservation, and idempotence.
///
/// @author Steve Ebersole
class OrmCoordinatesTest {
    static final String TARGET = targetVersion();
    static String targetVersion() {
        var environment = ValidationEnvironment.configured();
        return environment.value(environment.target("orm80") + ".ormVersion");
    }
    record Outcome(Map<String, String> files, List<SkippedMigrations.Row> rows) {}
    static SourceFile parse(String path, String text, ExecutionContext ctx) {
        Parser parser = path.endsWith(".toml") ? TomlParser.builder().build() : path.endsWith(".kts") ? KotlinParser.builder().isKotlinScript(true).build()
                : path.endsWith(".gradle") ? GroovyParser.builder().build() : XmlParser.builder().build();
        SourceFile source = parser.parse(ctx, text).findFirst().orElseThrow().withSourcePath(Path.of(path));
        assertFalse(source instanceof org.openrewrite.tree.ParseError);
        assertEquals(text, source.printAll()); return source;
    }
    static Outcome run(Map<String, String> files) {
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        List<SourceFile> sources = files.entrySet().stream().map(e -> parse(e.getKey(), e.getValue(), ctx)).toList();
        var result = new MigrateOrmCoordinates(TARGET).run(new InMemoryLargeSourceSet(sources), ctx);
        Map<String, String> output = new LinkedHashMap<>(files);
        for (Result r : result.getChangeset().getAllResults()) output.put(r.getAfter().getSourcePath().toString(), r.getAfter().printAll());
        var secondContext = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        var second = new MigrateOrmCoordinates(TARGET).run(new InMemoryLargeSourceSet(output.entrySet().stream()
                .map(e -> parse(e.getKey(), e.getValue(), secondContext)).toList()), secondContext);
        assertTrue(second.getChangeset().getAllResults().isEmpty(), () -> second.getChangeset().getAllResults().stream().map(Result::diff).toList().toString() + second.getDataTableRows(SkippedMigrations.class).stream().map(r -> r.getSubject() + ":" + r.getReasonCode()).toList());
        return new Outcome(output, result.getDataTableRows(SkippedMigrations.class));
    }
    static Outcome run(String path, String text) { return run(Map.of(path, text)); }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void unresolvedImportedVersionIsReported(String path) {
        String input = "dependencies { implementation(\"org.hibernate:hibernate-core:${importedVersion}\") }\n";
        Outcome outcome = run(path, input);
        assertEquals(input, outcome.files.get(path));
        assertTrue(outcome.rows.stream().anyMatch(r -> r.getReasonCode().equals(OrmCoordinateSupport.VERSION)));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void explicitVersionAlwaysRetained(String path) {
        String input = "dependencies { implementation(\"org.hibernate:hibernate-core:7.4.11.Final\") }\n";
        Outcome outcome = run(path, input);
        assertTrue(outcome.files.get(path).contains("org.hibernate.orm:hibernate-core:" + TARGET), outcome.files.get(path));
        assertFalse(outcome.files.get(path).contains("platform("), outcome.files.get(path));
    }
    @Test void enhancementAndCoordinateMigrationsCompose() {
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        String input = "<project><build><plugins><plugin><groupId>org.hibernate.orm.tooling</groupId><artifactId>hibernate-enhance-maven-plugin</artifactId><version>7.4.11.Final</version><configuration><enableExtendedEnhancement>true</enableExtendedEnhancement></configuration></plugin></plugins></build></project>";
        Recipe combined = new Recipe() {
            @Override public String getDisplayName() { return "Verify aggregate composition"; }
            @Override public String getDescription() { return "Verifies coordinates expose relocated enhancement plugins to option migration."; }
            @Override public List<Recipe> getRecipeList() { return List.of(new MigrateClientEnhancementOption(), new MigrateOrmCoordinates(TARGET)); }
        };
        var result = combined.run(new InMemoryLargeSourceSet(List.of(parse("pom.xml", input, ctx))), ctx);
        String output = result.getChangeset().getAllResults().get(0).getAfter().printAll();
        assertTrue(output.contains("<artifactId>hibernate-maven-plugin</artifactId>"), output);
        assertTrue(output.contains("<enableClientEnhancement>true</enableClientEnhancement>"), output);
        assertFalse(output.contains("enableExtendedEnhancement"), output);
    }

    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradleLibrariesAndProcessors(String path) {
        String input = "dependencies {\n    implementation(\"org.hibernate:hibernate-core:7.4.11.Final\")\n    annotationProcessor(\"org.hibernate.orm:hibernate-jpamodelgen:7.4.11.Final\")\n}\n";
        String expected = "dependencies {\n    implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")\n    annotationProcessor(\"org.hibernate.orm:hibernate-processor:" + TARGET + "\")\n}\n";
        assertEquals(expected, run(path, input).files.get(path));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void gradlePluginsApplyFalseAndExistingPlatform(String path) {
        String input = "plugins { id(\"org.hibernate.orm\") version \"7.4.11.Final\" apply false }\ndependencies {\n    implementation(enforcedPlatform(\"org.hibernate.orm:hibernate-platform:7.4.11.Final\"))\n    implementation(\"org.hibernate:hibernate-core:7.4.11.Final\")\n}\n";
        String output = run(path, input).files.get(path);
        assertTrue(output.contains("version \"" + TARGET + "\" apply false"), output);
        assertTrue(output.contains("enforcedPlatform(\"org.hibernate.orm:hibernate-platform:" + TARGET + "\")"), output);
        assertTrue(output.contains("implementation(\"org.hibernate.orm:hibernate-core:" + TARGET + "\")"), output);
    }
    @Test void mavenScopeBomPluginAndProcessor() {
        String input = "<project><dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version></dependency></dependencies><build><plugins><plugin><groupId>org.hibernate.orm.tooling</groupId><artifactId>hibernate-enhance-maven-plugin</artifactId><version>7.4.11.Final</version><configuration><keep>true</keep></configuration></plugin></plugins></build></project>";
        String output = run("pom.xml", input).files.get("pom.xml");
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId><version>" + TARGET + "</version></dependency>"), output);
        assertTrue(output.contains("<artifactId>hibernate-maven-plugin</artifactId><version>" + TARGET + "</version>"), output);
        assertFalse(output.contains("<type>pom</type><scope>import</scope>"), output);
        assertTrue(output.contains("<configuration><keep>true</keep></configuration>"));
    }
    @Test void ivyRevisionsAndConstraint() {
        String input = "<ivy-module version='2.0'><info organisation='org.hibernate' module='mine'/><dependencies><dependency org='org.hibernate' name='hibernate-core' rev='7.4.11.Final' revConstraint='[7,8)' conf='compile->default'/></dependencies></ivy-module>";
        assertEquals(input.replace("org='org.hibernate'", "org='org.hibernate.orm'").replace("rev='7.4.11.Final'", "rev='" + TARGET + "'").replace("revConstraint='[7,8)'", "revConstraint='" + TARGET + "'"), run("ivy.xml", input).files.get("ivy.xml"));
    }
    @Test void antTaskNamespaceAndAlias() {
        for (String task : List.of("<project xmlns:m='antlib:org.apache.maven.artifact.ant'><m:dependencies>", "<project><taskdef name='resolve' classname='org.apache.maven.artifact.ant.DependenciesTask'/><resolve>")) {
            String input = task + "<dependency groupId='org.hibernate' artifactId='hibernate-core' version='7.4.11.Final'/>" + (task.contains("<resolve>") ? "</resolve>" : "</m:dependencies>") + "</project>";
            assertEquals(input.replace("groupId='org.hibernate'", "groupId='org.hibernate.orm'").replace("version='7.4.11.Final'", "version='" + TARGET + "'"), run("build.xml", input).files.get("build.xml"));
        }
    }
    @Test void catalogSharedVersionAndMixedBundle() {
        String catalog = "[versions]\norm = \"7.4.11.Final\"\n[libraries]\ncore = { module = \"org.hibernate:hibernate-core\", version.ref = \"orm\" }\nvalidator = { module = \"org.hibernate.validator:hibernate-validator\", version.ref = \"orm\" }\n[plugins]\nhibernate = { id = \"org.hibernate.orm\", version.ref = \"orm\" }\n[bundles]\npersistence = [\"core\", \"validator\"]\n";
        Outcome result = run(Map.of("gradle/libs.versions.toml", catalog, "build.gradle", "plugins { alias(libs.plugins.hibernate) }\ndependencies { implementation(libs.bundles.persistence) }\n"));
        String output = result.files.get("gradle/libs.versions.toml");
        assertTrue(output.contains("core = { module = \"org.hibernate.orm:hibernate-core\", version = \"" + TARGET + "\" }"), output);
        assertTrue(output.contains("validator = { module = \"org.hibernate.validator:hibernate-validator\", version.ref = \"orm\" }"), output);
        assertTrue(output.contains("hibernate = { id = \"org.hibernate.orm\", version = \"" + TARGET + "\" }"), output);
        assertFalse(result.files.get("build.gradle").contains("implementation(platform("), result.files.toString());
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void catalogCompleteLocalConsumersOmitVersions(String path) {
        var output = run(Map.of("settings.gradle", "rootProject.name = 'example'\n", path,
                "dependencies { implementation(libs.core) }\n", "gradle/libs.versions.toml",
                "[libraries]\ncore = { module = \"org.hibernate:hibernate-core\", version = \"7.4.11.Final\" }\n")).files;
        assertEquals("[libraries]\ncore = { module = \"org.hibernate.orm:hibernate-core\", version = \"" + TARGET + "\" }\n", output.get("gradle/libs.versions.toml"));
        assertFalse(output.get(path).contains("implementation(platform("));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void namedNotationAndTrailingClosure(String path) {
        String declaration = path.endsWith(".kts") ? "implementation(group = \"org.hibernate\", name = \"hibernate-core\", version = \"7.4.11.Final\")"
                : "implementation(group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final')";
        String input = "dependencies {\n    " + declaration + " { transitive = false }\n}\n";
        String output = run(path, input).files.get(path);
        assertTrue(output.contains("org.hibernate.orm"), output); assertFalse(output.contains("7.4.11.Final"), output);
        assertTrue(output.contains(TARGET), output);
        assertTrue(output.contains("transitive = false"), output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void simpleReferencesAndComplexExpression(String path) {
        String prefix = path.endsWith(".kts") ? "val orm = \"7.4.11.Final\"\n" : "def orm = '7.4.11.Final'\n";
        String input = prefix + "dependencies {\n    annotationProcessor(\"org.hibernate.orm:hibernate-jpamodelgen:$orm\")\n    implementation(\"org.hibernate:hibernate-core:${orm + 'suffix'}\")\n}\n";
        if (path.endsWith(".kts")) input = input.replace("'suffix'", "\"suffix\"");
        var outcome = run(path, input);
        assertTrue(outcome.files.get(path).contains("org.hibernate.orm:hibernate-processor:"), outcome.files.toString());
        assertTrue(outcome.files.get(path).contains("org.hibernate:hibernate-core:${orm + "));
        assertEquals(1, outcome.rows.size()); assertEquals(OrmCoordinateSupport.VERSION, outcome.rows.get(0).getReasonCode());
    }
    @Test void mavenCompilerPathsOnlyAndParentProperties() {
        String input = "<project><parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent><properties><orm>7.4.11.Final</orm></properties><dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>${orm}</version></dependency></dependencies><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><configuration><annotationProcessorPaths><path><groupId>org.hibernate</groupId><artifactId>hibernate-jpamodelgen</artifactId><version>${orm}</version></path></annotationProcessorPaths></configuration></plugin><plugin><artifactId>other</artifactId><configuration><annotationProcessorPaths><path><groupId>org.hibernate</groupId><artifactId>hibernate-jpamodelgen</artifactId><version>${orm}</version></path></annotationProcessorPaths></configuration></plugin></plugins></build></project>";
        String output = run("pom.xml", input).files.get("pom.xml");
        assertTrue(output.contains("<orm>7.4.11.Final</orm>"));
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId><version>" + TARGET + "</version>"), output);
        assertTrue(output.contains("<artifactId>hibernate-processor</artifactId><version>" + TARGET + "</version>"), output);
        assertTrue(output.contains("<artifactId>hibernate-jpamodelgen</artifactId><version>${orm}</version>"), output);
    }
    @Test void mavenExclusiveToolingPropertyAndNamespace() {
        String input = "<m:project xmlns:m='http://maven.apache.org/POM/4.0.0'><m:properties><m:orm>7.4.11.Final</m:orm></m:properties><m:build><m:plugins><m:plugin><m:groupId>org.hibernate.orm</m:groupId><m:artifactId>hibernate-maven-plugin</m:artifactId><m:version>${orm}</m:version></m:plugin></m:plugins></m:build></m:project>";
        assertEquals(input.replace("7.4.11.Final", TARGET), run("custom.xml", input).files.get("custom.xml"));
    }
    @Test void allCanonicalAndLegacyCoordinatesInIvy() {
        Map<String, String> inputs = new LinkedHashMap<>(); Map<String, String> expected = new LinkedHashMap<>();
        for (String group : List.of("org.hibernate", "org.hibernate.orm")) for (String artifact : group.equals("org.hibernate") ? OrmCoordinateSupport.LEGACY : OrmCoordinateSupport.CANONICAL) {
            String path = group + "/" + artifact + "/ivy.xml";
            String input = "<ivy-module version='2.0'><info organisation='example' module='test'/><dependencies><dependency org='" + group + "' name='" + artifact + "' rev='7.4.11.Final'/></dependencies></ivy-module>";
            inputs.put(path, input); expected.put(path, input.replace("org='" + group + "'", "org='org.hibernate.orm'").replace("7.4.11.Final", TARGET));
        }
        assertEquals(expected, run(inputs).files);
    }
    @Test void conflictsUnsupportedArtifactAndAntLocalRetrieval() {
        String ivy = "<ivy-module version='2.0'><info organisation='example' module='test'/><dependencies><dependency org='org.hibernate' name='hibernate-core' rev='7.4.11.Final'/><dependency org='org.hibernate.orm' name='hibernate-core' rev='7.4.11.Final'/><dependency org='org.hibernate' name='hibernate-entitymanager' rev='5.6.15.Final'/><dependency org='org.hibernate' name='hibernate-envers' rev='7.4.11.Final'><artifact name='custom' type='jar' url='file:/local.jar'/></dependency></dependencies></ivy-module>";
        var outcome = run("ivy.xml", ivy); assertEquals(ivy, outcome.files.get("ivy.xml"));
        assertEquals(List.of(OrmCoordinateSupport.CONFLICT, OrmCoordinateSupport.CONFLICT, OrmCoordinateSupport.CONFLICT, OrmCoordinateSupport.SYNTAX), outcome.rows.stream().map(SkippedMigrations.Row::getReasonCode).toList());
    }
    @Test void catalogsWithOrmOnlyReferenceAndMissingProject() {
        String catalog = "[versions]\norm = '7.4.11.Final'\n[libraries]\ncore = { group = 'org.hibernate', name = 'hibernate-core', version.ref = 'orm' }\nprocessor = { module = 'org.hibernate:hibernate-jpamodelgen', version.ref = 'orm' }\n";
        String output = run(Map.of("settings.gradle", "include('missing')\n", "build.gradle", "dependencies { implementation(libs.core); annotationProcessor(libs.processor) }\n", "gradle/libs.versions.toml", catalog)).files.get("gradle/libs.versions.toml");
        assertTrue(output.contains("orm = '" + TARGET + "'"), output); assertTrue(output.contains("version.ref = 'orm'"), output);
        assertTrue(output.contains("hibernate-processor"), output);
    }
    @Test void groovyExplicitMapAndCommandNotation() {
        for (String call : List.of("implementation([group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final'])",
                "implementation group: 'org.hibernate', name: 'hibernate-core', version: '7.4.11.Final'")) {
            String output = run("build.gradle", "dependencies { " + call + " }\n").files.get("build.gradle");
            assertTrue(output.contains("group: 'org.hibernate.orm'"), output);
            assertTrue(output.contains(TARGET), output);
            assertFalse(output.contains("implementation(platform("), output);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void classifiedArtifactsAndVersionComments(String path) {
        String call = path.endsWith(".kts") ? "implementation(group = \"org.hibernate\", name = \"hibernate-core\", version = /*keep version explanation*/ \"7.4.11.Final\")"
                : "implementation(group: 'org.hibernate', name: 'hibernate-core', version: /*keep version explanation*/ '7.4.11.Final')";
        String input = "dependencies {\n    " + call + "\n    runtimeOnly(\"org.hibernate:hibernate-envers:7.4.11.Final:tests@jar\")\n}\n";
        String output = run(path, input).files.get(path);
        assertTrue(output.contains("/*keep version explanation*/"), output);
        assertTrue(output.contains("org.hibernate.orm:hibernate-envers:" + TARGET + ":tests@jar"), output);
        assertFalse(output.contains("7.4.11.Final"), output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void exclusiveLocalToolingVersionReferences(String path) {
        String prefix = path.endsWith(".kts") ? "val orm = \"7.4.11.Final\"\n" : "def orm = '7.4.11.Final'\n";
        String input = prefix + "dependencies { annotationProcessor(\"org.hibernate.orm:hibernate-jpamodelgen:$orm\") }\n";
        String output = run(path, input).files.get(path);
        assertEquals(input.replace("7.4.11.Final", TARGET).replace("hibernate-jpamodelgen", "hibernate-processor"), output);
    }
    @Test void mavenProfileBomAndClassifiedDependency() {
        String input = "<project><profiles><profile><id>extra</id><dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version><classifier>tests</classifier></dependency></dependencies></profile></profiles></project>";
        String output = run("pom.xml", input).files.get("pom.xml");
        assertTrue(output.contains("<artifactId>hibernate-core</artifactId><version>" + TARGET + "</version><classifier>tests</classifier>"), output);
        assertFalse(output.contains("<dependencyManagement>"), output);
    }
    @Test void antPropertyOwnershipAndUnrelatedConsumer() {
        String input = "<project xmlns:m='antlib:org.apache.maven.artifact.ant'><property name='orm' value='7.4.11.Final'/><m:dependencies><dependency groupId='org.hibernate' artifactId='hibernate-core' version='${orm}'/></m:dependencies></project>";
        assertEquals(input.replace("value='7.4.11.Final'", "value='" + TARGET + "'").replace("groupId='org.hibernate'", "groupId='org.hibernate.orm'"), run("build.xml", input).files.get("build.xml"));
        String shared = input.replace("</project>", "<echo message='${orm}'/></project>");
        assertEquals(shared.replace("groupId='org.hibernate'", "groupId='org.hibernate.orm'").replace("version='${orm}'", "version='" + TARGET + "'"), run("build.xml", shared).files.get("build.xml"));
    }
    @Test void namedCatalogImportAndRichConstraintDiagnostic() {
        String settings = "dependencyResolutionManagement { versionCatalogs { create('deps') { from(files('gradle/deps.toml')) } } }\n";
        String catalog = "[libraries]\ncore = { module = 'org.hibernate:hibernate-core', version = { strictly = '7.4.11.Final' } }\n";
        var result = run(Map.of("settings.gradle", settings, "build.gradle", "dependencies { implementation(deps.core) }\n", "gradle/deps.toml", catalog));
        assertEquals(catalog, result.files.get("gradle/deps.toml"));
        assertTrue(result.rows.stream().anyMatch(r -> r.getReasonCode().equals(OrmCoordinateSupport.VERSION)));
    }
    @Test void originalLocationsCrLfAndNamespaceIsolation() {
        String input = "<project>\r\n  <dependencies>\r\n    <dependency><groupId>${group}</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version></dependency>\r\n  </dependencies>\r\n</project>";
        var result = run("pom.xml", input); assertEquals(input, result.files.get("pom.xml"));
        assertEquals(3, result.rows.get(0).getLine()); assertEquals(6, result.rows.get(0).getColumn());
        assertEquals(OrmCoordinateSupport.IDENTITY, result.rows.get(0).getReasonCode());
        String foreign = "<project><dependencies><dependency><groupId xmlns='urn:foreign'>org.hibernate</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version></dependency></dependencies></project>";
        assertEquals(foreign, run("pom.xml", foreign).files.get("pom.xml"));
    }
    @Test void publishedPlatformMetadata() throws Exception {
        var ctx = RecipeExecutionContexts.standard(e -> { throw new AssertionError(e); });
        var downloader = new org.openrewrite.maven.internal.MavenPomDownloader(ctx);
        var pom = downloader.download(new org.openrewrite.maven.tree.GroupArtifactVersion("org.hibernate.orm", "hibernate-platform", TARGET),
                null, null, List.of(org.openrewrite.maven.tree.MavenRepository.MAVEN_CENTRAL)).resolve(List.of(), downloader, ctx);
        assertEquals(TARGET, pom.getManagedVersion("org.hibernate.orm", "hibernate-core", "jar", null));
        assertTrue(OrmCoordinateSupport.managed(TARGET, List.of(), ctx).contains("hibernate-core"));
    }
    private static String entityFixture(String path, String group, boolean core, boolean conflict) {
        if (path.endsWith("gradle") || path.endsWith("kts")) {
            return "dependencies {\n    implementation(\"" + group + ":hibernate-entitymanager:5.6.15.Final\")" + (conflict ? " { exclude(group = \"example\", module = \"excluded\") }" : "")
                    + " // keep entity comment\n" + (core ? "    implementation(\"org.hibernate.orm:hibernate-core:7.4.11.Final\")\n" : "") + "}\n";
        }
        if (path.equals("pom.xml")) return "<project><dependencies><!-- keep preceding comment --><dependency><groupId>" + group
                + "</groupId><artifactId>hibernate-entitymanager</artifactId><version>5.6.15.Final</version><!-- keep entity comment -->"
                + (conflict ? "<exclusions><exclusion><groupId>example</groupId><artifactId>excluded</artifactId></exclusion></exclusions>" : "") + "</dependency>"
                + (core ? "<dependency><groupId>org.hibernate.orm</groupId><artifactId>hibernate-core</artifactId><version>7.4.11.Final</version><scope>compile</scope><type>jar</type></dependency>" : "") + "</dependencies></project>";
        boolean ivy = path.equals("ivy.xml");
        String prefix = ivy ? "<ivy-module version='2.0'><info organisation='example' module='test'/><dependencies>" : "<project xmlns:m='antlib:org.apache.maven.artifact.ant'><m:dependencies>";
        String attributes = ivy ? "org='" + group + "' name='hibernate-entitymanager' rev='5.6.15.Final'" : "groupId='" + group + "' artifactId='hibernate-entitymanager' version='5.6.15.Final'";
        String existing = ivy ? "<dependency org='org.hibernate.orm' name='hibernate-core' rev='7.4.11.Final'/>" : "<dependency groupId='org.hibernate.orm' artifactId='hibernate-core' version='7.4.11.Final'/>";
        return prefix + "<dependency " + attributes + (conflict ? (ivy ? " conf='test->default'" : " scope='test'") : "") + "><!-- keep entity comment --></dependency>"
                + (core ? existing : "") + (ivy ? "</dependencies></ivy-module>" : "</m:dependencies></project>");
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts", "pom.xml", "ivy.xml", "build.xml"})
    void entityManagerReplacementAndRemoval(String path) {
        for (String group : List.of("org.hibernate", "org.hibernate.orm")) for (boolean core : List.of(false, true)) {
            Outcome result = run(path, entityFixture(path, group, core, false));
            String output = result.files.get(path);
            assertFalse(output.contains("hibernate-entitymanager"), output);
            assertEquals(1, output.split("hibernate-core", -1).length - 1, output);
            assertTrue(output.contains("keep entity comment"), output);
            assertTrue(output.contains("org.hibernate.orm"), output);
            assertTrue(result.rows.isEmpty(), result.rows.stream().map(SkippedMigrations.Row::getReasonCode).toList().toString());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts", "pom.xml", "ivy.xml", "build.xml"})
    void conflictingEntityManagerBehaviorIsPreserved(String path) {
        String input = entityFixture(path, "org.hibernate", true, true);
        Outcome result = run(path, input);
        assertEquals(input, result.files.get(path));
        assertEquals(2, result.rows.size());
        assertTrue(result.rows.stream().allMatch(r -> r.getReasonCode().equals(OrmCoordinateSupport.CONFLICT)));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void entityManagerCatalogAliasesAndBundlesRemainValid(String path) {
        String catalog = "[libraries]\nentity = { module = \"org.hibernate:hibernate-entitymanager\", version = \"5.6.15.Final\" }\ncore = { module = \"org.hibernate.orm:hibernate-core\", version = \"7.4.11.Final\" }\n[bundles]\norm = [\"entity\", \"core\"]\n";
        Outcome result = run(Map.of("settings.gradle", "rootProject.name = 'test'\n", path, "dependencies { implementation(libs.bundles.orm) }\n", "gradle/libs.versions.toml", catalog));
        String output = result.files.get("gradle/libs.versions.toml");
        assertFalse(output.contains("hibernate-entitymanager"), output);
        assertTrue(output.contains("entity = { module = \"org.hibernate.orm:hibernate-core\", version = \"" + TARGET + "\" }"), output);
        assertTrue(output.contains("orm = [\"entity\", \"core\"]"), output);
        assertTrue(result.files.get(path).contains("implementation(libs.bundles.orm)"));
        assertTrue(result.rows.isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void equivalentEntityClosuresRetainClosingComments(String path) {
        String input = "dependencies {\n    implementation(\"org.hibernate:hibernate-entitymanager:5.6.15.Final\") { exclude(group = \"example\", module = \"excluded\") /* keep closing comment */ }\n"
                + "    implementation(\"org.hibernate.orm:hibernate-core:7.4.11.Final\") { exclude(group = \"example\", module = \"excluded\") }\n}\n";
        String output = run(path, input).files.get(path);
        assertFalse(output.contains("hibernate-entitymanager"), output);
        assertTrue(output.contains("keep closing comment"), output);
        assertEquals(1, output.split("exclude\\(", -1).length - 1, output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void entityRemovalAcrossBlocksInSameConfiguration(String path) {
        String input = "dependencies { implementation(\"org.hibernate:hibernate-entitymanager:5.6.15.Final\") }\n"
                + "dependencies { implementation(\"org.hibernate.orm:hibernate-core:7.4.11.Final\") }\n";
        String output = run(path, input).files.get(path);
        assertFalse(output.contains("hibernate-entitymanager"), output);
        assertEquals(1, output.split("hibernate-core", -1).length - 1, output);
    }
    @Test void entityClassifierEquivalenceAndConflict() {
        String input = "dependencies { implementation(group: 'org.hibernate', name: 'hibernate-entitymanager', version: '5.6.15.Final', classifier: 'tests'); implementation('org.hibernate.orm:hibernate-core:7.4.11.Final:tests') }\n";
        String output = run("build.gradle", input).files.get("build.gradle");
        assertFalse(output.contains("hibernate-entitymanager"), output);
        assertTrue(output.contains("hibernate-core:" + TARGET + ":tests"), output);
        String conflict = input.replace("classifier: 'tests'", "classifier: 'sources'");
        assertEquals(conflict, run("build.gradle", conflict).files.get("build.gradle"));
    }
    @Test void conflictingEntityAttributesDoNotUpdateSharedAntProperty() {
        String input = "<project xmlns:m='antlib:org.apache.maven.artifact.ant'><property name='orm' value='7.4.11.Final'/><m:dependencies>"
                + "<dependency groupId='org.hibernate' artifactId='hibernate-entitymanager' version='${orm}' scope='test'/>"
                + "<dependency groupId='org.hibernate.orm' artifactId='hibernate-core' version='${orm}'/>"
                + "<dependency groupId='org.hibernate' artifactId='hibernate-envers' version='${orm}'/></m:dependencies></project>";
        String output = run("build.xml", input).files.get("build.xml");
        assertTrue(output.contains("<property name='orm' value='7.4.11.Final'/>"), output);
        assertTrue(output.contains("artifactId='hibernate-entitymanager' version='${orm}' scope='test'"), output);
        assertTrue(output.contains("artifactId='hibernate-envers' version='" + TARGET + "'"), output);
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void differentDependencyReceiversRequireReview(String path) {
        String input = "dependencies { custom.implementation(\"org.hibernate:hibernate-entitymanager:5.6.15.Final\"); implementation(\"org.hibernate.orm:hibernate-core:7.4.11.Final\") }\n";
        Outcome result = run(path, input);
        assertEquals(input, result.files.get(path));
        assertTrue(result.rows.stream().allMatch(r -> r.getReasonCode().equals(OrmCoordinateSupport.CONFLICT)));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts"})
    void platformCoreDoesNotReplaceRuntimeEntityDependency(String path) {
        String input = "dependencies { implementation(\"org.hibernate:hibernate-entitymanager:5.6.15.Final\"); implementation(platform(\"org.hibernate.orm:hibernate-core:7.4.11.Final\")) }\n";
        Outcome result = run(path, input);
        assertEquals(input, result.files.get(path));
        assertEquals(2, result.rows.size());
        assertTrue(result.rows.stream().allMatch(r -> r.getReasonCode().equals(OrmCoordinateSupport.CONFLICT)));
    }
    @ParameterizedTest @ValueSource(strings = {"build.gradle", "build.gradle.kts", "pom.xml"})
    void thirdPartyBomDoesNotTriggerHibernatePlatformInjection(String path) {
        String input;
        if (path.equals("pom.xml")) {
            input = "<project><dependencyManagement><dependencies>"
                    + "<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>"
                    + "<version>3.4.0</version><type>pom</type><scope>import</scope></dependency>"
                    + "</dependencies></dependencyManagement>"
                    + "<dependencies><dependency><groupId>org.hibernate</groupId><artifactId>hibernate-core</artifactId></dependency></dependencies></project>";
        }
        else {
            input = "dependencies {\n    implementation(platform(\"org.springframework.boot:spring-boot-dependencies:3.4.0\"))\n    implementation(\"org.hibernate:hibernate-core\")\n}\n";
        }
        Outcome result = run(path, input);
        String output = result.files.get(path);
        assertFalse(output.contains("hibernate-platform"), output);
        assertTrue(output.contains("org.hibernate.orm"), output);
        assertTrue(output.contains("spring-boot-dependencies"), output);
    }
    @Test void rejectedOptionsAndUnrelatedArtifacts() {
        for (String version : Arrays.asList(null, "", "8.+", "${orm}", "9.0.0.Final", "8.0.0-SNAPSHOT")) assertFalse(new MigrateOrmCoordinates(version).validate().isValid());
        String xml = "<project><dependencies><dependency><groupId>org.hibernate.validator</groupId><artifactId>hibernate-validator</artifactId><version>8.0.0.Final</version></dependency></dependencies></project>";
        var result = run("pom.xml", xml); assertEquals(xml, result.files.get("pom.xml")); assertTrue(result.rows.isEmpty());
    }
}
