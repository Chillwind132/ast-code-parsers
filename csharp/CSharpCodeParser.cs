// ============================================================================
// CSharpCodeParser - C# Code Metadata Extractor for RAG Indexing
// ============================================================================
//
// OVERVIEW
// --------
// This parser extracts rich semantic metadata from C# source code for use in
// RAG-based code search, retrieval and static analysis pipelines. It uses the
// Roslyn compiler API to parse C# code from stdin and outputs a JSON document
// describing the file, its types and their methods to stdout. The output schema
// is identical to the companion JavaCodeParser for cross-language code search.
//
// USAGE
// -----
// Pipe C# source code to stdin; receive JSON on stdout:
//   cat MyClass.cs | ./CSharpCodeParser > methods.json
//
//   --file <path>       record <path> in the "file" field; consumers use it for node ids
//   --include-source    also emit full_code and body_offset per method
//   --pretty            indent the JSON; off by default, since indentation is most of the payload
//
// Exit codes: 0 parsed cleanly, 2 the input has syntax errors (described on
// stderr; output is still emitted but may be incomplete).
//
// BUILD INSTRUCTIONS
// ------------------
// Windows:  dotnet publish -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true
// Linux:    dotnet publish -c Release -r linux-x64 --self-contained true /p:PublishSingleFile=true
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
// Requires Microsoft.CodeAnalysis.CSharp (Roslyn) NuGet package.
//
// ============================================================================

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Text;
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

    const int SchemaVersion = 2;

    /// <summary>Exit code used when the input contains syntax errors; output is still emitted.</summary>
    const int ExitParseErrors = 2;

    static string _filePath;
    static bool _includeSource;
    static bool _pretty;
    static SemanticModel _semanticModel;
    static SyntaxTree _tree;

    static int Main(string[] args)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--file" && i + 1 < args.Length) _filePath = args[++i];
            else if (args[i] == "--include-source") _includeSource = true;
            else if (args[i] == "--pretty") _pretty = true;
        }

        string code = ReadAllStdin();

        _tree = CSharpSyntaxTree.ParseText(code);
        var root = _tree.GetCompilationUnitRoot();

        // Roslyn error-recovers, so a malformed file still yields plausible-looking output.
        // Report the syntax errors so the caller can tell degraded results from clean ones.
        var syntaxErrors = _tree.GetDiagnostics()
            .Where(d => d.Severity == DiagnosticSeverity.Error)
            .ToList();
        foreach (var d in syntaxErrors)
        {
            Console.Error.WriteLine("CSharpCodeParser: " + d.ToString());
        }

        // Reference assemblies are embedded rather than loaded from disk, because
        // Assembly.Location returns an empty string in single-file published apps.
        Compilation compilation = null;
        try
        {
            compilation = CSharpCompilation.Create(
                "CodeAnalysis",
                new[] { _tree },
                Net80.References.All,
                new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        }
        catch
        {
            // If we can't create a compilation, semanticModel remains null.
        }

        try
        {
            if (compilation != null)
                _semanticModel = compilation.GetSemanticModel(_tree);
        }
        catch
        {
            // proceed without semantic info
        }

        var types = new List<Dictionary<string, object>>();
        // BaseTypeDeclarationSyntax covers class, struct, interface, record and enum. Nested
        // types are emitted as their own entries, each carrying its full dotted name.
        foreach (var typeDecl in root.DescendantNodes().OfType<BaseTypeDeclarationSyntax>())
        {
            types.Add(BuildType(typeDecl));
        }

        var document = new Dictionary<string, object>
        {
            ["schema_version"] = SchemaVersion,
            ["file"] = _filePath,
            ["language"] = "csharp",
            ["imports"] = root.Usings.Select(u => u.Name?.ToString() ?? u.ToString().Trim()).ToList(),
            ["top_level_comment"] = ExtractTopLevelComment(root),
            ["types"] = types
        };

        // Indentation is most of the payload on a real file, so it is opt-in.
        Console.WriteLine(JsonSerializer.Serialize(document, new JsonSerializerOptions { WriteIndented = _pretty }));

        return syntaxErrors.Count > 0 ? ExitParseErrors : 0;
    }

    static string ReadAllStdin()
    {
        using var reader = new StreamReader(Console.OpenStandardInput());
        // Echoed source text would otherwise carry the host's line separator into the JSON,
        // making the same file produce different output on Windows and Linux.
        return reader.ReadToEnd().Replace("\r\n", "\n");
    }

    /// <summary>File header comment: the comment trivia ahead of the first declaration.</summary>
    static string ExtractTopLevelComment(CompilationUnitSyntax root)
    {
        var comments = root.GetLeadingTrivia()
            .Where(t => t.IsKind(SyntaxKind.SingleLineCommentTrivia) ||
                        t.IsKind(SyntaxKind.MultiLineCommentTrivia) ||
                        t.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia) ||
                        t.IsKind(SyntaxKind.MultiLineDocumentationCommentTrivia))
            .Select(t => t.ToString().Trim())
            .ToList();
        return comments.Count > 0 ? string.Join("\n", comments) : null;
    }

    // ------------------------------------------------------------------ types

    static Dictionary<string, object> BuildType(BaseTypeDeclarationSyntax typeDecl)
    {
        // Walk outwards, accumulating every enclosing type and namespace, so nested
        // declarations keep their full path instead of collapsing onto one segment.
        var typeNames = new List<string> { typeDecl.Identifier.Text };
        var namespaceParts = new List<string>();
        for (var parent = typeDecl.Parent; parent != null; parent = parent.Parent)
        {
            if (parent is BaseTypeDeclarationSyntax outer)
                typeNames.Insert(0, outer.Identifier.Text);
            else if (parent is BaseNamespaceDeclarationSyntax ns)
                namespaceParts.Insert(0, ns.Name.ToString());
        }

        string namespaceName = namespaceParts.Count > 0 ? string.Join(".", namespaceParts) : "";
        string name = string.Join(".", typeNames);
        string qualifiedName = (namespaceName.Length > 0 ? namespaceName + "." : "") + name;

        var methods = new List<Dictionary<string, object>>();
        if (typeDecl is TypeDeclarationSyntax tds)
        {
            // Direct members only; a nested type's methods belong to that type's entry.
            foreach (var method in tds.Members.OfType<MethodDeclarationSyntax>())
            {
                methods.Add(BuildMethod(method, qualifiedName));
            }
        }

        return new Dictionary<string, object>
        {
            ["name"] = name,
            ["qualified_name"] = qualifiedName,
            ["namespace"] = namespaceName,
            ["kind"] = KindOf(typeDecl),
            ["inherits_from"] = typeDecl.BaseList?.Types.Select(t => t.Type.ToString()).ToList() ?? new List<string>(),
            ["inheritance_hierarchy"] = InheritanceHierarchy(typeDecl),
            ["methods"] = methods
        };
    }

    static string KindOf(BaseTypeDeclarationSyntax typeDecl) => typeDecl switch
    {
        RecordDeclarationSyntax => "record",
        ClassDeclarationSyntax => "class",
        StructDeclarationSyntax => "struct",
        InterfaceDeclarationSyntax => "interface",
        EnumDeclarationSyntax => "enum",
        _ => "class"
    };

    static List<string> InheritanceHierarchy(BaseTypeDeclarationSyntax typeDecl)
    {
        var hierarchy = new List<string>();
        if (_semanticModel == null) return hierarchy;

        if (_semanticModel.GetDeclaredSymbol(typeDecl) is INamedTypeSymbol symbol)
        {
            for (var b = symbol.BaseType; b != null; b = b.BaseType)
            {
                hierarchy.Add(b.ToDisplayString(QualifiedFormat));
            }
            foreach (var i in symbol.AllInterfaces)
            {
                hierarchy.Add(i.ToDisplayString(QualifiedFormat));
            }
        }
        return hierarchy;
    }

    // ---------------------------------------------------------------- methods

    static Dictionary<string, object> BuildMethod(MethodDeclarationSyntax method, string typeQualifiedName)
    {
        var map = new Dictionary<string, object>
        {
            ["name"] = method.Identifier.Text,
            ["qualified_name"] = typeQualifiedName + "." + method.Identifier.Text,
            ["modifiers"] = method.Modifiers.Select(m => m.Text).ToList()
        };

        IMethodSymbol symbol = _semanticModel?.GetDeclaredSymbol(method);

        map["annotations"] = method.AttributeLists
            .SelectMany(al => al.Attributes)
            .Select(BuildAnnotation)
            .ToList();

        // parameters: qualified type plus an explicit flag, so an unresolved type is not
        // silently indistinguishable from a resolved one.
        var parameters = new List<Dictionary<string, object>>();
        var declared = method.ParameterList.Parameters;
        for (int i = 0; i < declared.Count; i++)
        {
            string written = declared[i].Type?.ToString().Trim() ?? "";
            var resolvedType = symbol != null && i < symbol.Parameters.Length
                ? symbol.Parameters[i].Type
                : null;
            parameters.Add(new Dictionary<string, object>
            {
                ["name"] = declared[i].Identifier.Text,
                ["type"] = resolvedType?.ToDisplayString(QualifiedFormat) ?? written,
                ["resolved"] = resolvedType != null
            });
        }
        map["parameters"] = parameters;

        map["return_type"] = new Dictionary<string, object>
        {
            ["type"] = symbol?.ReturnType?.ToDisplayString(QualifiedFormat) ?? method.ReturnType.ToString().Trim(),
            ["resolved"] = symbol?.ReturnType != null
        };

        var doc = ExtractXmlDoc(method);
        map["documentation"] = new Dictionary<string, object>
        {
            ["summary"] = doc.Summary,
            ["params"] = doc.Params,
            ["returns"] = doc.Returns
        };

        map["calls"] = BuildCalls(method);
        map["line_span"] = LineSpanOf(method.Span);
        map["thrown_exceptions"] = BuildThrownExceptions(method, doc);
        map["is_override"] = symbol?.IsOverride ?? false;
        map["implemented_interface_members"] = symbol?.ExplicitInterfaceImplementations
            .Select(m => m.ToDisplayString())
            .ToList() ?? new List<string>();

        // TestNG @DataProvider linkage has no C# equivalent, but the field is part of the
        // shared schema so consumers never branch on language.
        map["data_provider_name"] = null;
        map["data_provider_source"] = null;

        if (_includeSource)
        {
            // ToString() is the text of Span, which is exactly what line_span selects, so a
            // consumer can reconstruct full_code from the source file and the span alone.
            map["full_code"] = method.ToString();
            var body = (SyntaxNode)method.Body ?? method.ExpressionBody;
            map["body_offset"] = body != null ? body.SpanStart - method.SpanStart : (int?)null;
        }

        return map;
    }

    static Dictionary<string, object> BuildAnnotation(AttributeSyntax attr)
    {
        string qualifiedName = attr.Name.ToString();
        if (_semanticModel?.GetSymbolInfo(attr).Symbol is IMethodSymbol ctor)
        {
            qualifiedName = ctor.ContainingType.ToDisplayString(QualifiedFormat);
        }

        var values = new Dictionary<string, string>();
        if (attr.ArgumentList != null)
        {
            int positional = 0;
            foreach (var arg in attr.ArgumentList.Arguments)
            {
                string key = arg.NameEquals?.Name.Identifier.Text
                             ?? arg.NameColon?.Name.Identifier.Text
                             ?? positional++.ToString();
                values[key] = arg.Expression.ToString().Trim('"');
            }
        }

        return new Dictionary<string, object>
        {
            ["name"] = attr.Name.ToString(),
            ["qualified_name"] = qualifiedName,
            ["values"] = values
        };
    }

    /// <summary>
    /// Invocations in the method body, grouped by call target. The target carries parameter
    /// types so overloads stay distinct nodes in a call graph.
    /// </summary>
    static List<Dictionary<string, object>> BuildCalls(MethodDeclarationSyntax method)
    {
        var grouped = new List<Dictionary<string, object>>();
        var index = new Dictionary<string, Dictionary<string, object>>();

        var bodyNode = (SyntaxNode)method.Body ?? method.ExpressionBody?.Expression;
        if (bodyNode == null) return grouped;

        foreach (var invocation in bodyNode.DescendantNodes().OfType<InvocationExpressionSyntax>())
        {
            string target;
            string returnType = null;
            bool resolved = false;

            if (_semanticModel?.GetSymbolInfo(invocation).Symbol is IMethodSymbol callSymbol)
            {
                string parameters = string.Join(", ",
                    callSymbol.Parameters.Select(p => p.Type.ToDisplayString(QualifiedFormat)));
                target = callSymbol.ContainingType.ToDisplayString(QualifiedFormat)
                         + "." + callSymbol.Name + "(" + parameters + ")";
                returnType = callSymbol.ReturnType.ToDisplayString(QualifiedFormat);
                resolved = true;
            }
            else
            {
                target = invocation.Expression.ToString();
            }

            if (!index.TryGetValue(target, out var entry))
            {
                entry = new Dictionary<string, object>
                {
                    ["target"] = target,
                    ["return_type"] = returnType,
                    ["resolved"] = resolved,
                    ["count"] = 0,
                    ["line_spans"] = new List<Dictionary<string, int>>()
                };
                index[target] = entry;
                grouped.Add(entry);
            }

            entry["count"] = (int)entry["count"] + 1;
            ((List<Dictionary<string, int>>)entry["line_spans"]).Add(LineSpanOf(invocation.Span));
        }

        return grouped;
    }

    /// <summary>
    /// C# has no throws clause, so every entry comes from an XML doc &lt;exception&gt; tag.
    /// The cref is resolved against the method's scope where possible.
    /// </summary>
    static List<Dictionary<string, object>> BuildThrownExceptions(MethodDeclarationSyntax method, XmlDocInfo doc)
    {
        var result = new List<Dictionary<string, object>>();
        foreach (var ex in doc.Exceptions)
        {
            string type = ex.Key;
            bool resolved = false;

            if (_semanticModel != null && !string.IsNullOrWhiteSpace(type))
            {
                var candidates = _semanticModel.LookupNamespacesAndTypes(method.SpanStart, null, type);
                if (candidates.Length == 1 && candidates[0] is ITypeSymbol ts)
                {
                    type = ts.ToDisplayString(QualifiedFormat);
                    resolved = true;
                }
            }

            result.Add(new Dictionary<string, object>
            {
                ["type"] = type,
                ["resolved"] = resolved,
                ["sources"] = new List<string> { "xmldoc" },
                ["description"] = string.IsNullOrWhiteSpace(ex.Value) ? null : ex.Value
            });
        }
        return result;
    }

    static Dictionary<string, int> LineSpanOf(TextSpan span)
    {
        var ls = _tree.GetLineSpan(span);
        return new Dictionary<string, int>
        {
            ["start_line"] = ls.StartLinePosition.Line + 1,
            ["start_column"] = ls.StartLinePosition.Character + 1,
            ["end_line"] = ls.EndLinePosition.Line + 1,
            ["end_column"] = ls.EndLinePosition.Character + 1
        };
    }

    // ---------------------------------------------------------------- xml doc

    class XmlDocInfo
    {
        public string Summary;
        public string Returns;
        public List<Dictionary<string, string>> Params = new();
        public List<KeyValuePair<string, string>> Exceptions = new();
    }

    static XmlDocInfo ExtractXmlDoc(MethodDeclarationSyntax method)
    {
        var info = new XmlDocInfo();

        var xmlCommentString = string.Join("\n", method.GetLeadingTrivia()
            .Where(t => t.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia) ||
                        t.IsKind(SyntaxKind.MultiLineDocumentationCommentTrivia))
            .Select(t => t.ToFullString()));
        if (string.IsNullOrWhiteSpace(xmlCommentString)) return info;

        string cleaned = CleanXmlDoc(xmlCommentString);
        try
        {
            var xdoc = XDocument.Parse("<root>" + cleaned + "</root>");

            // A doc comment with no <summary> tag is just prose; keep it rather than drop it.
            var summary = xdoc.Descendants("summary").FirstOrDefault();
            info.Summary = summary != null
                ? summary.Value.Trim()
                : NullIfBlank(xdoc.Root.Nodes().OfType<System.Xml.Linq.XText>()
                    .Select(t => t.Value.Trim())
                    .FirstOrDefault(t => t.Length > 0));

            info.Returns = xdoc.Descendants("returns").FirstOrDefault()?.Value.Trim();
            info.Params = xdoc.Descendants("param")
                .Select(pe => new Dictionary<string, string>
                {
                    ["name"] = pe.Attribute("name")?.Value ?? "",
                    ["description"] = pe.Value.Trim()
                })
                .ToList();
            info.Exceptions = xdoc.Descendants("exception")
                .Select(ee => new KeyValuePair<string, string>(
                    StripCrefPrefix(ee.Attribute("cref")?.Value ?? ""),
                    ee.Value.Trim()))
                .ToList();
        }
        catch
        {
            // Malformed XML - a stray & or an unclosed tag. There is no raw field to fall back
            // on, so keep the prose with the markup stripped out.
            info.Summary = NullIfBlank(Regex.Replace(cleaned, "<[^>]*>", " ").Replace("&amp;", "&").Trim());
        }

        return info;
    }

    static string StripCrefPrefix(string cref)
    {
        // Roslyn writes crefs as "T:Namespace.Type" once bound; in source they are bare.
        return cref.Length > 2 && cref[1] == ':' ? cref.Substring(2) : cref;
    }

    static string NullIfBlank(string value) => string.IsNullOrWhiteSpace(value) ? null : value;

    static string CleanXmlDoc(string xml)
    {
        var lines = xml.Split('\n')
            .Select(l => l.TrimStart('/', ' ', '\t', '*').TrimEnd('\r'))
            .Where(l => !string.IsNullOrWhiteSpace(l))
            .ToArray();
        return string.Join("\n", lines);
    }
}
