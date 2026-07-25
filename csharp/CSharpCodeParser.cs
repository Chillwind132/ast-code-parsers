// ============================================================================
// CSharpCodeParser - C# Method Metadata Extractor for RAG Indexing
// ============================================================================
//
// OVERVIEW
// --------
// This parser extracts rich semantic metadata from C# source code for use in
// RAG-based code search, retrieval and static analysis pipelines. It uses the
// Roslyn compiler API to parse C# code from stdin and outputs a JSON array of method
// metadata objects to stdout. The output schema is designed to be consistent
// with the companion JavaCodeParser for cross-language code search.
//
// USAGE
// -----
// Pipe C# source code to stdin; receive JSON method metadata on stdout:
//   cat MyClass.cs | ./CSharpCodeParser > methods.json
//
// BUILD INSTRUCTIONS
// ------------------
// Windows:  dotnet publish -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true
// Linux:    dotnet publish -c Release -r linux-x64 --self-contained true /p:PublishSingleFile=true
//
// EXTRACTED METADATA
// ------------------
// For each method in the source file, the parser extracts:
//   - Identity:       Method name, qualified name, namespace, containing class
//   - Signature:      Return type, parameters (with fully qualified types when resolvable)
//   - Modifiers:      Access level, static, virtual, async, etc.
//   - Attributes:     All applied attributes (C# equivalent of Java annotations)
//   - Documentation:  XML doc comments (summary, param, returns tags)
//   - Source Code:    Full method text and body-only snippet
//   - Inheritance:    Base types, interface implementations, override detection
//   - Call Graph:     Method invocations within the body (callee, types, positions)
//   - Location:       Line/column spans for precise source mapping
//
// OUTPUT FORMAT
// -------------
// JSON array where each element represents one method with fields including:
// symbol_type, name, qualified_name, namespace, modifiers, annotations,
// parameters, return_type, documentation, body_code, full_code, calls,
// line_span, inherits_from, implemented_interface_members, is_override, language
//
// DEPENDENCIES
// ------------
// Requires Microsoft.CodeAnalysis.CSharp (Roslyn) NuGet package.
//
// ============================================================================

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Xml.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Basic.Reference.Assemblies;

class Program
{
    // Emits "System.String" rather than the "string" keyword alias, so fully qualified
    // types line up with the Java parser's output.
    static readonly SymbolDisplayFormat QualifiedFormat = new SymbolDisplayFormat(
        globalNamespaceStyle: SymbolDisplayGlobalNamespaceStyle.Omitted,
        typeQualificationStyle: SymbolDisplayTypeQualificationStyle.NameAndContainingTypesAndNamespaces,
        genericsOptions: SymbolDisplayGenericsOptions.IncludeTypeParameters,
        miscellaneousOptions: SymbolDisplayMiscellaneousOptions.ExpandNullable);

    /// <summary>Exit code used when the input contains syntax errors; output is still emitted.</summary>
    const int ExitParseErrors = 2;

    static int Main()
    {
        string code = ReadAllStdin();

        // Parse code
        SyntaxTree tree = CSharpSyntaxTree.ParseText(code);
        var root = tree.GetCompilationUnitRoot();

        // Roslyn error-recovers, so a malformed file still yields plausible-looking output.
        // Report the syntax errors so the caller can tell degraded results from clean ones.
        var syntaxErrors = tree.GetDiagnostics()
            .Where(d => d.Severity == DiagnosticSeverity.Error)
            .ToList();
        foreach (var d in syntaxErrors)
        {
            Console.Error.WriteLine("CSharpCodeParser: " + d.ToString());
        }

        // Attempt to create a Compilation for semantic info.
        // Reference assemblies are embedded rather than loaded from disk, because
        // Assembly.Location returns an empty string in single-file published apps.
        Compilation compilation = null;
        try
        {
            compilation = CSharpCompilation.Create(
                "CodeAnalysis",
                new[] { tree },
                Net80.References.All,
                new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        }
        catch
        {
            // If we can't create a compilation, semanticModel remains null.
        }

        SemanticModel semanticModel = null;
        try
        {
            if (compilation != null)
                semanticModel = compilation.GetSemanticModel(tree);
        }
        catch
        {
            // proceed without semantic info
        }

        var methods = root.DescendantNodes().OfType<MethodDeclarationSyntax>();
        var results = new List<MethodInfo>();

        foreach (var method in methods)
        {
            var info = new MethodInfo
            {
                Name = method.Identifier.Text,
                FullCode = method.ToFullString(),
                ReturnType = method.ReturnType.ToString().Trim(),
                Parameters = method.ParameterList.Parameters.Select(p => new ParameterInfo
                {
                    Name = p.Identifier.Text,
                    Type = p.Type?.ToString().Trim()
                }).ToList(),
                Modifiers = method.Modifiers.Select(m => m.Text).ToList(),
                Attributes = method.AttributeLists
                    .SelectMany(al => al.Attributes)
                    .Select(a => a.Name.ToString())
                    .ToList(),
                Code = method.Body?.ToFullString() ?? method.ExpressionBody?.ToFullString() ?? "{}",
                XmlDoc = new XmlDocInfo()
            };

            // XML Doc parsing
            var leadingTrivia = method.GetLeadingTrivia();
            var xmlCommentString = string.Join("\n", leadingTrivia
                .Where(t => t.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia) ||
                            t.IsKind(SyntaxKind.MultiLineDocumentationCommentTrivia))
                .Select(t => t.ToFullString()));
            if (!string.IsNullOrWhiteSpace(xmlCommentString))
            {
                try
                {
                    var cleaned = CleanXmlDoc(xmlCommentString);
                    var xdoc = XDocument.Parse("<root>" + cleaned + "</root>");

                    info.XmlDoc.Summary = xdoc.Descendants("summary").FirstOrDefault()?.Value.Trim();
                    info.XmlDoc.Returns = xdoc.Descendants("returns").FirstOrDefault()?.Value.Trim();
                    info.XmlDoc.Params = xdoc.Descendants("param")
                        .Select(pe => new XmlParam
                        {
                            Name = pe.Attribute("name")?.Value,
                            Description = pe.Value.Trim()
                        })
                        .ToList();
                }
                catch
                {
                    info.XmlDoc.Raw = xmlCommentString.Trim();
                }
            }

            // Walk outwards to the compilation unit, accumulating every enclosing type and
            // namespace. TypeDeclarationSyntax covers class, struct, interface and record;
            // BaseNamespaceDeclarationSyntax covers both block and file-scoped namespaces.
            // Each segment is prepended, so nested declarations keep their full path rather than
            // collapsing onto whichever one the walk happened to see last.
            var typeNames = new List<string>();
            var namespaceParts = new List<string>();
            bool baseTypesFound = false;
            for (var parent = method.Parent; parent != null; parent = parent.Parent)
            {
                if (parent is TypeDeclarationSyntax tds)
                {
                    typeNames.Insert(0, tds.Identifier.Text);
                    if (!baseTypesFound)
                    {
                        // Only the innermost type declares this method's base types.
                        info.ClassBaseTypes = tds.BaseList?.Types.Select(t => t.Type.ToString()).ToList() ?? new List<string>();
                        baseTypesFound = true;
                    }
                }
                else if (parent is BaseNamespaceDeclarationSyntax nds)
                {
                    namespaceParts.Insert(0, nds.Name.ToString());
                }
            }
            info.ClassName = typeNames.Count > 0 ? string.Join(".", typeNames) : null;
            info.NamespaceName = namespaceParts.Count > 0 ? string.Join(".", namespaceParts) : null;

            // Semantic analysis for return types, overrides, etc.
            if (semanticModel != null)
            {
                var symbol = semanticModel.GetDeclaredSymbol(method);
                if (symbol is IMethodSymbol methodSymbol)
                {
                    info.IsOverride = methodSymbol.IsOverride;
                    info.ImplementedInterfaceMembers = methodSymbol.ExplicitInterfaceImplementations
                        .Select(m => m.ToDisplayString())
                        .ToList();

                    try
                    {
                        info.FullyQualifiedReturnType = methodSymbol.ReturnType?.ToDisplayString(QualifiedFormat);
                    }
                    catch { }

                    for (int i = 0; i < methodSymbol.Parameters.Length && i < info.Parameters.Count; i++)
                    {
                        info.Parameters[i].FullyQualifiedType = methodSymbol.Parameters[i].Type.ToDisplayString(QualifiedFormat);
                    }
                }
            }

            // Analyze calls
            info.Calls = new List<CallInfo>();
            var bodyNode = (SyntaxNode)method.Body ?? method.ExpressionBody?.Expression;
            if (bodyNode != null)
            {
                var invocations = bodyNode.DescendantNodes().OfType<InvocationExpressionSyntax>();
                foreach (var invocation in invocations)
                {
                    var callInfo = new CallInfo();
                    var lineSpan = tree.GetLineSpan(invocation.Span);
                    callInfo.LineSpan = new LineSpan
                    {
                        StartLine = lineSpan.StartLinePosition.Line + 1,
                        StartColumn = lineSpan.StartLinePosition.Character + 1,
                        EndLine = lineSpan.EndLinePosition.Line + 1,
                        EndColumn = lineSpan.EndLinePosition.Character + 1
                    };

                    if (semanticModel != null)
                    {
                        var symInfo = semanticModel.GetSymbolInfo(invocation);
                        if (symInfo.Symbol is IMethodSymbol callSymbol)
                        {
                            callInfo.CalleeName = callSymbol.Name;
                            callInfo.FullyQualifiedCalleeName = callSymbol.ContainingType.ToDisplayString(QualifiedFormat) + "." + callSymbol.Name;
                            callInfo.ReturnType = callSymbol.ReturnType.ToDisplayString(QualifiedFormat);
                            callInfo.ParameterTypes = callSymbol.Parameters.Select(p => p.Type.ToDisplayString(QualifiedFormat)).ToList();
                        }
                        else
                        {
                            callInfo.CalleeName = invocation.Expression.ToString();
                        }
                    }
                    else
                    {
                        callInfo.CalleeName = invocation.Expression.ToString();
                    }

                    info.Calls.Add(callInfo);
                }
            }

            // Location info
            var methodLineSpan = tree.GetLineSpan(method.Span);
            info.LineSpan = new LineSpan
            {
                StartLine = methodLineSpan.StartLinePosition.Line + 1,
                StartColumn = methodLineSpan.StartLinePosition.Character + 1,
                EndLine = methodLineSpan.EndLinePosition.Line + 1,
                EndColumn = methodLineSpan.EndLinePosition.Character + 1
            };

            results.Add(info);
        }

        // Transform MethodInfo objects into standardized JSON
        var finalJsonList = new List<Dictionary<string, object>>();
        foreach (var mi in results)
        {
            finalJsonList.Add(MethodInfoToJsonMap(mi));
        }

        string jsonOutput = JsonSerializer.Serialize(finalJsonList, new JsonSerializerOptions { WriteIndented = true });
        Console.WriteLine(jsonOutput);

        return syntaxErrors.Count > 0 ? ExitParseErrors : 0;
    }

    static string ReadAllStdin()
    {
        using var reader = new StreamReader(Console.OpenStandardInput());
        // Echoed source text would otherwise carry the host's line separator into the JSON,
        // making the same file produce different output on Windows and Linux.
        return reader.ReadToEnd().Replace("\r\n", "\n");
    }

    static string CleanXmlDoc(string xml)
    {
        var lines = xml.Split('\n')
            .Select(l => l.TrimStart('/', ' ', '\t', '*').TrimEnd('\r'))
            .Where(l => !string.IsNullOrWhiteSpace(l))
            .ToArray();
        return string.Join("\n", lines);
    }

    static Dictionary<string, object> MethodInfoToJsonMap(MethodInfo mi)
    {
        // Construct qualified_name
        string qualifiedName = (mi.NamespaceName != null ? mi.NamespaceName + "." : "")
            + (mi.ClassName != null ? mi.ClassName + "." : "")
            + mi.Name;

        var map = new Dictionary<string, object>();

        // Use a similar structure as the Java parser
        map["symbol_type"] = "method";
        map["name"] = mi.Name;
        map["qualified_name"] = qualifiedName;
        map["namespace"] = mi.NamespaceName ?? "";
        map["modifiers"] = mi.Modifiers;

        // Attributes -> annotations
        // We'll store them simply as name + no values since we don't have value pairs easily
        var annotationList = new List<Dictionary<string, object>>();
        foreach (var attr in mi.Attributes)
        {
            var annMap = new Dictionary<string, object>();
            annMap["name"] = attr;
            annMap["fully_qualified_name"] = attr; // Without semantic info for attrs, just repeat name
            annMap["values"] = new Dictionary<string, string>(); // empty
            annotationList.Add(annMap);
        }
        map["annotations"] = annotationList;

        // Parameters
        var paramsList = new List<Dictionary<string, object>>();
        foreach (var p in mi.Parameters)
        {
            var pMap = new Dictionary<string, object>();
            pMap["name"] = p.Name;
            pMap["type"] = p.Type ?? "";
            pMap["fully_qualified_type"] = p.FullyQualifiedType ?? p.Type ?? "";
            paramsList.Add(pMap);
        }
        map["parameters"] = paramsList;

        // return_type (as object)
        var returnTypeMap = new Dictionary<string, object>();
        returnTypeMap["type"] = mi.ReturnType ?? "";
        returnTypeMap["fully_qualified_type"] = mi.FullyQualifiedReturnType ?? mi.ReturnType ?? "";
        map["return_type"] = returnTypeMap;

        // documentation
        var docMap = new Dictionary<string, object>();
        docMap["summary"] = mi.XmlDoc.Summary;
        var docParams = new List<Dictionary<string, string>>();
        foreach (var xp in mi.XmlDoc.Params)
        {
            var xpMap = new Dictionary<string, string>();
            xpMap["name"] = xp.Name ?? "";
            xpMap["description"] = xp.Description ?? "";
            docParams.Add(xpMap);
        }
        docMap["params"] = docParams;
        docMap["returns"] = mi.XmlDoc.Returns;
        docMap["throws"] = new List<Dictionary<string, string>>(); // No throws extracted in C#
        docMap["raw"] = mi.XmlDoc.Raw;
        map["documentation"] = docMap;

        // body_code & full_code
        map["body_code"] = mi.Code;
        map["full_code"] = mi.FullCode;

        // calls
        var callsList = new List<Dictionary<string, object>>();
        foreach (var c in mi.Calls)
        {
            var cMap = new Dictionary<string, object>();
            cMap["callee_name"] = c.CalleeName ?? "";
            cMap["fully_qualified_callee_name"] = c.FullyQualifiedCalleeName ?? c.CalleeName ?? "";
            cMap["return_type"] = c.ReturnType ?? "";
            cMap["parameter_types"] = c.ParameterTypes ?? new List<string>();
            if (c.LineSpan != null)
            {
                var callLineSpan = new Dictionary<string, int>();
                callLineSpan["start_line"] = c.LineSpan.StartLine;
                callLineSpan["start_column"] = c.LineSpan.StartColumn;
                callLineSpan["end_line"] = c.LineSpan.EndLine;
                callLineSpan["end_column"] = c.LineSpan.EndColumn;
                cMap["line_span"] = callLineSpan;
            }
            else
            {
                cMap["line_span"] = null;
            }
            callsList.Add(cMap);
        }
        map["calls"] = callsList;

        // line_span
        if (mi.LineSpan != null)
        {
            var lineSpanMap = new Dictionary<string, int>();
            lineSpanMap["start_line"] = mi.LineSpan.StartLine;
            lineSpanMap["start_column"] = mi.LineSpan.StartColumn;
            lineSpanMap["end_line"] = mi.LineSpan.EndLine;
            lineSpanMap["end_column"] = mi.LineSpan.EndColumn;
            map["line_span"] = lineSpanMap;
        }
        else
        {
            map["line_span"] = null;
        }

        // inherits_from
        map["inherits_from"] = mi.ClassBaseTypes;

        // implemented_interface_members
        map["implemented_interface_members"] = mi.ImplementedInterfaceMembers;

        // thrown_exceptions (C# not extracted, empty)
        map["thrown_exceptions"] = new List<Dictionary<string, string>>();

        // is_override
        map["is_override"] = mi.IsOverride ?? false;

        // data_provider_name & data_provider_source not applicable here
        map["data_provider_name"] = null;
        map["data_provider_source"] = null;

        // imported_types not extracted in this parser
        map["imported_types"] = new List<string>();

        // top_level_comment not extracted
        map["top_level_comment"] = null;

        // inheritance_hierarchy not extracted
        map["inheritance_hierarchy"] = new List<string>();

        // language
        map["language"] = "csharp";

        return map;
    }
}

class MethodInfo
{
    public string Name { get; set; }
    public string ReturnType { get; set; }
    public List<ParameterInfo> Parameters { get; set; } = new();
    public List<string> Modifiers { get; set; } = new();
    public List<string> Attributes { get; set; } = new();
    public string NamespaceName { get; set; }
    public string ClassName { get; set; }
    public XmlDocInfo XmlDoc { get; set; }
    public string Code { get; set; }
    public string FullCode { get; set; }

    public bool? IsOverride { get; set; }
    public List<string> ImplementedInterfaceMembers { get; set; } = new();
    public string FullyQualifiedReturnType { get; set; }
    public List<string> ClassBaseTypes { get; set; } = new();
    public List<CallInfo> Calls { get; set; }
    public LineSpan LineSpan { get; set; }
}

class ParameterInfo
{
    public string Name { get; set; }
    public string Type { get; set; }
    public string FullyQualifiedType { get; set; }
}

class XmlDocInfo
{
    public string Summary { get; set; }
    public string Returns { get; set; }
    public List<XmlParam> Params { get; set; } = new();
    public string Raw { get; set; }
}

class XmlParam
{
    public string Name { get; set; }
    public string Description { get; set; }
}

class CallInfo
{
    public string CalleeName { get; set; }
    public string FullyQualifiedCalleeName { get; set; }
    public string ReturnType { get; set; }
    public List<string> ParameterTypes { get; set; }
    public LineSpan LineSpan { get; set; }
}

class LineSpan
{
    public int StartLine { get; set; }
    public int StartColumn { get; set; }
    public int EndLine { get; set; }
    public int EndColumn { get; set; }
}
