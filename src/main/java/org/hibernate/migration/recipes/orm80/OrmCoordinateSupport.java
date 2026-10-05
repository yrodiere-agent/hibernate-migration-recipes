package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.hibernate.migration.recipes.xml.DescriptorVisitor;
import org.openrewrite.*;
import org.openrewrite.maven.MavenExecutionContextView;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.internal.MavenPomDownloader;
import org.openrewrite.maven.tree.*;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/// Exact coordinate policy, target validation, platform metadata, and diagnostics.
///
/// @author Steve Ebersole
final class OrmCoordinateSupport {
    static final String GROUP = "org.hibernate.orm";
    static final String PLATFORM = "hibernate-platform";
    static final String IDENTITY = "ORM_COORDINATE_IDENTITY_UNRESOLVED";
    static final String ARTIFACT = "ORM_COORDINATE_ARTIFACT_UNSUPPORTED";
    static final String CONFLICT = "ORM_COORDINATE_CONFLICT";
    static final String VERSION = "ORM_COORDINATE_VERSION_UNRESOLVED";
    static final String SYNTAX = "ORM_COORDINATE_SYNTAX_UNSUPPORTED";

    static final Set<String> CANONICAL = Set.of("hibernate-core", "hibernate-testing", "hibernate-dialect-testkit",
            "hibernate-envers", "hibernate-spatial", "hibernate-vector", "hibernate-community-dialects", "hibernate-scan-jandex",
            "hibernate-agroal", "hibernate-c3p0", "hibernate-hikaricp", "hibernate-ucp", "hibernate-jcache", "hibernate-micrometer",
            "hibernate-graalvm", "hibernate-processor", "hibernate-gradle-plugin", "hibernate-maven-plugin", "hibernate-ant",
            "hibernate-assistant", "hibernate-reveng", "hibernate-jfr", PLATFORM);
    static final Set<String> LEGACY = Set.of("hibernate-core", "hibernate-testing", "hibernate-envers", "hibernate-spatial",
            "hibernate-vector", "hibernate-community-dialects", "hibernate-agroal", "hibernate-c3p0", "hibernate-hikaricp",
            "hibernate-ucp", "hibernate-jcache", "hibernate-micrometer", "hibernate-graalvm", "hibernate-processor", "hibernate-ant",
            "hibernate-assistant", "hibernate-reveng", "hibernate-jfr");
    static final Set<String> TOOLING = Set.of("hibernate-processor", "hibernate-gradle-plugin", "hibernate-maven-plugin", "hibernate-ant");
    static final Pattern RELEASE = Pattern.compile("8\\.0\\.[0-9]+\\.(?:Final|(?:Alpha|Beta|CR)[0-9]+)");

    private OrmCoordinateSupport() {}

    static Validated<String> validate(String version) {
        return Validated.test("targetVersion", "must be an exact ORM 8.0 release", version,
                v -> v != null && RELEASE.matcher(v).matches());
    }

    static boolean entityManager(String artifact) { return "hibernate-entitymanager".equals(artifact); }

    static String target(String group, String artifact) {
        if ((GROUP.equals(group) || "org.hibernate".equals(group)) && entityManager(artifact)) return "hibernate-core";
        if ((GROUP.equals(group) || "org.hibernate".equals(group)) && "hibernate-jpamodelgen".equals(artifact))
            return "hibernate-processor";
        if (Set.of(GROUP, "org.hibernate", "org.hibernate.orm.tooling").contains(group)
                && "hibernate-enhance-maven-plugin".equals(artifact)) return "hibernate-maven-plugin";
        return GROUP.equals(group) && CANONICAL.contains(artifact)
                || "org.hibernate".equals(group) && LEGACY.contains(artifact) ? artifact : null;
    }

    static boolean knownArtifact(String artifact) {
        return CANONICAL.contains(artifact) || Set.of("hibernate-jpamodelgen", "hibernate-enhance-maven-plugin", "hibernate-entitymanager").contains(artifact);
    }

    static boolean recognizable(String group, String artifact) {
        return target(group, artifact) != null || Set.of(GROUP, "org.hibernate", "org.hibernate.orm.tooling").contains(group)
                && Set.of("hibernate-entitymanager", "hibernate-enhance-maven-plugin", "hibernate-jpamodelgen").contains(artifact);
    }

    static boolean simpleVersion(String value) {
        return value != null && !value.isBlank() && !value.contains("${") && !value.contains("$")
                && !value.contains("+") && !value.contains("[") && !value.contains("(") && !value.contains("latest.");
    }

    /// Downloads only the selected published BOM through the execution's Maven settings/repositories.
    static Set<String> managed(String version, List<MavenRepository> repositories, ExecutionContext ctx) {
        repositories = repositories.isEmpty() ? List.of(MavenRepository.MAVEN_CENTRAL) : repositories;
        final List<MavenRepository> selectedRepositories = repositories;
        var view = MavenExecutionContextView.view(ctx);
        String key = OrmCoordinateSupport.class.getName() + ".bom:" + version + ":" + repositories.hashCode()
                + ":" + Objects.hash(view.getSettings(), view.getMirrors(), view.getCredentials(), view.getLocalRepository(),
                        view.getAddCentralRepository(), view.getAddLocalRepository());
        Set<String> cached = ctx.getMessage(key);
        if (cached != null) return cached;
        // Downloading initializes other execution-context caches; avoid nesting their
        // updates inside this context's ConcurrentHashMap.computeIfAbsent callback.
        Set<String> managed;
        try {
            var downloader = new MavenPomDownloader(ctx);
            var pom = downloader.download(new GroupArtifactVersion(GROUP, PLATFORM, version), null, null, selectedRepositories)
                    .resolve(List.of(), downloader, ctx);
            Set<String> artifacts = new HashSet<>();
            for (var dependency : pom.getDependencyManagement()) {
                if (GROUP.equals(dependency.getGroupId()) && version.equals(dependency.getVersion())
                        && (dependency.getType() == null || "jar".equals(dependency.getType()))
                        && dependency.getClassifier() == null) artifacts.add(dependency.getArtifactId());
            }
            managed = Collections.unmodifiableSet(artifacts);
        }
        catch (MavenDownloadingException ex) {
            managed = Set.of();
        }
        ctx.putMessage(key, managed);
        return managed;
    }

    static void report(Recipe recipe, SkippedMigrations table, SourceFile source, int offset,
            String subject, String reason, String message, ExecutionContext ctx) {
        Set<String> rows = ctx.computeMessageIfAbsent(OrmCoordinateSupport.class.getName() + ".rows", k -> ConcurrentHashMap.newKeySet());
        String key = recipe.getName() + ":" + source.getSourcePath() + ":" + offset + ":" + subject + ":" + reason;
        if (!rows.add(key)) return;
        int line = 1, column = 1;
        String text = source.printAll();
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') { line++; column = 1; } else column++;
        }
        table.insertRow(ctx, new SkippedMigrations.Row(recipe.getName(), source.getSourcePath().toString(), line, column, subject, reason, message));
    }

    static String local(Xml.Tag tag) { return DescriptorVisitor.local(tag); }
    static String value(Xml.Tag tag, String name) {
        List<Xml.Tag> children = children(tag, name);
        return children.size() == 1 ? children.get(0).getValue().orElse("").trim() : "";
    }
    static List<Xml.Tag> children(Xml.Tag tag, String name) {
        String prefix = tag.getName().contains(":") ? tag.getName().substring(0, tag.getName().indexOf(':') + 1) : "";
        return tag.getChildren().stream().filter(t -> (prefix + name).equals(t.getName())).toList();
    }
    static String name(Xml.Tag tag, String local) {
        return tag.getName().contains(":") ? tag.getName().substring(0, tag.getName().indexOf(':') + 1) + local : local;
    }
    static String attribute(Xml.Tag tag, String name) {
        return tag.getAttributes().stream().filter(a -> name.equals(a.getKeyAsString()))
                .map(Xml.Attribute::getValueAsString).findFirst().orElse("");
    }
}
