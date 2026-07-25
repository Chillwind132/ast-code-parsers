// ============================================================================
// JavaCodeParser - Java Method Metadata Extractor for RAG Indexing
// ============================================================================
//
// OVERVIEW
// --------
// This parser extracts rich semantic metadata from Java source code for use in
// RAG-based code search, retrieval and static analysis pipelines. It uses the
// JavaParser library with symbol solving to parse Java code from stdin and
// outputs a JSON array of method metadata objects to stdout. The output schema is
// designed to be consistent with the companion CSharpCodeParser for cross-language
// code search.
//
// USAGE
// -----
// Pipe Java source code to stdin; receive JSON method metadata on stdout:
//   cat MyClass.java | java -jar JavaCodeParser.jar > methods.json
//
// BUILD INSTRUCTIONS
// ------------------
// Run ./build.sh (or build.ps1 on Windows) from the java/ directory.
// The resulting fat JAR includes all dependencies for standalone execution.
//
// EXTRACTED METADATA
// ------------------
// For each method in the source file, the parser extracts:
//   - Identity:       Method name, qualified name, package, containing class/interface/enum/record
//   - Signature:      Return type, parameters (with fully qualified types when resolvable)
//   - Modifiers:      Access level, static, final, synchronized, etc.
//   - Annotations:    All applied annotations (names, qualified names, key-value pairs)
//   - Documentation:  Javadoc comments (summary, @param, @return, @throws tags, raw text)
//   - Source Code:    Full method text and body-only snippet
//   - Inheritance:    Base types, interface implementations, override detection, full hierarchy
//   - Call Graph:     Method invocations within the body (callee, types, positions)
//   - Exceptions:     Declared thrown exceptions (with fully qualified types)
//   - Location:       Line/column spans for precise source mapping
//   - Imports:        All import declarations from the compilation unit
//   - TestNG:         DataProvider linkage for @Test methods referencing @DataProvider
//
// OUTPUT FORMAT
// -------------
// JSON array where each element represents one method with fields including:
// symbol_type, name, qualified_name, namespace, modifiers, annotations,
// parameters, return_type, documentation, body_code, full_code, calls,
// line_span, inherits_from, implemented_interface_members, thrown_exceptions,
// is_override, data_provider_name, data_provider_source, imported_types,
// top_level_comment, inheritance_hierarchy, language
//
// DEPENDENCIES
// ------------
// Requires com.github.javaparser (JavaParser with symbol solver) and com.google.gson.
//
// ============================================================================


import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.JavadocComment;
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
 * Updated to produce standardized JSON output for RAG.
 */
public class JavaCodeParser {

    static class MethodInfo {
        String name;
        String returnType;
        String fullyQualifiedReturnType;
        List<ParameterInfo> parameters = new ArrayList<>();
        List<String> modifiers = new ArrayList<>();
        List<AnnotationInfo> annotations = new ArrayList<>();
        String namespaceName;
        String className;
        List<String> classBaseTypes = new ArrayList<>();
        XmlDocInfo xmlDoc = new XmlDocInfo();
        String code;
        String fullCode;
        Boolean isOverride;
        List<String> implementedInterfaceMembers = new ArrayList<>();
        List<CallInfo> calls = new ArrayList<>();
        LineSpan lineSpan;
        List<String> importedTypes = new ArrayList<>();
        String topComment;
        List<String> inheritanceHierarchy = new ArrayList<>();
        List<ThrownExceptionInfo> thrownExceptions = new ArrayList<>();

        String dataProviderName;
        MethodInfo dataProviderMethod;
    }

    static class ParameterInfo {
        String name;
        String type;
        String fullyQualifiedType;
    }

    static class AnnotationInfo {
        String name;
        String fullyQualifiedName;
        Map<String, String> values = new HashMap<>();
    }

    static class XmlDocInfo {
        String summary;
        String returns;
        List<XmlParam> params = new ArrayList<>();
        List<XmlThrows> throwsList = new ArrayList<>();
        String raw;
    }

    static class XmlParam {
        String name;
        String description;
    }

    static class XmlThrows {
        String exceptionType;
        String description;
    }

    static class CallInfo {
        String calleeName;
        String fullyQualifiedCalleeName;
        String returnType;
        List<String> parameterTypes;
        LineSpan lineSpan;
    }

    static class ThrownExceptionInfo {
        String exceptionType;
        String fullyQualifiedExceptionType;
    }

    static class LineSpan {
        int startLine;
        int startColumn;
        int endLine;
        int endColumn;
    }

    // System.out follows the platform charset, which mangles non-ASCII identifiers on Windows.
    // JSON is UTF-8 by definition, so write it as such regardless of host.
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static final PrintStream ERR =
            new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    /** Exit code used when the input could not be parsed at all. */
    private static final int EXIT_PARSE_FAILED = 2;

    public static void main(String[] args) throws Exception {
        String sourceRoot = "src";
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        // Only add JavaParserTypeSolver if the src directory exists
        // When parsing standalone code from stdin, no source tree is available
        Path srcPath = Paths.get(sourceRoot);
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
        String codeInput = sb.toString();

        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(codeInput);
        } catch (ParseProblemException e) {
            // A batch indexer should get an empty result plus a readable reason, not a stack trace
            // on stdout's sibling channel and nothing to consume.
            OUT.println("[]");
            e.getProblems().forEach(p -> ERR.println("JavaCodeParser: " + p.getMessage().split("\n")[0]));
            OUT.flush();
            ERR.flush();
            System.exit(EXIT_PARSE_FAILED);
            return;
        }

        List<MethodInfo> results = new ArrayList<>();

        String packageName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse(null);

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(Collectors.toList());

        String topComment = cu.getComment()
                .filter(JavadocComment.class::isInstance)
                .map(JavadocComment.class::cast)
                .map(JavadocComment::parse)
                .map(JavaCodeParser::extractAllJavadocInfoAsRaw)
                .orElse(null);

        for (TypeDeclaration<?> typeDecl : cu.getTypes()) {
            processTypeDeclaration(typeDecl, packageName, null, results, imports, topComment);
        }

        linkDataProviders(results);

        List<Map<String, Object>> finalJsonList = new ArrayList<>();
        for (MethodInfo mi : results) {
            finalJsonList.add(methodInfoToJsonMap(mi));
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonOutput = gson.toJson(finalJsonList);
        OUT.println(jsonOutput);
        OUT.flush();
    }

    /**
     * JavaParser's printer and Javadoc reader both emit the platform line separator, which would
     * make the same input produce different output on Windows and Linux.
     */
    private static String normalizeEol(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * @param enclosingTypeName dotted name of the types this declaration is nested in, or null at
     *                          the top level. Nested types are named Outer.Inner, so a method's
     *                          qualified name identifies exactly one declaration.
     */
    private static void processTypeDeclaration(TypeDeclaration<?> typeDecl, String packageName, String enclosingTypeName, List<MethodInfo> results, List<String> imports, String topComment) {
        if (typeDecl.isClassOrInterfaceDeclaration()) {
            processClassOrInterface(typeDecl.asClassOrInterfaceDeclaration(), packageName, enclosingTypeName, results, imports, topComment);
        } else if (typeDecl.isEnumDeclaration()) {
            processEnumDeclaration(typeDecl.asEnumDeclaration(), packageName, enclosingTypeName, results, imports, topComment);
        } else if (typeDecl.isRecordDeclaration()) {
            processRecordDeclaration(typeDecl.asRecordDeclaration(), packageName, enclosingTypeName, results, imports, topComment);
        }
    }

    private static String nest(String enclosingTypeName, String simpleName) {
        return enclosingTypeName == null ? simpleName : enclosingTypeName + "." + simpleName;
    }

    private static void processClassOrInterface(ClassOrInterfaceDeclaration classDecl, String packageName, String enclosingTypeName, List<MethodInfo> results, List<String> imports, String topComment) {
        String className = nest(enclosingTypeName, classDecl.getNameAsString());
        List<String> baseTypes = new ArrayList<>();
        baseTypes.addAll(classDecl.getExtendedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));
        baseTypes.addAll(classDecl.getImplementedTypes().stream().map(t -> t.asString()).collect(Collectors.toList()));

        for (MethodDeclaration method : classDecl.getMethods()) {
            MethodInfo info = extractMethodInfo(method, packageName, className, baseTypes, imports, topComment, classDecl);
            results.add(info);
        }

        for (BodyDeclaration<?> member : classDecl.getMembers()) {
            if (member.isClassOrInterfaceDeclaration()) {
                processClassOrInterface(member.asClassOrInterfaceDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isEnumDeclaration()) {
                processEnumDeclaration(member.asEnumDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isRecordDeclaration()) {
                processRecordDeclaration(member.asRecordDeclaration(), packageName, className, results, imports, topComment);
            }
        }
    }

    private static void processEnumDeclaration(EnumDeclaration enumDecl, String packageName, String enclosingTypeName, List<MethodInfo> results, List<String> imports, String topComment) {
        String className = nest(enclosingTypeName, enumDecl.getNameAsString());
        for (BodyDeclaration<?> member : enumDecl.getMembers()) {
            if (member.isMethodDeclaration()) {
                MethodInfo info = extractMethodInfo(member.asMethodDeclaration(), packageName, className, Collections.emptyList(), imports, topComment, enumDecl);
                results.add(info);
            }
        }
        for (BodyDeclaration<?> member : enumDecl.getMembers()) {
            if (member.isClassOrInterfaceDeclaration()) {
                processClassOrInterface(member.asClassOrInterfaceDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isEnumDeclaration()) {
                processEnumDeclaration(member.asEnumDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isRecordDeclaration()) {
                processRecordDeclaration(member.asRecordDeclaration(), packageName, className, results, imports, topComment);
            }
        }
    }

    private static void processRecordDeclaration(RecordDeclaration recordDecl, String packageName, String enclosingTypeName, List<MethodInfo> results, List<String> imports, String topComment) {
        String className = nest(enclosingTypeName, recordDecl.getNameAsString());
        for (BodyDeclaration<?> member : recordDecl.getMembers()) {
            if (member.isMethodDeclaration()) {
                MethodInfo info = extractMethodInfo(member.asMethodDeclaration(), packageName, className, Collections.emptyList(), imports, topComment, recordDecl);
                results.add(info);
            }
        }
        for (BodyDeclaration<?> member : recordDecl.getMembers()) {
            if (member.isClassOrInterfaceDeclaration()) {
                processClassOrInterface(member.asClassOrInterfaceDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isEnumDeclaration()) {
                processEnumDeclaration(member.asEnumDeclaration(), packageName, className, results, imports, topComment);
            } else if (member.isRecordDeclaration()) {
                processRecordDeclaration(member.asRecordDeclaration(), packageName, className, results, imports, topComment);
            }
        }
    }

    private static MethodInfo extractMethodInfo(MethodDeclaration method, String packageName, String className, List<String> baseTypes, List<String> imports, String topComment, TypeDeclaration<?> parentType) {
        MethodInfo info = new MethodInfo();
        info.name = method.getNameAsString();
        info.returnType = method.getType().asString();
        info.modifiers = method.getModifiers().stream().map(m -> m.getKeyword().asString()).collect(Collectors.toList());

        for (AnnotationExpr ann : method.getAnnotations()) {
            AnnotationInfo annInfo = new AnnotationInfo();
            annInfo.name = ann.getNameAsString();
            try {
                ResolvedAnnotationDeclaration rad = ann.resolve();
                annInfo.fullyQualifiedName = rad.getQualifiedName();
            } catch (Exception e) {
                annInfo.fullyQualifiedName = ann.getNameAsString();
            }

            if (ann.isNormalAnnotationExpr()) {
                NormalAnnotationExpr nae = ann.asNormalAnnotationExpr();
                for (MemberValuePair pair : nae.getPairs()) {
                    annInfo.values.put(pair.getNameAsString(), pair.getValue().toString().replace("\"", ""));
                }
            }

            // Without testng on the type solver path the annotation only resolves to its simple
            // name, which is the normal case when parsing a single file from stdin.
            boolean isTestAnnotation = annInfo.fullyQualifiedName.endsWith(".Test") || annInfo.name.equals("Test");
            if (isTestAnnotation && annInfo.values.containsKey("dataProvider")) {
                info.dataProviderName = annInfo.values.get("dataProvider");
            }

            info.annotations.add(annInfo);
        }

        info.namespaceName = packageName;
        info.className = className;
        info.classBaseTypes = baseTypes;
        info.importedTypes = imports;
        info.topComment = topComment;

        try {
            ResolvedReferenceTypeDeclaration decl = method.resolve().declaringType().asReferenceType();
            List<ResolvedReferenceType> ancestors = decl.getAllAncestors();
            info.inheritanceHierarchy = ancestors.stream()
                    .map(a -> a.getQualifiedName())
                    .collect(Collectors.toList());
        } catch (Exception ignored) {}

        for (Parameter p : method.getParameters()) {
            ParameterInfo pi = new ParameterInfo();
            pi.name = p.getNameAsString();
            pi.type = p.getType().asString();
            try {
                ResolvedType pt = p.getType().resolve();
                pi.fullyQualifiedType = pt.describe();
            } catch (Exception e) {
                pi.fullyQualifiedType = pi.type;
            }
            info.parameters.add(pi);
        }

        info.fullCode = normalizeEol(method.toString());
        info.code = normalizeEol(method.getBody().map(Object::toString).orElse(method.toString()));

        method.getThrownExceptions().forEach(te -> {
            ThrownExceptionInfo tei = new ThrownExceptionInfo();
            tei.exceptionType = te.asString();
            try {
                ResolvedType rt = te.resolve();
                tei.fullyQualifiedExceptionType = rt.describe();
            } catch (Exception ex) {
                tei.fullyQualifiedExceptionType = tei.exceptionType;
            }
            info.thrownExceptions.add(tei);
        });

        method.getComment().ifPresent(comment -> {
            if (comment.isJavadocComment()) {
                try {
                    Javadoc jd = comment.asJavadocComment().parse();
                    info.xmlDoc.raw = normalizeEol(jd.toText());
                    info.xmlDoc.summary = normalizeEol(jd.getDescription().toText().trim());

                    for (JavadocBlockTag tag : jd.getBlockTags()) {
                        switch (tag.getTagName()) {
                            case "param":
                                XmlParam xp = new XmlParam();
                                xp.name = tag.getName().orElse("");
                                xp.description = normalizeEol(tag.getContent().toText().trim());
                                info.xmlDoc.params.add(xp);
                                break;
                            case "return":
                                info.xmlDoc.returns = normalizeEol(tag.getContent().toText().trim());
                                break;
                            case "throws":
                                XmlThrows xt = new XmlThrows();
                                xt.exceptionType = tag.getName().orElse("");
                                xt.description = normalizeEol(tag.getContent().toText().trim());
                                info.xmlDoc.throwsList.add(xt);
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

        try {
            ResolvedMethodDeclaration rmd = method.resolve();
            info.fullyQualifiedReturnType = rmd.getReturnType().describe();
            info.isOverride = isOverrideMethod(rmd);
            info.implementedInterfaceMembers = getImplementedInterfaceMembers(rmd);
        } catch (Exception e) {
            // Ignore if symbol resolution fails
        }

        if (method.getBody().isPresent()) {
            for (MethodCallExpr call : method.getBody().get().findAll(MethodCallExpr.class)) {
                CallInfo ci = new CallInfo();
                ci.calleeName = call.getNameAsString();
                call.getRange().ifPresent(range -> {
                    ci.lineSpan = new LineSpan();
                    ci.lineSpan.startLine = range.begin.line;
                    ci.lineSpan.startColumn = range.begin.column;
                    ci.lineSpan.endLine = range.end.line;
                    ci.lineSpan.endColumn = range.end.column;
                });

                try {
                    ResolvedMethodDeclaration cm = call.resolve();
                    ci.fullyQualifiedCalleeName = cm.getQualifiedSignature();
                    ci.returnType = cm.getReturnType().describe();
                    ci.parameterTypes = new ArrayList<>();
                    for (int i = 0; i < cm.getNumberOfParams(); i++) {
                        ci.parameterTypes.add(cm.getParam(i).getType().describe());
                    }
                } catch (Exception ex) {
                    ci.fullyQualifiedCalleeName = ci.calleeName;
                }

                info.calls.add(ci);
            }
        }

        method.getRange().ifPresent(range -> {
            LineSpan ls = new LineSpan();
            ls.startLine = range.begin.line;
            ls.startColumn = range.begin.column;
            ls.endLine = range.end.line;
            ls.endColumn = range.end.column;
            info.lineSpan = ls;
        });

        return info;
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

    private static void linkDataProviders(List<MethodInfo> methods) {
        Map<String, MethodInfo> dataProviders = new HashMap<>();
        for (MethodInfo mi : methods) {
            for (AnnotationInfo ann : mi.annotations) {
                boolean isDataProvider = ann.fullyQualifiedName.endsWith(".DataProvider") || ann.name.equals("DataProvider");
                if (isDataProvider && ann.values.containsKey("name")) {
                    String dpName = ann.values.get("name");
                    dataProviders.put(dpName, mi);
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

    private static String extractAllJavadocInfoAsRaw(Javadoc jd) {
        return normalizeEol(jd.toText());
    }

    private static Map<String, Object> methodInfoToJsonMap(MethodInfo mi) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("symbol_type", "method");
        map.put("name", mi.name);

        String qualifiedName = (mi.namespaceName != null ? mi.namespaceName + "." : "")
                + (mi.className != null ? mi.className + "." : "") + mi.name;
        map.put("qualified_name", qualifiedName);

        map.put("namespace", mi.namespaceName != null ? mi.namespaceName : "");
        map.put("modifiers", mi.modifiers);

        List<Map<String, Object>> annotationList = new ArrayList<>();
        for (AnnotationInfo ann : mi.annotations) {
            Map<String, Object> annMap = new HashMap<>();
            annMap.put("name", ann.name);
            annMap.put("fully_qualified_name", ann.fullyQualifiedName);
            annMap.put("values", ann.values);
            annotationList.add(annMap);
        }
        map.put("annotations", annotationList);

        List<Map<String, Object>> paramsList = new ArrayList<>();
        for (ParameterInfo p : mi.parameters) {
            Map<String, Object> pMap = new HashMap<>();
            pMap.put("name", p.name);
            pMap.put("type", p.type);
            pMap.put("fully_qualified_type", p.fullyQualifiedType);
            paramsList.add(pMap);
        }
        map.put("parameters", paramsList);

        Map<String, Object> returnTypeMap = new HashMap<>();
        returnTypeMap.put("type", mi.returnType);
        returnTypeMap.put("fully_qualified_type", mi.fullyQualifiedReturnType != null ? mi.fullyQualifiedReturnType : mi.returnType);
        map.put("return_type", returnTypeMap);

        Map<String, Object> docMap = new HashMap<>();
        docMap.put("summary", mi.xmlDoc.summary);

        List<Map<String, String>> docParams = new ArrayList<>();
        for (XmlParam xp : mi.xmlDoc.params) {
            Map<String, String> xpMap = new HashMap<>();
            xpMap.put("name", xp.name);
            xpMap.put("description", xp.description);
            docParams.add(xpMap);
        }
        docMap.put("params", docParams);
        docMap.put("returns", mi.xmlDoc.returns);

        List<Map<String, String>> docThrowsList = new ArrayList<>();
        for (XmlThrows xt : mi.xmlDoc.throwsList) {
            Map<String, String> xtMap = new HashMap<>();
            xtMap.put("exception_type", xt.exceptionType);
            xtMap.put("description", xt.description);
            docThrowsList.add(xtMap);
        }
        docMap.put("throws", docThrowsList);
        docMap.put("raw", mi.xmlDoc.raw);
        map.put("documentation", docMap);

        map.put("body_code", mi.code);
        map.put("full_code", mi.fullCode);

        List<Map<String, Object>> callsList = new ArrayList<>();
        for (CallInfo c : mi.calls) {
            Map<String, Object> cMap = new HashMap<>();
            cMap.put("callee_name", c.calleeName);
            cMap.put("fully_qualified_callee_name", c.fullyQualifiedCalleeName);
            cMap.put("return_type", c.returnType);
            cMap.put("parameter_types", c.parameterTypes);
            if (c.lineSpan != null) {
                Map<String, Integer> callLineSpan = new HashMap<>();
                callLineSpan.put("start_line", c.lineSpan.startLine);
                callLineSpan.put("start_column", c.lineSpan.startColumn);
                callLineSpan.put("end_line", c.lineSpan.endLine);
                callLineSpan.put("end_column", c.lineSpan.endColumn);
                cMap.put("line_span", callLineSpan);
            } else {
                cMap.put("line_span", null);
            }
            callsList.add(cMap);
        }
        map.put("calls", callsList);

        if (mi.lineSpan != null) {
            Map<String, Integer> lineSpanMap = new HashMap<>();
            lineSpanMap.put("start_line", mi.lineSpan.startLine);
            lineSpanMap.put("start_column", mi.lineSpan.startColumn);
            lineSpanMap.put("end_line", mi.lineSpan.endLine);
            lineSpanMap.put("end_column", mi.lineSpan.endColumn);
            map.put("line_span", lineSpanMap);
        } else {
            map.put("line_span", null);
        }

        map.put("inherits_from", mi.classBaseTypes);
        map.put("implemented_interface_members", mi.implementedInterfaceMembers);

        List<Map<String, String>> thrownExList = new ArrayList<>();
        for (ThrownExceptionInfo te : mi.thrownExceptions) {
            Map<String, String> teMap = new HashMap<>();
            teMap.put("exception_type", te.exceptionType);
            teMap.put("fully_qualified_exception_type", te.fullyQualifiedExceptionType);
            thrownExList.add(teMap);
        }
        map.put("thrown_exceptions", thrownExList);

        map.put("is_override", mi.isOverride != null ? mi.isOverride : false);

        map.put("data_provider_name", mi.dataProviderName);
        map.put("data_provider_source", mi.dataProviderMethod != null ? mi.dataProviderMethod.name : null);

        map.put("imported_types", mi.importedTypes);
        map.put("top_level_comment", mi.topComment);
        map.put("inheritance_hierarchy", mi.inheritanceHierarchy);
        map.put("language", "java");

        return map;
    }
}
