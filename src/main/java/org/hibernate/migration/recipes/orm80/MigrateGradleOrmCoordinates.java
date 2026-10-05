package org.hibernate.migration.recipes.orm80;

import org.hibernate.migration.recipes.table.SkippedMigrations;
import org.jspecify.annotations.NonNull;
import org.openrewrite.*;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.kotlin.tree.K;

import org.openrewrite.toml.tree.Toml;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

import static org.hibernate.migration.recipes.orm80.OrmCoordinateSupport.*;

/// Migrates direct Gradle declarations and local catalogs as a single cross-file plan.
///
/// @author Steve Ebersole
public class MigrateGradleOrmCoordinates extends ScanningRecipe<MigrateGradleOrmCoordinates.Plan> {
    @Option(displayName = "Target ORM version", description = "An exact published ORM 8.0 release.", example = "8.0.0.Beta3")
    private final String targetVersion;
    private final transient SkippedMigrations skipped = new SkippedMigrations(this);
    private static final Set<String> CONFIGURATIONS = Set.of("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly",
            "testImplementation", "testCompileOnly", "testRuntimeOnly", "annotationProcessor", "testAnnotationProcessor");
    public MigrateGradleOrmCoordinates(String targetVersion) { this.targetVersion = targetVersion; }
    public String getTargetVersion() { return targetVersion; }
    // Relocated coordinates can expose declarations to earlier aggregate recipes.
    @Override public boolean causesAnotherCycle() { return true; }
    @Override public @NonNull String getDisplayName() { return "Migrate Gradle ORM coordinates"; }
    @Override public @NonNull String getDescription() { return "Relocates allowlisted ORM declarations and aligns plugins/processors in Gradle scripts and local catalogs."; }
    @Override public @NonNull Validated<Object> validate() { return super.validate().and(OrmCoordinateSupport.validate(targetVersion)); }
    static final class Plan {
        final Map<Path, SourceFile> scripts = new LinkedHashMap<>();
        final Map<Path, GradleOrmCatalog> catalogs = new LinkedHashMap<>();
        final Map<Path, List<CoordinateSourceEdits.Edit>> edits = new HashMap<>();
        final Set<GradleOrmCatalog.Entry> blockedEntries = new HashSet<>();
        boolean ready;
    }
    @Override public Plan getInitialValue(ExecutionContext ctx) { return new Plan(); }
    @Override public TreeVisitor<?, ExecutionContext> getScanner(Plan plan) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (!(tree instanceof SourceFile source) || !validate().isValid()) return tree;
                String filename = source.getSourcePath().getFileName().toString();
                if (source instanceof Toml.Document toml) plan.catalogs.put(source.getSourcePath(), new GradleOrmCatalog(toml));
                else if ((source instanceof G.CompilationUnit || source instanceof K.CompilationUnit)
                        && Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").contains(filename))
                    plan.scripts.put(source.getSourcePath(), source);
                return tree;
            }
        };
    }
    @Override public TreeVisitor<?, ExecutionContext> getVisitor(Plan plan) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree preVisit(Tree tree, ExecutionContext ctx) {
                stopAfterPreVisit();
                if (!(tree instanceof SourceFile source)) return tree;
                if (!plan.ready) { prepare(plan, ctx); plan.ready = true; }
                return CoordinateSourceEdits.apply(source, plan.edits.getOrDefault(source.getSourcePath(), List.of()), ctx);
            }
        };
    }
    private record Declaration(J.MethodInvocation call, J.MethodInvocation block, String configuration, String group, String artifact,
                               String version, String role, boolean supported, String notation, List<Expression> arguments,
                               GradleOrmCatalog catalog, GradleOrmCatalog.Entry entry) {}
    private record Script(SourceFile source, List<Declaration> declarations, Map<UUID, Integer> positions, Map<String, J.Literal> safeVersions) {}

    private void prepare(Plan plan, ExecutionContext ctx) {
        if (!validate().isValid()) return;
        // A source is planned once per recipe execution; all offsets refer to that leaf's initial input.
        Set<String> processed = ctx.computeMessageIfAbsent(getName() + ".processed:" + targetVersion, k -> new HashSet<>());
        identifyCatalogs(plan);
        List<Script> scripts = new ArrayList<>();
        for (SourceFile source : plan.scripts.values()) {
            if (source.getSourcePath().getFileName().toString().startsWith("settings")) continue;
            if (!processed.add(source.getSourcePath() + ":" + source.getId())) continue;
            scripts.add(inspect(source, plan));
        }
        Map<GradleOrmCatalog.Entry, List<Declaration>> consumers = new HashMap<>();
        for (Script script : scripts) for (Declaration declaration : script.declarations)
            if (declaration.entry != null) consumers.computeIfAbsent(declaration.entry, k -> new ArrayList<>()).add(declaration);
        for (Script script : scripts) migrateScript(script, consumers, plan, ctx);
        for (GradleOrmCatalog catalog : plan.catalogs.values()) {
            if (catalog.names.isEmpty() || !processed.add(catalog.source.getSourcePath() + ":" + catalog.source.getId())) continue;
            migrateCatalog(catalog, consumers, plan, ctx);
        }
    }

    private static void identifyCatalogs(Plan plan) {
        for (GradleOrmCatalog catalog : plan.catalogs.values()) {
            if (catalog.source.getSourcePath().endsWith(Path.of("gradle/libs.versions.toml"))) catalog.names.add("libs");
        }
        for (SourceFile source : plan.scripts.values()) {
            if (!source.getSourcePath().getFileName().toString().startsWith("settings")) continue;
            new JavaIsoVisitor<Integer>() {
                @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                    if (call.getSimpleName().equals("from")) {
                        String filename = null;
                        for (Expression arg : call.getArguments()) if (arg instanceof J.MethodInvocation files
                                && files.getSimpleName().equals("files") && files.getArguments().size() == 1
                                && files.getArguments().get(0) instanceof J.Literal literal && literal.getValue() instanceof String s) filename = s;
                        String name = null;
                        for (Cursor c = getCursor().getParent(); c != null; c = c.getParent())
                            if (c.getValue() instanceof J.MethodInvocation create && create.getSimpleName().equals("create")
                                    && !create.getArguments().isEmpty() && create.getArguments().get(0) instanceof J.Literal literal
                                    && literal.getValue() instanceof String s) { name = s; break; }
                        if (filename != null && name != null) {
                            Path parent = source.getSourcePath().getParent();
                            Path path = (parent == null ? Path.of(filename) : parent.resolve(filename)).normalize();
                            GradleOrmCatalog catalog = plan.catalogs.get(path);
                            if (catalog != null) catalog.names.add(name);
                        }
                    }
                    return super.visitMethodInvocation(call, p);
                }
            }.visit(source, 0);
        }
    }
    private static Script inspect(SourceFile source, Plan plan) {
        List<Declaration> declarations = new ArrayList<>();
        Map<String, String> literals = new HashMap<>(); Set<String> writes = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (localDeclaration(getCursor()) && variable.getInitializer() instanceof J.Literal literal && literal.getValue() instanceof String s)
                    literals.put(variable.getSimpleName(), s);
                else writes.add(variable.getSimpleName());
                return super.visitVariable(variable, p);
            }
            @Override public J.Assignment visitAssignment(J.Assignment assignment, Integer p) {
                if (assignment.getVariable() instanceof J.Identifier id) writes.add(id.getSimpleName());
                return super.visitAssignment(assignment, p);
            }
        }.visit(source, 0);
        writes.forEach(literals::remove);
        new JavaIsoVisitor<Integer>() {
            @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation call, Integer p) {
                List<J.MethodInvocation> parents = calls(getCursor());
                String configuration = call.getSimpleName();
                boolean dependencyContext = parents.stream().anyMatch(m -> m.getSimpleName().equals("dependencies") || m.getSimpleName().equals("constraints"));
                boolean direct = !parents.isEmpty() && Set.of("dependencies", "constraints").contains(parents.get(0).getSimpleName())
                        && parents.stream().allMatch(m -> Set.of("dependencies", "constraints", "buildscript").contains(m.getSimpleName())
                                && (m.getSelect() == null || m.getSelect() instanceof J.Identifier id && Set.of("project", "this").contains(id.getSimpleName())));
                J.MethodInvocation block = parents.stream().filter(m -> Set.of("dependencies", "constraints").contains(m.getSimpleName())).findFirst().orElse(null);
                if (dependencyContext && !Set.of("platform", "enforcedPlatform", "dependencies", "constraints").contains(configuration)) {
                    List<Expression> args = call.getArguments().stream().filter(e -> !(e instanceof J.Lambda) && !(e instanceof J.Empty)).toList();
                    String group = "", artifact = "", version = "", notation = "";
                    List<Expression> coordinateArgs = args;
                    boolean platform = false;
                    if (args.size() == 1 && args.get(0) instanceof J.MethodInvocation wrapper
                            && Set.of("platform", "enforcedPlatform").contains(wrapper.getSimpleName())) { platform = true; coordinateArgs = wrapper.getArguments(); }
                    if (coordinateArgs.size() == 1 && coordinateArgs.get(0) instanceof G.MapLiteral map) coordinateArgs = new ArrayList<>(map.getElements());
                    if (coordinateArgs.size() == 1) {
                        Expression arg = coordinateArgs.get(0);
                        String value = string(arg, literals);
                        if (!value.isEmpty()) {
                            String[] parts = value.split(":", -1);
                            if (parts.length >= 2) { group = parts[0]; artifact = parts[1]; }
                            if (parts.length >= 3) version = parts[2];
                            notation = parts.length <= 3 && !value.contains("@") ? "string" : parts.length <= 4 ? "extended" : "unsupported";
                            if (version.contains("@")) version = version.substring(0, version.indexOf('@'));
                        }
                        else if (arg instanceof G.GString || arg instanceof K.StringTemplate) {
                            Matcher matcher = Pattern.compile("^[\"']([^:]+):([^:]+):(.+)[\"']$").matcher(CoordinateSourceEdits.printed(source, arg));
                            if (matcher.matches()) {
                                group = identity(matcher.group(1), literals); artifact = identity(matcher.group(2), literals); version = matcher.group(3); notation = "string";
                                Matcher reference = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?").matcher(version);
                                if (reference.matches() && !literals.containsKey(reference.group(1))) version = "unresolved version(...)";
                            }
                        }
                        else {
                            String accessor = arg.printTrimmed();
                            for (GradleOrmCatalog catalog : plan.catalogs.values()) for (String name : catalog.names) {
                                Path buildParent = source.getSourcePath().getParent();
                                Path catalogParent = catalog.source.getSourcePath().getParent();
                                Path root = catalogParent == null ? Path.of("") : catalogParent.getParent();
                                if (root != null && buildParent != null && !buildParent.startsWith(root)) continue;
                                if (accessor.startsWith(name + ".")) {
                                    String alias = accessor.substring(name.length() + 1);
                                    List<GradleOrmCatalog.Entry> entries = new ArrayList<>();
                                    if (alias.startsWith("bundles.")) {
                                        for (String member : catalog.bundles.getOrDefault(alias.substring(8), List.of())) {
                                            var entry = catalog.library(GradleOrmCatalog.accessor(member)); if (entry != null) entries.add(entry);
                                        }
                                    }
                                    else { var entry = catalog.library(alias); if (entry != null) entries.add(entry); }
                                    for (var entry : entries) if (recognizable(entry.group(), entry.artifact())) declarations.add(new Declaration(call, block,
                                            configuration, entry.group(), entry.artifact(), entry.version(), role(configuration, entry.artifact(), platform, parents),
                                            direct && CONFIGURATIONS.contains(configuration), "catalog", args, catalog, entry));
                                }
                            }
                        }
                    }
                    else {
                        Map<String, Expression> fields = fields(coordinateArgs);
                        group = string(fields.get("group"), literals); artifact = string(fields.get("name"), literals);
                        if (group.isEmpty() && fields.get("group") != null) group = "${unresolvedGroup}";
                        if (artifact.isEmpty() && fields.get("name") != null) artifact = "${unresolvedArtifact}";
                        Expression v = fields.get("version"); version = string(v, literals);
                        if (version.isEmpty() && v != null) version = v instanceof J.Identifier ? "unresolved version(...)" : CoordinateSourceEdits.printed(source, v);
                        notation = "named";
                    }
                    if (group.isEmpty() && artifact.isEmpty() && fields(coordinateArgs).isEmpty()) {
                        for (Expression argument : coordinateArgs) {
                            String[] parts = string(argument, literals).split(":", -1);
                            if (parts.length >= 2 && recognizable(parts[0], parts[1]))
                                declarations.add(new Declaration(call, block, configuration, parts[0], parts[1], parts.length >= 3 ? parts[2] : "",
                                        "library", false, "unsupported", List.of(argument), null, null));
                        }
                    }
                    if (recognizable(group, artifact) || knownArtifact(artifact) && (group.contains("$") || group.isEmpty())
                            || Set.of(GROUP, "org.hibernate").contains(group) && artifact.contains("$"))
                        declarations.add(new Declaration(call, block, configuration, group, artifact, version, notation.equals("extended") || fields(coordinateArgs).containsKey("classifier") || fields(coordinateArgs).containsKey("ext")
                                        ? "artifact" : role(configuration, artifact, platform, parents),
                                direct && (CONFIGURATIONS.contains(configuration) || configuration.equals("classpath") && target(group, artifact) != null && TOOLING.contains(target(group, artifact)) && parents.stream().anyMatch(m -> m.getSimpleName().equals("buildscript"))),
                                notation, coordinateArgs, null, null));
                }
                if (configuration.equals("id") && !call.getArguments().isEmpty() && GROUP.equals(string(call.getArguments().get(0), literals))
                        && parents.stream().anyMatch(m -> m.getSimpleName().equals("plugins"))
                        && parents.stream().noneMatch(m -> m.getSimpleName().equals("version")))
                    declarations.add(new Declaration(call, block, "plugin", "plugin", GROUP, "", "plugin", true, "pluginMissing", call.getArguments(), null, null));
                if (configuration.equals("version") && !call.getArguments().isEmpty()) {
                    String plugin = call.getSelect() instanceof J.MethodInvocation id && id.getSimpleName().equals("id")
                            && !id.getArguments().isEmpty() ? string(id.getArguments().get(0), literals) : "";
                    if (plugin.equals(GROUP)) declarations.add(new Declaration(call, block, "plugin", "plugin", GROUP,
                            (string(call.getArguments().get(0), literals).isEmpty() ? "unresolved version(...)" : string(call.getArguments().get(0), literals)), "plugin", parents.stream().allMatch(m -> Set.of("apply", "plugins").contains(m.getSimpleName())),
                            "plugin", call.getArguments(), null, null));
                }
                return super.visitMethodInvocation(call, p);
            }
        }.visit(source, 0);
        Set<UUID> ids = new HashSet<>();
        declarations.forEach(d -> { ids.add(d.call.getId()); if (d.block != null) ids.add(d.block.getId()); });
        Map<String, J.Literal> definitions = new HashMap<>(); Map<String, Integer> reads = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, Integer p) {
                if (variable.getInitializer() instanceof J.Literal literal && literal.getValue() instanceof String
                        && literals.containsKey(variable.getSimpleName())) definitions.put(variable.getSimpleName(), literal);
                return super.visitVariable(variable, p);
            }
            @Override public J.Identifier visitIdentifier(J.Identifier identifier, Integer p) {
                if (!(getCursor().getParentTreeCursor().getValue() instanceof J.VariableDeclarations.NamedVariable variable
                        && variable.getName().getId().equals(identifier.getId()))) reads.merge(identifier.getSimpleName(), 1, Integer::sum);
                return super.visitIdentifier(identifier, p);
            }
        }.visit(source, 0);
        definitions.entrySet().removeIf(e -> {
            List<Declaration> uses = declarations.stream().filter(d -> versionReference(source, d).equals(e.getKey())).toList();
            return uses.isEmpty() || reads.getOrDefault(e.getKey(), 0) != uses.size()
                    || uses.stream().anyMatch(d -> !d.supported || !validVersion(d.version) || (!d.role.equals("plugin") && target(d.group, d.artifact) == null)
                            || declarations.stream().anyMatch(other -> other != d && Objects.equals(other.block, d.block)
                                    && other.configuration.equals(d.configuration) && other.role.equals(d.role)
                                    && Objects.equals(target(other.group, other.artifact), target(d.group, d.artifact))));
        });
        definitions.values().forEach(literal -> ids.add(literal.getId()));
        return new Script(source, declarations, CoordinateSourceEdits.offsets(source, ids), definitions);
    }
    private static boolean localDeclaration(Cursor cursor) {
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            Object tree = c.getValue();
            if (tree instanceof J.Lambda || tree instanceof J.ClassDeclaration || tree instanceof J.MethodDeclaration || tree instanceof J.If
                    || tree instanceof J.ForLoop || tree instanceof J.ForEachLoop || tree instanceof J.WhileLoop || tree instanceof J.DoWhileLoop
                    || tree instanceof J.Try) return false;
        }
        return true;
    }
    private static List<J.MethodInvocation> calls(Cursor cursor) {
        List<J.MethodInvocation> result = new ArrayList<>();
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) if (c.getValue() instanceof J.MethodInvocation method) result.add(method);
        return result;
    }
    private static String role(String configuration, String artifact, boolean platform, List<J.MethodInvocation> parents) {
        return platform || PLATFORM.equals(artifact) ? "platform" : configuration.contains("annotationProcessor") || configuration.contains("AnnotationProcessor")
                || configuration.equals("classpath") || TOOLING.contains(artifact) || artifact.equals("hibernate-jpamodelgen") ? "tooling"
                : parents.stream().anyMatch(m -> m.getSimpleName().equals("constraints")) ? "constraint" : "library";
    }
    private static Map<String, Expression> fields(List<Expression> args) {
        Map<String, Expression> fields = new LinkedHashMap<>();
        for (Expression arg : args) {
            if (arg instanceof G.MapEntry entry && entry.getKey() instanceof J.Literal key && key.getValue() instanceof String s) fields.put(s, entry.getValue());
            else if (arg instanceof J.Assignment assignment && assignment.getVariable() instanceof J.Identifier id) fields.put(id.getSimpleName(), assignment.getAssignment());
        }
        return fields;
    }
    private static String identity(String value, Map<String, String> literals) {
        Matcher reference = Pattern.compile("\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?").matcher(value);
        return reference.matches() ? literals.getOrDefault(reference.group(1), value) : value;
    }
    private static String string(Expression expression, Map<String, String> literals) {
        if (expression instanceof J.Literal literal && literal.getValue() instanceof String s) return s;
        if (expression instanceof J.Identifier id) return literals.getOrDefault(id.getSimpleName(), "");
        return "";
    }

    private void migrateScript(Script script, Map<GradleOrmCatalog.Entry, List<Declaration>> consumers, Plan plan, ExecutionContext ctx) {
        List<CoordinateSourceEdits.Edit> edits = plan.edits.computeIfAbsent(script.source.getSourcePath(), k -> new ArrayList<>());
        Set<Declaration> redundant = new HashSet<>(), entityConflicts = new HashSet<>();
        for (Declaration entity : script.declarations) if (entityManager(entity.artifact)) {
            List<Declaration> cores = script.declarations.stream().filter(c -> c.artifact.equals("hibernate-core")
                    && target(c.group, c.artifact) != null && c.configuration.equals(entity.configuration)
                    && c.role.equals("constraint") == entity.role.equals("constraint")).toList();
            if (cores.isEmpty()) continue;
            Declaration core = cores.get(0);
            if (cores.size() == 1 && entity.supported && core.supported && validVersion(entity.version) && validVersion(core.version)
                    && !entity.notation.equals("unsupported") && !core.notation.equals("unsupported")
                    && dependencyBehavior(script.source, entity).equals(dependencyBehavior(script.source, core))) redundant.add(entity);
            else { entityConflicts.add(entity); entityConflicts.addAll(cores); }
        }
        script.safeVersions.keySet().removeIf(reference -> entityConflicts.stream().anyMatch(d -> versionReference(script.source, d).equals(reference)));
        Map<String, List<Declaration>> configurations = new LinkedHashMap<>();
        for (Declaration d : script.declarations) configurations.computeIfAbsent(d.block == null ? "plugin" : d.block.getId() + ":" + d.configuration, k -> new ArrayList<>()).add(d);
        for (List<Declaration> declarations : configurations.values()) {
            List<Declaration> platforms = declarations.stream().filter(d -> d.role.equals("platform")).toList();
            boolean conflict = platforms.size() > 1 || platforms.stream().anyMatch(d -> !d.supported || !validVersion(d.version));
            Map<String, Long> counts = new HashMap<>();
            for (Declaration d : declarations) {
                String artifact = d.role.equals("plugin") ? GROUP : target(d.group, d.artifact);
                if (artifact != null && !d.role.equals("platform") && !redundant.contains(d)) counts.merge(d.role + ":" + artifact, 1L, Long::sum);
            }
            Set<UUID> handled = new HashSet<>();
            for (Declaration d : declarations) {
                String artifact = d.role.equals("plugin") ? GROUP : target(d.group, d.artifact);
                String reason = null;
                if (d.group.isEmpty() || d.group.contains("$") || d.artifact.contains("$")) reason = IDENTITY;
                else if (artifact == null) reason = ARTIFACT;
                else if (entityConflicts.contains(d) || d.role.equals("platform") && conflict || counts.getOrDefault(d.role + ":" + artifact, 0L) > 1) reason = CONFLICT;
                else if (!validVersion(d.version) || d.notation.equals("pluginMissing")) reason = VERSION;
                else if (!d.supported || d.notation.equals("unsupported")) reason = SYNTAX;
                int offset = script.positions.get(d.call.getId());
                if (reason != null) {
                    if (d.entry != null) plan.blockedEntries.add(d.entry);
                    report(this, skipped, script.source, offset, d.group + ":" + d.artifact, reason,
                            "Resolve this Gradle declaration manually before migration.", ctx); continue;
                }
                if (redundant.contains(d) && d.entry == null) {
                    String old = CoordinateSourceEdits.printed(script.source, d.call);
                    int end = offset + old.length(), separator = end;
                    String input = script.source.printAll();
                    while (separator < input.length() && Character.isWhitespace(input.charAt(separator))) separator++;
                    if (separator < input.length() && input.charAt(separator) == ';') end = separator + 1;
                    edits.add(new CoordinateSourceEdits.Edit(offset, end, retainedComments(script.source, d.call)));
                    continue;
                }
                if (d.entry != null) continue;
                if (!handled.add(d.call.getId())) continue;
                String old = CoordinateSourceEdits.printed(script.source, d.call);
                String updated = replaceDeclaration(script.source, d, artifact, false, old, script.safeVersions.keySet());
                String reference = versionReference(script.source, d);
                if (script.safeVersions.containsKey(reference)) {
                    J.Literal literal = script.safeVersions.get(reference);
                    int position = script.positions.get(literal.getId());
                    String value = CoordinateSourceEdits.printed(script.source, literal);
                    edits.add(new CoordinateSourceEdits.Edit(position, position + value.length(), quote(value, targetVersion)));
                }
                if (!updated.equals(old)) edits.add(new CoordinateSourceEdits.Edit(offset, offset + old.length(), updated));
            }
        }
    }
    private static String dependencyBehavior(SourceFile source, Declaration declaration) {
        Map<String, String> behavior = new TreeMap<>();
        behavior.put("role", declaration.role);
        if (declaration.call.getSelect() != null) behavior.put("receiver", normalizedBehavior(source, declaration.call.getSelect()));
        for (var field : fields(declaration.arguments).entrySet()) if (!Set.of("group", "name", "version").contains(field.getKey()))
            behavior.put(field.getKey(), normalizedBehavior(source, field.getValue()));
        if (declaration.notation.equals("extended")) {
            String printed = CoordinateSourceEdits.printed(source, declaration.arguments.get(0));
            String raw = printed.substring(1, printed.length() - 1);
            String[] parts = raw.split(":", -1);
            if (parts.length == 4) behavior.put("classifier", parts[3].contains("@") ? parts[3].substring(0, parts[3].indexOf('@')) : parts[3]);
            if (raw.contains("@")) behavior.put("ext", raw.substring(raw.indexOf('@') + 1));
        }
        for (Expression argument : declaration.call.getArguments()) if (argument instanceof J.Lambda)
            behavior.put("configuration", normalizedBehavior(source, argument));
        if (declaration.entry != null) for (var field : declaration.entry.fields().entrySet())
            if (!Set.of("module", "group", "name", "version", "version.ref").contains(field.getKey()))
                behavior.put(field.getKey(), CoordinateSourceEdits.printed(declaration.catalog.source, field.getValue()));
        return behavior.toString();
    }
    private static String normalizedBehavior(SourceFile source, J tree) {
        if (tree instanceof J.Literal literal) return String.valueOf(literal.getValue());
        J normalized = new JavaIsoVisitor<Integer>() {
            @Override public Space visitSpace(Space space, Space.Location location, Integer p) {
                return space.withWhitespace("").withComments(List.of());
            }
        }.visitNonNull(tree, 0);
        return CoordinateSourceEdits.printed(source, normalized);
    }
    private static boolean validVersion(String version) {
        return version.isEmpty() || simpleVersion(version) || version.matches("\\$[A-Za-z_][A-Za-z0-9_]*")
                || version.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");
    }
    private String replaceDeclaration(SourceFile source, Declaration d, String artifact, boolean omit, String old, Set<String> safeVersions) {
        boolean preserveReference = safeVersions.contains(versionReference(source, d));
        if (d.notation.equals("plugin")) {
            if (preserveReference) return old;
            Expression version = d.arguments.get(0);
            return replacePart(old, CoordinateSourceEdits.printed(source, version), quote(CoordinateSourceEdits.printed(source, version), targetVersion));
        }
        if (d.notation.equals("string") || d.notation.equals("extended")) {
            Expression argument = d.arguments.get(0);
            String coordinate = GROUP + ":" + artifact + (omit ? "" : ":" + (preserveReference ? d.version : targetVersion));
            if (d.notation.equals("extended")) {
                String printed = CoordinateSourceEdits.printed(source, argument);
                String raw = printed.substring(1, printed.length() - 1);
                String[] parts = raw.split(":", -1);
                if (parts.length == 4) coordinate += ":" + parts[3];
                else if (raw.contains("@")) coordinate += raw.substring(raw.indexOf('@'));
            }
            return replacePart(old, CoordinateSourceEdits.printed(source, argument), quote(CoordinateSourceEdits.printed(source, argument), coordinate));
        }
        Map<String, Expression> fields = fields(d.arguments);
        String updated = old;
        if (fields.containsKey("group")) updated = replacePart(updated, CoordinateSourceEdits.printed(source, fields.get("group")), quote(CoordinateSourceEdits.printed(source, fields.get("group")), GROUP));
        if (fields.containsKey("name")) updated = replacePart(updated, CoordinateSourceEdits.printed(source, fields.get("name")), quote(CoordinateSourceEdits.printed(source, fields.get("name")), artifact));
        if (fields.containsKey("version")) {
            Expression version = fields.get("version");
            if (!omit && !preserveReference) updated = replacePart(updated, CoordinateSourceEdits.printed(source, version), quote(CoordinateSourceEdits.printed(source, version), targetVersion));
            else if (omit) {
                Expression argument = d.arguments.stream().filter(a -> a instanceof G.MapEntry e && e.getValue() == version
                        || a instanceof J.Assignment assignment && assignment.getAssignment() == version).findFirst().orElseThrow();
                String printed = CoordinateSourceEdits.printed(source, argument); int index = updated.indexOf(printed);
                int start = index, end = index + printed.length();
                int comma = start - 1; while (comma >= 0 && Character.isWhitespace(updated.charAt(comma))) comma--;
                if (comma >= 0 && updated.charAt(comma) == ',') start = comma;
                else { while (end < updated.length() && Character.isWhitespace(updated.charAt(end))) end++; if (end < updated.length() && updated.charAt(end) == ',') end++; }
                updated = updated.substring(0, start) + retainedComments(source, argument) + updated.substring(end);
            }
        }
        else if (!omit) {
            int end = updated.indexOf(')');
            if (end < 0) updated += ", version: '" + targetVersion + "'";
            else updated = updated.substring(0, end) + (updated.substring(0, end).contains("name =") ? ", version = " : ", version: ")
                    + "\"" + targetVersion + "\"" + updated.substring(end);
        }
        return updated;
    }
    private static String retainedComments(SourceFile source, J argument) {
        StringBuilder retained = new StringBuilder();
        Set<Comment> comments = Collections.newSetFromMap(new IdentityHashMap<>());
        new JavaIsoVisitor<Integer>() {
            @Override public Space visitSpace(Space space, Space.Location location, Integer p) {
                if (space != argument.getPrefix()) for (Comment comment : space.getComments()) if (comments.add(comment))
                    retained.append(' ').append(comment.printComment(new Cursor(null, source))).append(comment.getSuffix());
                return space;
            }
        }.visit(argument, 0);
        return retained.toString();
    }
    private static String versionReference(SourceFile source, Declaration d) {
        if (d.notation.equals("string") && !d.arguments.isEmpty()) {
            Matcher matcher = Pattern.compile(".*:\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?['\"]$").matcher(CoordinateSourceEdits.printed(source, d.arguments.get(0)));
            return matcher.matches() ? matcher.group(1) : "";
        }
        Expression expression = d.notation.equals("plugin") && !d.arguments.isEmpty() ? d.arguments.get(0) : fields(d.arguments).get("version");
        return expression instanceof J.Identifier id ? id.getSimpleName() : "";
    }
    private static String quote(String old, String value) { return GradleOrmCatalog.quote(old, value); }
    private static String replacePart(String source, String old, String value) {
        int offset = source.indexOf(old); if (offset < 0) throw new IllegalStateException("Missing structural argument: " + old);
        return source.substring(0, offset) + value + source.substring(offset + old.length());
    }

    private void migrateCatalog(GradleOrmCatalog catalog,
            Map<GradleOrmCatalog.Entry, List<Declaration>> consumers, Plan plan, ExecutionContext ctx) {
        List<CoordinateSourceEdits.Edit> edits = plan.edits.computeIfAbsent(catalog.source.getSourcePath(), k -> new ArrayList<>());
        Map<String, String> safeReferences = new HashMap<>();
        for (var e : catalog.entries) if (!e.reference().isEmpty()) {
            List<GradleOrmCatalog.Entry> uses = catalog.entries.stream().filter(other -> other.reference().equals(e.reference())).toList();
            if (uses.stream().allMatch(other -> (target(other.group(), other.artifact()) != null || other.role().equals("plugin") && GROUP.equals(other.artifact()))
                    && validVersion(other.version()) && !plan.blockedEntries.contains(other))) safeReferences.put(e.reference(), targetVersion);
        }
        Set<UUID> versionIds = new HashSet<>(); Map<UUID, Toml.KeyValue> versionFields = new HashMap<>();
        for (var value : catalog.source.getValues()) if (value instanceof Toml.Table table && table.getName() != null && table.getName().getName().equals("versions"))
            for (Toml tree : table.getValues()) if (tree instanceof Toml.KeyValue kv && safeReferences.containsKey(GradleOrmCatalog.key(kv))
                    && catalog.entries.stream().anyMatch(e -> e.reference().equals(GradleOrmCatalog.key(kv)))) {
                versionIds.add(kv.getId()); versionFields.put(kv.getId(), kv);
            }
        var versionPositions = CoordinateSourceEdits.offsets(catalog.source, versionIds);
        for (Toml.KeyValue kv : versionFields.values()) {
            String old = CoordinateSourceEdits.printed(catalog.source, kv); String updated = replacePart(old, CoordinateSourceEdits.printed(catalog.source, kv.getValue()), quote(CoordinateSourceEdits.printed(catalog.source, kv.getValue()), targetVersion));
            edits.add(new CoordinateSourceEdits.Edit(versionPositions.get(kv.getId()), versionPositions.get(kv.getId()) + old.length(), updated));
        }
        for (var entry : catalog.entries) {
            boolean plugin = entry.role().equals("plugin") && GROUP.equals(entry.artifact());
            if (!plugin && !recognizable(entry.group(), entry.artifact())) continue;
            String artifact = plugin ? GROUP : target(entry.group(), entry.artifact());
            String reason = artifact == null ? ARTIFACT : plan.blockedEntries.contains(entry) ? CONFLICT : !validVersion(entry.version()) ? VERSION : null;
            if (!entry.fields().isEmpty() && entry.fields().get("version") != null
                    && entry.fields().get("version").getValue() instanceof Toml.Table && entry.reference().isEmpty()) reason = VERSION;
            int offset = catalog.positions.get(entry.tree().getId());
            if (reason != null) { report(this, skipped, catalog.source, offset, entry.group() + ":" + entry.artifact(), reason,
                    "Resolve this catalog declaration manually before migration.", ctx); continue; }
            String old = CoordinateSourceEdits.printed(catalog.source, entry.tree()); String updated = old;
            if (entry.tree().getValue() instanceof Toml.Literal) {
                updated = replacePart(old, CoordinateSourceEdits.printed(catalog.source, entry.tree().getValue()), quote(CoordinateSourceEdits.printed(catalog.source, entry.tree().getValue()), GROUP + ":" + artifact + ":" + targetVersion));
            }
            else {
                for (String field : List.of("module", "group", "name")) if (entry.fields().containsKey(field) && !plugin) {
                    var value = entry.fields().get(field).getValue();
                    String replacement = field.equals("module") ? GROUP + ":" + artifact : field.equals("group") ? GROUP : artifact;
                    updated = replacePart(updated, CoordinateSourceEdits.printed(catalog.source, value), quote(CoordinateSourceEdits.printed(catalog.source, value), replacement));
                }
                if (entry.reference().isEmpty() || !safeReferences.containsKey(entry.reference())) {
                    String field = entry.fields().containsKey("version.ref") ? "version.ref" : "version";
                    if (entry.fields().containsKey(field)) {
                        var member = entry.fields().get(field);
                        String replacement = CoordinateSourceEdits.printed(catalog.source, member);
                        if (field.equals("version.ref")) replacement = replacement.replaceFirst("version\\.ref", "version");
                        replacement = replacePart(replacement, CoordinateSourceEdits.printed(catalog.source, member.getValue()), quote(CoordinateSourceEdits.printed(catalog.source, member.getValue()), targetVersion));
                        updated = replacePart(updated, CoordinateSourceEdits.printed(catalog.source, member), replacement);
                    }
                    else { int close = updated.lastIndexOf('}'); updated = updated.substring(0, close).stripTrailing() + ", version = \"" + targetVersion + "\" " + updated.substring(close); }
                }
            }
            if (!updated.equals(old)) edits.add(new CoordinateSourceEdits.Edit(offset, offset + old.length(), updated));
        }
    }
}
