# AST Code Parsers

[![CI](https://github.com/Chillwind132/ast-code-parsers/actions/workflows/ci.yml/badge.svg)](https://github.com/Chillwind132/ast-code-parsers/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk&logoColor=white)](java/)
[![.NET](https://img.shields.io/badge/.NET-8.0-512BD4?logo=dotnet&logoColor=white)](csharp/)
[![Release](https://img.shields.io/github/v/release/Chillwind132/ast-code-parsers?color=blue)](https://github.com/Chillwind132/ast-code-parsers/releases/latest)

A collection of code parsers that use **native language ASTs** to provide the highest quality of semantic extraction, compared to language-agnostic tools like tree-sitter.

Each parser is a standalone CLI that reads source code on **stdin** and writes a JSON array of method-level metadata to **stdout**. Both parsers emit the *same schema*, so downstream consumers (RAG indexers, code search, call-graph analysis, documentation generators) can treat Java and C# uniformly.

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
cat examples/OrderService.java | java -jar java/JavaCodeParser_Full.jar
```

### Output

The parser emits one object per method. Showing the `applyDiscount` entry, with `documentation`, `body_code` and `full_code` elided for length — the complete document is in [`examples/OrderService.java.output.json`](examples/OrderService.java.output.json).

```json
{
  "symbol_type": "method",
  "name": "applyDiscount",
  "qualified_name": "com.example.orders.OrderService.applyDiscount",
  "namespace": "com.example.orders",
  "modifiers": [
    "public"
  ],
  "annotations": [
    {
      "name": "Override",
      "fully_qualified_name": "java.lang.Override",
      "values": {}
    },
    {
      "name": "Transactional",
      "fully_qualified_name": "com.example.orders.Transactional",
      "values": {
        "readOnly": "false"
      }
    }
  ],
  "parameters": [
    {
      "name": "order",
      "type": "Order",
      "fully_qualified_type": "com.example.orders.Order"
    },
    {
      "name": "code",
      "type": "String",
      "fully_qualified_type": "java.lang.String"
    }
  ],
  "return_type": {
    "type": "BigDecimal",
    "fully_qualified_type": "java.math.BigDecimal"
  },
  "documentation": {
    "summary": "Applies a promotional discount to an order.",
    "params": [
      {
        "name": "order",
        "description": "the order to discount"
      },
      {
        "name": "code",
        "description": "the promo code to apply"
      }
    ],
    "returns": "the new total after discount",
    "throws": [
      {
        "exception_type": "InvalidPromoException",
        "description": "if the code is expired"
      }
    ]
  },
  "calls": [
    {
      "callee_name": "findByCode",
      "fully_qualified_callee_name": "com.example.orders.PromoRepository.findByCode(java.lang.String)",
      "return_type": "com.example.orders.Promo",
      "parameter_types": [
        "java.lang.String"
      ],
      "line_span": {
        "start_line": 58,
        "start_column": 23,
        "end_line": 58,
        "end_column": 54
      }
    },
    {
      "callee_name": "subtract",
      "fully_qualified_callee_name": "java.math.BigDecimal.subtract(java.math.BigDecimal)",
      "return_type": "java.math.BigDecimal",
      "parameter_types": [
        "java.math.BigDecimal"
      ],
      "line_span": {
        "start_line": 60,
        "start_column": 16,
        "end_line": 60,
        "end_column": 59
      }
    }
  ],
  "line_span": {
    "start_line": 55,
    "start_column": 5,
    "end_line": 61,
    "end_column": 5
  },
  "inherits_from": [
    "BaseService"
  ],
  "inheritance_hierarchy": [
    "com.example.orders.BaseService",
    "java.lang.Object"
  ],
  "thrown_exceptions": [
    {
      "exception_type": "InvalidPromoException",
      "fully_qualified_exception_type": "com.example.orders.InvalidPromoException"
    }
  ],
  "is_override": true,
  "imported_types": [
    "java.math.BigDecimal"
  ],
  "language": "java"
}
```

Note what a syntax-only parser could not have given you: `String` resolved to `java.lang.String`, every call site bound to its declaring type and return type, the `readOnly` annotation argument as a key-value pair, the Javadoc split into `summary` / `params` / `returns` / `throws`, and `is_override` confirmed against the base class rather than inferred from the annotation.

### The same method in C#

`examples/OrderService.cs` is the direct equivalent, and the output uses identical field names:

```bash
cat examples/OrderService.cs | csharp/linux-x64/CSharpCodeParser
```

```json
{
  "symbol_type": "method",
  "name": "ApplyDiscount",
  "qualified_name": "Example.Orders.OrderService.ApplyDiscount",
  "namespace": "Example.Orders",
  "modifiers": [
    "public",
    "override"
  ],
  "parameters": [
    {
      "name": "order",
      "type": "Order",
      "fully_qualified_type": "Example.Orders.Order"
    },
    {
      "name": "code",
      "type": "string",
      "fully_qualified_type": "System.String"
    }
  ],
  "return_type": {
    "type": "decimal",
    "fully_qualified_type": "System.Decimal"
  },
  "calls": [
    {
      "callee_name": "FindByCode",
      "fully_qualified_callee_name": "Example.Orders.PromoRepository.FindByCode",
      "return_type": "Example.Orders.Promo",
      "parameter_types": [
        "System.String"
      ],
      "line_span": {
        "start_line": 49,
        "start_column": 27,
        "end_line": 49,
        "end_column": 60
      }
    }
  ],
  "is_override": true,
  "language": "csharp"
}
```

Full document: [`examples/OrderService.cs.output.json`](examples/OrderService.cs.output.json).

---

## Output schema

Every element of the output array describes one method.

| Field | Description |
| --- | --- |
| `symbol_type` | Always `method` |
| `name`, `qualified_name`, `namespace` | Identity, fully qualified where resolvable |
| `modifiers` | `public`, `static`, `async`, `final`, ... |
| `annotations` | Annotations / attributes with qualified names and key-value arguments |
| `parameters` | Name, declared type, and fully qualified type per parameter |
| `return_type` | Declared and fully qualified return type |
| `documentation` | `summary`, `params`, `returns`, `throws`, `raw` |
| `body_code`, `full_code` | Body-only snippet and the full method text including doc comment |
| `calls` | Invocations in the body, with resolved callee, return type, parameter types, and line/column span |
| `line_span` | Start and end line/column of the method |
| `inherits_from`, `inheritance_hierarchy` | Declared supertypes, and the resolved chain to `java.lang.Object` |
| `implemented_interface_members`, `is_override` | Compiler-resolved override information |
| `thrown_exceptions` | Declared `throws` clause (Java) |
| `imported_types`, `top_level_comment` | File-level context |
| `data_provider_name`, `data_provider_source` | TestNG `@DataProvider` linkage for `@Test` methods (Java) |
| `language` | `java` or `csharp` |

Fields that a given language cannot express are present but empty, so consumers never need to branch on language.

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

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Parsed cleanly. |
| `2` | The input has syntax errors, described on stderr. Java cannot recover and prints `[]`; C# error-recovers and still prints what it found, which may be incomplete. |

stdout is always valid JSON, so a batch indexer can consume the output and use the exit code to decide whether to trust it.

---

## License

MIT — see [LICENSE](LICENSE).
