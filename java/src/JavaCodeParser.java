// ============================================================================
// JavaCodeParser - Java Code Metadata Extractor for RAG Indexing
// ============================================================================
//
// OVERVIEW
// --------
// This parser extracts rich semantic metadata from Java source code for use in
// RAG-based code search, retrieval and static analysis pipelines. It uses the
// JavaParser library with symbol solving to parse Java code from stdin and
// outputs a JSON document describing the file, its types and their methods to
// stdout. The output schema is identical to the companion CSharpCodeParser for
// cross-language code search.
//
// USAGE
// -----
// Pipe Java source code to stdin; receive JSON on stdout:
//   cat MyClass.java | java -jar JavaCodeParser.jar > methods.json
//
//   --file <path>       record <path> in the "file" field; consumers use it for node ids
//   --include-source    also emit full_code and body_offset per method
//   --pretty            indent the JSON; off by default, since indentation is most of the payload
//
// Exit codes: 0 parsed cleanly, 2 the input could not be parsed (described on
// stderr; an empty document is still emitted).
//
// BUILD INSTRUCTIONS
// ------------------
// Run ./build.sh (or build.ps1 on Windows) from the java/ directory.
// The resulting fat JAR includes all dependencies for standalone execution.
//
// OUTPUT FORMAT
// -------------
// A single JSON object:
//   { schema_version, file, language, imports, top_level_comment, types: [
//       { name, qualified_name, namespace, kind, inherits_from,
//         inheritance_hierarchy, methods: [ ... ] } ] }
//
// Every type reference carries an explicit "resolved" flag, so a consumer can
// tell a fully qualified name from a fallback to the name as written.
//
// DEPENDENCIES
// ------------
// Requires com.github.javaparser (JavaParser with symbol solver) and com.google.gson.
//
// ============================================================================


import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.resolution.declarations.*;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaCodeParser
 * -------------
 * Produces the v2 nested envelope: one file, many types, many methods per type.
 */
public class JavaCodeParser {

    private static final int SCHEMA_VERSION = 2;

    /** Exit code used when the input could not be parsed at all. */
    private static final int EXIT_PARSE_FAILED = 2;

    // System.out follows the platform charset, which mangles non-ASCII identifiers on Windows.
    // JSON is UTF-8 by definition, so write it as such regardless of host.
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    private static String filePath;
    private static boolean includeSource;
    private static boolean pretty;

    /** Source text and its line offsets, used to slice full_code exactly as line_span selects it. */
    private static String sourceText;
    private static int[] lineStarts;

    static class TypeInfo {
        String name;
        String qualifiedName;
        String namespaceName;
        String kind;
        List<String> inheritsFrom = new ArrayList<>();
        List<String> inheritanceHierarchy = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
    }

    static class MethodInfo {
        String name;
        String qualifiedName;
        String returnType;
        boolean returnTypeResolved;
        List<ParameterInfo> parameters = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        List<AnnotationInfo> annotations = new ArrayList<>();
        JavadocInfo javadoc = new JavadocInfo();
        String fullCode;
        Integer bodyOffset;
        Boolean isOverride;
        List<String> implementedInterfaceMembers = new ArrayList<>();
        List<CallInfo> calls = new ArrayList<>();
        LineSpan lineSpan;
        List<ThrownExceptionInfo> thrownExceptions = new ArrayList<>();

        String dataProviderName;
        MethodInfo dataProviderMethod;
    }

    static class ParameterInfo {
        String name;
        String type;
        boolean resolved;
    }

    static class AnnotationInfo {
        String name;
        String qualifiedName;
        Map<String, String> values = new LinkedHashMap<>();
    }

    static class JavadocInfo {
        String summary;
        String returns;
        List<JavadocParam> params = new ArrayList<>();
        List<JavadocThrows> throwsList = new ArrayList<>();
    }

    static class JavadocParam {
        String name;
        String description;
    }

    static class JavadocThrows {
        String exceptionType;
        String description;
    }

    /** One call target, with every site that reaches it. */
    static class CallInfo {
        String target;
        String returnType;
        boolean resolved;
        int count;
        List<LineSpan> lineSpans = new ArrayList<>();
    }

    static class ThrownExceptionInfo {
        String type;
        boolean resolved;
        List<String> sources = new ArrayList<>();
        String description;
    }

    static class LineSpan {
        int startLine;
        int startColumn;
        int endLine;
        int endColumn;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--file") && i + 1 < args.length) {
                filePath = args[++i];
            } else if (args[i].equals("--include-source")) {
                includeSource = true;
            } else if (args[i].equals("--pretty")) {
                pretty = true;
            }
        }

        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        // Only add JavaParserTypeSolver if the src directory exists.
        // When parsing standalone code from stdin, no source tree is available.
        Path srcPath = Paths.get("src");
        if (Files.isDirectory(srcPath)) {
            typeSolver.add(new JavaParserTypeSolver(srcPath));
        }

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        config.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        StaticJavaParser.setConfiguration(config);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        sourceText = sb.toString();
        lineStarts = computeLineStarts(sourceText);

        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(sourceText);
        } catch (ParseProblemException e) {
            // A batch indexer should get an empty document plus a readable reason, not a stack
            // trace on stderr and nothing to consume.
            emit(buildDocument(Collections.emptyList(), Collections.emptyList(), null));
            e.getProblems().forEach(p -> ERR.println("JavaCodeParser: " + p.getMessage().split("\n")[0]));
            ERR.flush();
            System.exit(EXIT_PARSE_FAILED);
            return;
        }

        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(null);

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(Collectors.toList());

        List<TypeInfo> types = new ArrayList<>();
        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            processTypeDeclaration(typeDecl, packageName, null, types);
        }

        linkDataProviders(types);

        emit(buildDocument(imports, types, extractTopLevelComment(cu)));
    }

    private static void emit(Map<String, Object> document) {
        // Indentation is most of the payload on a real file, so it is opt-in. Nulls are always
        // written: a consumer should never have to distinguish "absent" from "unresolved".
        GsonBuilder builder = new GsonBuilder().serializeNulls();
        if (pretty) {
            builder.setPrettyPrinting();
        }
        OUT.println(builder.create().toJson(document));
        OUT.flush();
    }

    private static Map<String, Object> buildDocument(List<String> imports, List<TypeInfo> types, String topComment) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema_version", SCHEMA_VERSION);
        document.put("file", filePath);
        document.put("language", "java");
        document.put("imports", imports);
        document.put("top_level_comment", topComment);
        document.put("types", types.stream().map(JavaCodeParser::typeInfoToJsonMap).collect(Collectors.toList()));
        return document;
    }

    // ------------------------------------------------------------------ types

    /**
     * @param enclosingTypeName dotted name of the types this declaration is nested in, or null at
     *                          the top level. Nested types are named Outer.Inner, so a method's
     *                          qualified name identifies exactly one declaration.
     */
    private static void processTypeDeclaration(TypeDeclaration<?> typeDecl, String packageName, String enclosingTypeName, List<TypeInfo> types) {
        String name = nest(enclosingTypeName, typeDecl.getNameAsString());

        TypeInfo type = new TypeInfo();
        type.name = name;
        type.namespaceName = packageName != null ? packageName : "";
        type.qualifiedName = (packageName != null ? packageName + "." : "") + name;
        type.kind = kindOf(typeDecl);

        if (typeDecl.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration classDecl = typeDecl.asClassOrInterfaceDeclaration();
            type.inheritsFrom.addAll(classDecl.getExtendedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
            type.inheritsFrom.addAll(classDecl.getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
        } else if (typeDecl.isRecordDeclaration()) {
            type.inheritsFrom.addAll(typeDecl.asRecordDeclaration().getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
        } else if (typeDecl.isEnumDeclaration()) {
            type.inheritsFrom.addAll(typeDecl.asEnumDeclaration().getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
        }

        // Resolved once per type rather than once per method, which is where v1 spent most of
        // its output budget.
        try {
            ResolvedReferenceTypeDeclaration decl = typeDecl.resolve().asReferenceType();
            type.inheritanceHierarchy = decl.getAllAncestors().stream()
                    .map(ResolvedReferenceType::getQualifiedName)
                    .collect(Collectors.toList());
        } catch (Exception ignored) {}

        for (MethodDeclaration method : typeDecl.getMethods()) {
            type.methods.add(extractMethodInfo(method, type.qualifiedName));
        }

        types.add(type);

        for (BodyDeclaration<?> member : typeDecl.getMembers()) {
            if (member instanceof TypeDeclaration) {
                processTypeDeclaration((TypeDeclaration<?>) member, packageName, name, types);
            }
        }
    }

    private static String kindOf(TypeDeclaration<?> typeDecl) {
        if (typeDecl.isEnumDeclaration()) return "enum";
        if (typeDecl.isRecordDeclaration()) return "record";
        if (typeDecl.isAnnotationDeclaration()) return "annotation";
        if (typeDecl.isClassOrInterfaceDeclaration() && typeDecl.asClassOrInterfaceDeclaration().isInterface()) return "interface";
        return "class";
    }

    private static String nest(String enclosingTypeName, String simpleName) {
        return enclosingTypeName == null ? simpleName : enclosingTypeName + "." + simpleName;
    }

    /** File header comment: comments that precede the first type and do not document it. */
    private static String extractTopLevelComment(CompilationUnit cu) {
        int firstTypeLine = cu.getTypes().stream()
                .map(t -> t.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE))
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);

        Set<Comment> typeDocs = cu.getTypes().stream()
                .map(t -> t.getComment().orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        List<Comment> candidates = new ArrayList<>(cu.getAllContainedComments());
        cu.getComment().ifPresent(candidates::add);

        List<String> texts = candidates.stream()
                .filter(c -> !typeDocs.contains(c))
                .filter(c -> c.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE) < firstTypeLine)
                .sorted(Comparator.comparingInt(c -> c.getBegin().map(p -> p.line).orElse(0)))
                .map(c -> normalizeEol(c.getContent()).trim())
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());

        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    // ---------------------------------------------------------------- methods

    private static MethodInfo extractMethodInfo(MethodDeclaration method, String typeQualifiedName) {
        MethodInfo info = new MethodInfo();
        info.name = method.getNameAsString();
        info.qualifiedName = typeQualifiedName + "." + info.name;
        info.modifiers = method.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList());

        for (AnnotationExpr ann : method.getAnnotations()) {
            AnnotationInfo annInfo = new AnnotationInfo();
            annInfo.name = ann.getNameAsString();
            try {
                annInfo.qualifiedName = ann.resolve().getQualifiedName();
            } catch (Exception e) {
                annInfo.qualifiedName = ann.getNameAsString();
            }

            if (ann.isNormalAnnotationExpr()) {
                NormalAnnotationExpr nae = ann.asNormalAnnotationExpr();
                for (MemberValuePair pair : nae.getPairs()) {
                    annInfo.values.put(pair.getNameAsString(), pair.getValue().toString().replace("\"", ""));
                }
            }

            // Without testng on the type solver path the annotation only resolves to its simple
            // name, which is the normal case when parsing a single file from stdin.
            boolean isTestAnnotation = annInfo.qualifiedName.endsWith(".Test") || annInfo.name.equals("Test");
            if (isTestAnnotation && annInfo.values.containsKey("dataProvider")) {
                info.dataProviderName = annInfo.values.get("dataProvider");
            }

            info.annotations.add(annInfo);
        }

        for (Parameter p : method.getParameters()) {
            ParameterInfo pi = new ParameterInfo();
            pi.name = p.getNameAsString();
            try {
                pi.type = p.getType().resolve().describe();
                pi.resolved = true;
            } catch (Exception e) {
                // The fallback to the name as written is exactly where resolution failed.
                pi.type = p.getType().asString();
                pi.resolved = false;
            }
            info.parameters.add(pi);
        }

        info.returnType = method.getType().asString();
        info.returnTypeResolved = false;

        try {
            ResolvedMethodDeclaration rmd = method.resolve();
            info.returnType = rmd.getReturnType().describe();
            info.returnTypeResolved = true;
            info.isOverride = isOverrideMethod(rmd);
            info.implementedInterfaceMembers = getImplementedInterfaceMembers(rmd);
        } catch (Exception e) {
            // Ignore if symbol resolution fails
        }

        method.getComment().ifPresent(comment -> {
            if (comment.isJavadocComment()) {
                try {
                    Javadoc jd = comment.asJavadocComment().parse();
                    info.javadoc.summary = normalizeEol(jd.getDescription().toText().trim());

                    for (JavadocBlockTag tag : jd.getBlockTags()) {
                        switch (tag.getTagName()) {
                            case "param":
                                JavadocParam xp = new JavadocParam();
                                xp.name = tag.getName().orElse("");
                                xp.description = normalizeEol(tag.getContent().toText().trim());
                                info.javadoc.params.add(xp);
                                break;
                            case "return":
                                info.javadoc.returns = normalizeEol(tag.getContent().toText().trim());
                                break;
                            case "throws":
                            case "exception":
                                JavadocThrows xt = new JavadocThrows();
                                xt.exceptionType = tag.getName().orElse("");
                                xt.description = normalizeEol(tag.getContent().toText().trim());
                                info.javadoc.throwsList.add(xt);
                                break;
                            default:
                                break;
                        }
                    }
                } catch (Exception e) {
                    // Ignore Javadoc parsing errors
                }
            }
        });

        info.thrownExceptions = mergeThrownExceptions(method, info.javadoc);
        info.calls = extractCalls(method);
        info.lineSpan = spanOf(method.getBegin().orElse(null), method.getEnd().orElse(null));

        if (includeSource) {
            info.fullCode = sliceSource(method.getBegin().orElse(null), method.getEnd().orElse(null));
            Position methodStart = method.getBegin().orElse(null);
            Position bodyStart = method.getBody().flatMap(b -> b.getBegin()).orElse(null);
            if (methodStart != null && bodyStart != null && info.fullCode != null) {
                info.bodyOffset = offsetOf(bodyStart) - offsetOf(methodStart);
            }
        }

        return info;
    }

    /**
     * The throws clause and the Javadoc @throws tags describe the same thing, so they become one
     * list keyed by exception type, recording which sources mentioned it.
     */
    private static List<ThrownExceptionInfo> mergeThrownExceptions(MethodDeclaration method, JavadocInfo javadoc) {
        List<ThrownExceptionInfo> merged = new ArrayList<>();

        method.getThrownExceptions().forEach(te -> {
            ThrownExceptionInfo tei = new ThrownExceptionInfo();
            try {
                tei.type = te.resolve().describe();
                tei.resolved = true;
            } catch (Exception ex) {
                tei.type = te.asString();
                tei.resolved = false;
            }
            tei.sources.add("signature");
            merged.add(tei);
        });

        for (JavadocThrows jt : javadoc.throwsList) {
            ThrownExceptionInfo existing = merged.stream()
                    .filter(t -> simpleName(t.type).equals(simpleName(jt.exceptionType)))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.sources.add("javadoc");
                existing.description = jt.description;
            } else {
                ThrownExceptionInfo tei = new ThrownExceptionInfo();
                tei.type = jt.exceptionType;
                tei.resolved = false;
                tei.sources.add("javadoc");
                tei.description = jt.description;
                merged.add(tei);
            }
        }

        return merged;
    }

    private static String simpleName(String type) {
        if (type == null) return "";
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    /**
     * Invocations in the method body, grouped by call target. The qualified signature carries
     * parameter types, so overloads stay distinct nodes in a call graph.
     */
    private static List<CallInfo> extractCalls(MethodDeclaration method) {
        List<CallInfo> calls = new ArrayList<>();
        Map<String, CallInfo> index = new LinkedHashMap<>();

        if (!method.getBody().isPresent()) return calls;

        for (MethodCallExpr call : method.getBody().get().findAll(MethodCallExpr.class)) {
            String target;
            String returnType = null;
            boolean resolved = false;

            try {
                ResolvedMethodDeclaration cm = call.resolve();
                target = cm.getQualifiedSignature();
                returnType = cm.getReturnType().describe();
                resolved = true;
            } catch (Exception ex) {
                target = call.getNameAsString();
            }

            CallInfo ci = index.get(target);
            if (ci == null) {
                ci = new CallInfo();
                ci.target = target;
                ci.returnType = returnType;
                ci.resolved = resolved;
                index.put(target, ci);
                calls.add(ci);
            }
            ci.count++;
            LineSpan span = spanOf(call.getBegin().orElse(null), call.getEnd().orElse(null));
            if (span != null) {
                ci.lineSpans.add(span);
            }
        }

        return calls;
    }

    private static boolean isOverrideMethod(ResolvedMethodDeclaration rmd) {
        try {
            ResolvedReferenceTypeDeclaration declaringType = rmd.declaringType().asReferenceType();
            for (ResolvedReferenceType ancestor : declaringType.getAllAncestors()) {
                Optional<ResolvedReferenceTypeDeclaration> ancestorType = ancestor.getTypeDeclaration();
                if (ancestorType.isPresent()) {
                    for (ResolvedMethodDeclaration ancestorMethod : ancestorType.get().getDeclaredMethods()) {
                        if (methodsMatch(ancestorMethod, rmd)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static List<String> getImplementedInterfaceMembers(ResolvedMethodDeclaration rmd) {
        List<String> implemented = new ArrayList<>();
        try {
            ResolvedReferenceTypeDeclaration declaringType = rmd.declaringType().asReferenceType();
            for (ResolvedReferenceType ancestor : declaringType.getAllAncestors()) {
                Optional<ResolvedReferenceTypeDeclaration> ancestorType = ancestor.getTypeDeclaration();
                if (ancestorType.isPresent() && ancestorType.get().isInterface()) {
                    for (ResolvedMethodDeclaration ancestorMethod : ancestorType.get().getDeclaredMethods()) {
                        if (methodsMatch(ancestorMethod, rmd)) {
                            implemented.add(ancestorMethod.getQualifiedSignature());
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return implemented;
    }

    private static boolean methodsMatch(ResolvedMethodDeclaration m1, ResolvedMethodDeclaration m2) {
        if (!m1.getName().equals(m2.getName())) return false;
        if (m1.getNumberOfParams() != m2.getNumberOfParams()) return false;
        for (int i = 0; i < m1.getNumberOfParams(); i++) {
            ResolvedType t1 = m1.getParam(i).getType();
            ResolvedType t2 = m2.getParam(i).getType();
            if (!t1.isAssignableBy(t2) && !t2.isAssignableBy(t1) && !t1.describe().equals(t2.describe())) {
                return false;
            }
        }
        return true;
    }

    private static void linkDataProviders(List<TypeInfo> types) {
        List<MethodInfo> methods = types.stream().flatMap(t -> t.methods.stream()).collect(Collectors.toList());

        Map<String, MethodInfo> dataProviders = new HashMap<>();
        for (MethodInfo mi : methods) {
            for (AnnotationInfo ann : mi.annotations) {
                boolean isDataProvider = ann.qualifiedName.endsWith(".DataProvider") || ann.name.equals("DataProvider");
                if (isDataProvider && ann.values.containsKey("name")) {
                    dataProviders.put(ann.values.get("name"), mi);
                    break;
                }
            }
        }

        for (MethodInfo mi : methods) {
            if (mi.dataProviderName != null) {
                mi.dataProviderMethod = dataProviders.getOrDefault(mi.dataProviderName, null);
            }
        }
    }

    // ----------------------------------------------------------------- source

    private static int[] computeLineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offsetOf(Position position) {
        int lineIndex = Math.min(Math.max(position.line - 1, 0), lineStarts.length - 1);
        return lineStarts[lineIndex] + (position.column - 1);
    }

    /**
     * The exact source text that line_span selects. JavaParser's toString() would return a
     * pretty-printed reconstruction instead, which no span can reproduce.
     */
    private static String sliceSource(Position begin, Position end) {
        if (begin == null || end == null) return null;
        int from = offsetOf(begin);
        int to = Math.min(offsetOf(end) + 1, sourceText.length());
        return from >= 0 && from <= to ? sourceText.substring(from, to) : null;
    }

    private static LineSpan spanOf(Position begin, Position end) {
        if (begin == null || end == null) return null;
        LineSpan ls = new LineSpan();
        ls.startLine = begin.line;
        ls.startColumn = begin.column;
        ls.endLine = end.line;
        ls.endColumn = end.column;
        return ls;
    }

    /**
     * JavaParser's printer and Javadoc reader both emit the platform line separator, which would
     * make the same input produce different output on Windows and Linux.
     */
    private static String normalizeEol(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    // ------------------------------------------------------------------- json

    private static Map<String, Object> typeInfoToJsonMap(TypeInfo type) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", type.name);
        map.put("qualified_name", type.qualifiedName);
        map.put("namespace", type.namespaceName);
        map.put("kind", type.kind);
        map.put("inherits_from", type.inheritsFrom);
        map.put("inheritance_hierarchy", type.inheritanceHierarchy);
        map.put("methods", type.methods.stream().map(JavaCodeParser::methodInfoToJsonMap).collect(Collectors.toList()));
        return map;
    }

    private static Map<String, Object> methodInfoToJsonMap(MethodInfo mi) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("name", mi.name);
        map.put("qualified_name", mi.qualifiedName);
        map.put("modifiers", mi.modifiers);

        List<Map<String, Object>> annotationList = new ArrayList<>();
        for (AnnotationInfo ann : mi.annotations) {
            Map<String, Object> annMap = new LinkedHashMap<>();
            annMap.put("name", ann.name);
            annMap.put("qualified_name", ann.qualifiedName);
            annMap.put("values", ann.values);
            annotationList.add(annMap);
        }
        map.put("annotations", annotationList);

        List<Map<String, Object>> paramsList = new ArrayList<>();
        for (ParameterInfo p : mi.parameters) {
            Map<String, Object> pMap = new LinkedHashMap<>();
            pMap.put("name", p.name);
            pMap.put("type", p.type);
            pMap.put("resolved", p.resolved);
            paramsList.add(pMap);
        }
        map.put("parameters", paramsList);

        Map<String, Object> returnTypeMap = new LinkedHashMap<>();
        returnTypeMap.put("type", mi.returnType);
        returnTypeMap.put("resolved", mi.returnTypeResolved);
        map.put("return_type", returnTypeMap);

        Map<String, Object> docMap = new LinkedHashMap<>();
        docMap.put("summary", mi.javadoc.summary);
        List<Map<String, String>> docParams = new ArrayList<>();
        for (JavadocParam xp : mi.javadoc.params) {
            Map<String, String> xpMap = new LinkedHashMap<>();
            xpMap.put("name", xp.name);
            xpMap.put("description", xp.description);
            docParams.add(xpMap);
        }
        docMap.put("params", docParams);
        docMap.put("returns", mi.javadoc.returns);
        map.put("documentation", docMap);

        List<Map<String, Object>> callsList = new ArrayList<>();
        for (CallInfo c : mi.calls) {
            Map<String, Object> cMap = new LinkedHashMap<>();
            cMap.put("target", c.target);
            cMap.put("return_type", c.returnType);
            cMap.put("resolved", c.resolved);
            cMap.put("count", c.count);
            cMap.put("line_spans", c.lineSpans.stream().map(JavaCodeParser::lineSpanToJsonMap).collect(Collectors.toList()));
            callsList.add(cMap);
        }
        map.put("calls", callsList);

        map.put("line_span", mi.lineSpan != null ? lineSpanToJsonMap(mi.lineSpan) : null);

        List<Map<String, Object>> thrownList = new ArrayList<>();
        for (ThrownExceptionInfo te : mi.thrownExceptions) {
            Map<String, Object> teMap = new LinkedHashMap<>();
            teMap.put("type", te.type);
            teMap.put("resolved", te.resolved);
            teMap.put("sources", te.sources);
            teMap.put("description", te.description);
            thrownList.add(teMap);
        }
        map.put("thrown_exceptions", thrownList);

        map.put("is_override", mi.isOverride != null ? mi.isOverride : false);
        map.put("implemented_interface_members", mi.implementedInterfaceMembers);
        map.put("data_provider_name", mi.dataProviderName);
        map.put("data_provider_source", mi.dataProviderMethod != null ? mi.dataProviderMethod.name : null);

        if (includeSource) {
            map.put("full_code", mi.fullCode);
            map.put("body_offset", mi.bodyOffset);
        }

        return map;
    }

    private static Map<String, Integer> lineSpanToJsonMap(LineSpan ls) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("start_line", ls.startLine);
        map.put("start_column", ls.startColumn);
        map.put("end_line", ls.endLine);
        map.put("end_column", ls.endColumn);
        return map;
    }
}
