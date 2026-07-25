# AST Code Parsers

[![CI](https://github.com/Chillwind132/ast-code-parsers/actions/workflows/ci.yml/badge.svg)](https://github.com/Chillwind132/ast-code-parsers/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk&logoColor=white)](java/)
[![.NET](https://img.shields.io/badge/.NET-8.0-512BD4?logo=dotnet&logoColor=white)](csharp/)
[![Release](https://img.shields.io/github/v/release/Chillwind132/ast-code-parsers?color=blue)](https://github.com/Chillwind132/ast-code-parsers/releases/latest)

A collection of code parsers that use **native language ASTs** to provide the highest quality of semantic extraction, compared to language-agnostic tools like tree-sitter.

Each parser is a standalone CLI that reads source code on **stdin** and writes a JSON document describing the file, its types and their methods to **stdout**. Both parsers emit the *same schema*, so downstream consumers (RAG indexers, code search, call-graph analysis, documentation generators) can treat Java and C# uniformly.

| Language | Parser backend | Source |
| --- | --- | --- |
| Java | [JavaParser](https://javaparser.org/) + symbol solver | `java/src/JavaCodeParser.java` |
| C# | [Roslyn](https://github.com/dotnet/roslyn) (`Microsoft.CodeAnalysis.CSharp`) | `csharp/CSharpCodeParser.cs` |

---

## Example

Both examples in [`examples/`](examples/) are self-contained compilable files, so you can see symbol resolution actually working rather than degrading to bare identifiers.

### Input

`examples/OrderService.java` — abridged to the method of interest:

```java
package com.example.orders;

import java.math.BigDecimal;

public class OrderService extends BaseService {

    private final PromoRepository promoRepository = new PromoRepository();

    /**
     * Applies a promotional discount to an order.
     *
     * @param order the order to discount
     * @param code the promo code to apply
     * @return the new total after discount
     * @throws InvalidPromoException if the code is expired
     */
    @Override
    @Transactional(readOnly = false)
    public BigDecimal applyDiscount(Order order, String code) throws InvalidPromoException {
        Promo promo = promoRepository.findByCode(code);
        validate(promo);
        return order.getTotal().subtract(promo.getAmount());
    }
}
```

```bash
cat examples/OrderService.java \
  | java -jar java/JavaCodeParser_Full.jar --pretty --file examples/OrderService.java
```

### Output

One document per file: file-level context once, then a type per declaration, then the methods. Showing the `OrderService` type with only `applyDiscount` — the complete document is in [`examples/OrderService.java.output.json`](examples/OrderService.java.output.json).

```json
{
  "schema_version": 2,
  "file": "examples/OrderService.java",
  "language": "java",
  "imports": ["java.math.BigDecimal"],
  "top_level_comment": null,
  "types": [
    {
      "name": "OrderService",
      "qualified_name": "com.example.orders.OrderService",
      "namespace": "com.example.orders",
      "kind": "class",
      "inherits_from": ["BaseService"],
      "inheritance_hierarchy": ["com.example.orders.BaseService", "java.lang.Object"],
      "methods": [
        {
          "name": "applyDiscount",
          "qualified_name": "com.example.orders.OrderService.applyDiscount",
          "modifiers": ["public"],
          "annotations": [
            { "name": "Override", "qualified_name": "java.lang.Override", "values": {} },
            {
              "name": "Transactional",
              "qualified_name": "com.example.orders.Transactional",
              "values": { "readOnly": "false" }
            }
          ],
          "parameters": [
            { "name": "order", "type": "com.example.orders.Order", "resolved": true },
            { "name": "code", "type": "java.lang.String", "resolved": true }
          ],
          "return_type": { "type": "java.math.BigDecimal", "resolved": true },
          "documentation": {
            "summary": "Applies a promotional discount to an order.",
            "params": [
              { "name": "order", "description": "the order to discount" },
              { "name": "code", "description": "the promo code to apply" }
            ],
            "returns": "the new total after discount"
          },
          "calls": [
            {
              "target": "com.example.orders.PromoRepository.findByCode(java.lang.String)",
              "return_type": "com.example.orders.Promo",
              "resolved": true,
              "count": 1,
              "line_spans": [
                { "start_line": 58, "start_column": 23, "end_line": 58, "end_column": 54 }
              ]
            }
          ],
          "line_span": { "start_line": 55, "start_column": 5, "end_line": 61, "end_column": 5 },
          "thrown_exceptions": [
            {
              "type": "com.example.orders.InvalidPromoException",
              "resolved": true,
              "sources": ["signature", "javadoc"],
              "description": "if the code is expired"
            }
          ],
          "is_override": true,
          "implemented_interface_members": [],
          "data_provider_name": null,
          "data_provider_source": null
        }
      ]
    }
  ]
}
```

Note what a syntax-only parser could not have given you: `String` resolved to `java.lang.String`, every call site bound to its declaring type and return type with parameter types included, the `readOnly` annotation argument as a key-value pair, the Javadoc split into `summary` / `params` / `returns`, the `throws` clause and its `@throws` tag merged into one entry that records both sources, and `is_override` confirmed against the base class rather than inferred from the annotation.

### The same method in C#

`examples/OrderService.cs` is the direct equivalent, and the output uses identical field names:

```bash
cat examples/OrderService.cs \
  | csharp/linux-x64/CSharpCodeParser --pretty --file examples/OrderService.cs
```

```json
{
  "name": "ApplyDiscount",
  "qualified_name": "Example.Orders.OrderService.ApplyDiscount",
  "modifiers": ["public", "override"],
  "annotations": [
    {
      "name": "Transactional",
      "qualified_name": "Example.Orders.TransactionalAttribute",
      "values": { "ReadOnly": "false" }
    }
  ],
  "parameters": [
    { "name": "order", "type": "Example.Orders.Order", "resolved": true },
    { "name": "code", "type": "System.String", "resolved": true }
  ],
  "return_type": { "type": "System.Decimal", "resolved": true },
  "calls": [
    {
      "target": "Example.Orders.PromoRepository.FindByCode(System.String)",
      "return_type": "Example.Orders.Promo",
      "resolved": true,
      "count": 1,
      "line_spans": [
        { "start_line": 49, "start_column": 27, "end_line": 49, "end_column": 60 }
      ]
    }
  ],
  "is_override": true
}
```

Full document: [`examples/OrderService.cs.output.json`](examples/OrderService.cs.output.json).

[`examples/Nesting.java`](examples/Nesting.java) and [`examples/Nesting.cs`](examples/Nesting.cs) cover the naming cases that are easy to get wrong: nested types, enums, records, and C# file-scoped namespaces.

---

## Output schema

The document describes one file.

| Field | Description |
| --- | --- |
| `schema_version` | `2`. Incremented when a field is renamed, removed, or changes meaning |
| `file` | Whatever was passed to `--file`, else `null`. Consumers use it to build node ids |
| `language` | `java` or `csharp` |
| `imports` | Import / using declarations |
| `top_level_comment` | File header comment, if any |
| `types` | One entry per type declaration, including nested ones |

Each entry in `types`:

| Field | Description |
| --- | --- |
| `name`, `qualified_name`, `namespace` | Identity. Nested types are named `Outer.Inner` |
| `kind` | `class`, `interface`, `struct`, `enum`, `record`, or `annotation` |
| `inherits_from` | Supertypes as written in the declaration |
| `inheritance_hierarchy` | Resolved ancestors, up to `java.lang.Object` / `System.Object` |
| `methods` | Method declarations of this type only; a nested type's methods live under its own entry |

Each entry in `methods`:

| Field | Description |
| --- | --- |
| `name`, `qualified_name` | Identity |
| `modifiers` | `public`, `static`, `async`, `final`, ... |
| `annotations` | Annotations / attributes with qualified names and key-value arguments |
| `parameters` | Name, qualified type, and `resolved` per parameter |
| `return_type` | Qualified type and `resolved` |
| `documentation` | `summary`, `params`, `returns` |
| `calls` | Invocations grouped by `target`, with `return_type`, `resolved`, `count`, and a `line_spans` entry per call site |
| `line_span` | Start and end line/column of the method |
| `thrown_exceptions` | Merged from the `throws` clause and the doc comment, with `sources` recording which mentioned it |
| `implemented_interface_members`, `is_override` | Compiler-resolved override information |
| `data_provider_name`, `data_provider_source` | TestNG `@DataProvider` linkage for `@Test` methods (Java) |
| `full_code`, `body_offset` | Only under `--include-source`. See below |

Fields that a given language cannot express are present but `null` or empty, so consumers never need to branch on language.

### Resolution is explicit

Every type reference carries a `resolved` boolean. When symbol resolution succeeds, `type` is fully qualified; when it fails, `type` falls back to the name as written and `resolved` is `false`. A consumer can therefore tell a genuine `java.lang.String` from an unresolvable `Foo`, instead of guessing from whether the string contains a dot.

Call targets always carry parameter types — `Type.method(java.lang.String)` — so overloads remain distinct nodes in a call graph.

### Reconstructing source

`full_code` is omitted by default. Under `--include-source` it holds the exact text that `line_span` selects, and `body_offset` is the index into `full_code` where the body starts. Because the span and the text agree by construction, a consumer that has the original file can skip `full_code` entirely and slice the span itself.

---

## Build

Neither build requires you to install dependencies by hand; both scripts resolve everything on first run.

### Java

Requires a JDK 17 or newer on `PATH`. The script downloads its four dependencies from Maven Central, compiles, and packages a fat JAR.

```bash
cd java
./build.sh          # Linux / macOS
pwsh ./build.ps1    # Windows
```

Produces `java/JavaCodeParser_Full.jar`, which runs standalone with no classpath setup.

### C#

Requires the .NET SDK 8 or newer. `build.sh` falls back to a Docker build automatically if no suitable SDK is found.

```bash
cd csharp
./build.sh          # Linux / macOS -> linux-x64/CSharpCodeParser
pwsh ./build.ps1    # Windows       -> win-x64/CSharpCodeParser.exe
```

Both produce a self-contained single-file binary with no runtime prerequisite on the target machine. Pass a runtime identifier to cross-target, e.g. `pwsh ./build.ps1 -Runtime linux-x64`.

---

## Usage

Each parser reads source code on stdin, so pipe a file into it and the JSON comes back on stdout.

```bash
# Java
cat MyClass.java | java -jar java/JavaCodeParser_Full.jar

# C#
cat MyClass.cs | csharp/linux-x64/CSharpCodeParser
```

On Windows, `Get-Content` replaces `cat`:

```powershell
Get-Content MyClass.java -Raw | java -jar java\JavaCodeParser_Full.jar
Get-Content MyClass.cs -Raw | .\csharp\win-x64\CSharpCodeParser.exe
```

To save the result instead of printing it, add `> methods.json` to the end of any of the above.

Both parsers read a single compilation unit per run, so batching across a repository is left to the caller — spawn one process per file, or keep a worker pool if throughput matters.

### Options

| Flag | Effect |
| --- | --- |
| `--file <path>` | Record `<path>` in the `file` field. Nothing is read from disk; this is the identity the caller wants in the output |
| `--include-source` | Also emit `full_code` and `body_offset` per method |
| `--pretty` | Indent the JSON. Off by default — indentation is most of the payload on a real file |

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Parsed cleanly. |
| `2` | The input has syntax errors, described on stderr. Java cannot recover and emits a document with no types; C# error-recovers and still emits what it found, which may be incomplete. |

stdout is always valid JSON, so a batch indexer can consume the output and use the exit code to decide whether to trust it.

---

## License

MIT — see [LICENSE](LICENSE).
