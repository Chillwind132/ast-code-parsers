# AST Code Parsers

A collection of code parsers that use **native language ASTs** to provide the highest quality of semantic extraction, compared to language-agnostic tools like tree-sitter.

Each parser is a standalone CLI that reads source code on **stdin** and writes a JSON array of method-level metadata to **stdout**. Both parsers emit the *same schema*, so downstream consumers (RAG indexers, code search, call-graph analysis, documentation generators) can treat Java and C# uniformly.

| Language | Parser backend | Entry point |
| --- | --- | --- |
| Java | [JavaParser](https://javaparser.org/) + symbol solver | `java/src/JavaCodeParser.java` |
| C# | [Roslyn](https://github.com/dotnet/roslyn) (`Microsoft.CodeAnalysis.CSharp`) | `csharp/CSharpCodeParser.cs` |

---

## Why native ASTs instead of tree-sitter

tree-sitter is excellent at what it was built for: fast, error-tolerant, incremental *syntax* trees for editors. But it stops at syntax. These parsers use each language's own compiler front-end, which gives you a semantic model on top of the tree.

- **Type resolution** — `String` becomes `java.lang.String`; a parameter's declared type and its fully qualified type are both reported. tree-sitter only sees the token.
- **Symbol binding** — call sites resolve to the declaring type, so `subtract(...)` can be attributed to its owner rather than left as a bare identifier.
- **Override and interface awareness** — `is_override` and `implemented_interface_members` come from the compiler's symbol table, not from guessing at the `@Override` annotation.
- **Structured documentation** — Javadoc and XML doc comments are parsed into `summary`, `params`, `returns` and `throws` rather than returned as one comment blob.
- **Correct language semantics for free** — generics, records, `var`, expression-bodied members and modern syntax levels are handled by the real grammar, which is always current with the language.

The trade-off is honest: these parsers are heavier and slower than tree-sitter, and they are per-language rather than universal. Use tree-sitter when you need speed over a hundred languages; use these when extraction quality is what matters.

---

## Visual example

### Input

Piped to stdin (`examples/OrderService.java`):

```java
package com.example.orders;

import java.util.List;

public class OrderService extends BaseService implements Auditable {

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

Abridged below; the full 139-line document is in [`examples/OrderService.output.json`](examples/OrderService.output.json).

```json
[
  {
    "symbol_type": "method",
    "name": "applyDiscount",
    "qualified_name": "com.example.orders.OrderService.applyDiscount",
    "namespace": "com.example.orders",
    "modifiers": ["public"],
    "annotations": [
      { "name": "Override", "fully_qualified_name": "java.lang.Override", "values": {} },
      { "name": "Transactional", "fully_qualified_name": "Transactional", "values": { "readOnly": "false" } }
    ],
    "parameters": [
      { "name": "order", "type": "Order", "fully_qualified_type": "Order" },
      { "name": "code", "type": "String", "fully_qualified_type": "java.lang.String" }
    ],
    "return_type": { "type": "BigDecimal", "fully_qualified_type": "BigDecimal" },
    "documentation": {
      "summary": "Applies a promotional discount to an order.",
      "params": [
        { "name": "order", "description": "the order to discount" },
        { "name": "code", "description": "the promo code to apply" }
      ],
      "returns": "the new total after discount",
      "throws": [
        { "exception_type": "InvalidPromoException", "description": "if the code is expired" }
      ]
    },
    "calls": [
      { "callee_name": "findByCode", "line_span": { "start_line": 18, "start_column": 23, "end_line": 18, "end_column": 54 } },
      { "callee_name": "validate",   "line_span": { "start_line": 19, "start_column": 9,  "end_line": 19, "end_column": 23 } },
      { "callee_name": "subtract",   "line_span": { "start_line": 20, "start_column": 16, "end_line": 20, "end_column": 59 } },
      { "callee_name": "getTotal",   "line_span": { "start_line": 20, "start_column": 16, "end_line": 20, "end_column": 31 } },
      { "callee_name": "getAmount",  "line_span": { "start_line": 20, "start_column": 42, "end_line": 20, "end_column": 58 } }
    ],
    "line_span": { "start_line": 15, "start_column": 5, "end_line": 21, "end_column": 5 },
    "inherits_from": ["BaseService", "Auditable"],
    "thrown_exceptions": [
      { "exception_type": "InvalidPromoException", "fully_qualified_exception_type": "InvalidPromoException" }
    ],
    "is_override": false,
    "imported_types": ["java.util.List"],
    "body_code": "{ ... }",
    "full_code": "/** ... */ @Override @Transactional(readOnly = false) public BigDecimal applyDiscount(...) { ... }",
    "language": "java"
  }
]
```

Note what a syntax-only parser could not have given you: `java.lang.String` as the resolved parameter type, the `readOnly` annotation argument as a key-value pair, the Javadoc split into `summary` / `params` / `returns` / `throws`, and every call site with its exact column span.

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
| `calls` | Invocations in the body, with callee name, types, and line/column span |
| `line_span` | Start and end line/column of the method |
| `inherits_from`, `inheritance_hierarchy` | Base classes and implemented interfaces of the containing type |
| `implemented_interface_members`, `is_override` | Compiler-resolved override information |
| `thrown_exceptions` | Declared `throws` clause (Java) |
| `imported_types`, `top_level_comment` | File-level context |
| `data_provider_name`, `data_provider_source` | TestNG `@DataProvider` linkage for `@Test` methods (Java) |
| `language` | `java` or `csharp` |

Fields that a given language cannot express are present but empty, so consumers never need to branch on language.

---

## Build

### Java

Requires a JDK (17+) on `PATH`. The build script downloads its four dependencies from Maven Central on first run.

```bash
cd java
./build.sh          # Linux / macOS
pwsh ./build.ps1    # Windows
```

Produces a self-contained fat JAR, `java/JavaCodeParser_Full.jar`.

### C#

Requires the .NET 9 SDK, or Docker as a fallback (the script detects this automatically).

```bash
cd csharp
./build.sh
```

Produces a self-contained single-file binary at `csharp/linux-x64/CSharpCodeParser`. For Windows, swap the runtime identifier:

```bash
dotnet publish -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true
```

---

## Usage

```bash
# Java
cat MyClass.java | java -jar java/JavaCodeParser_Full.jar > methods.json

# C#
cat MyClass.cs | ./csharp/linux-x64/CSharpCodeParser > methods.json
```

Both read a single compilation unit from stdin, so batching across a repository is left to the caller — spawn one process per file, or keep a worker pool if throughput matters.

---

## Limitations

- **One file at a time.** Neither parser walks a directory tree or resolves types across files it has not been given. Cross-file resolution falls back to the simple name.
- **Methods only.** Fields, properties, and constructors are not currently emitted.
- **C# semantic model is minimal.** The Roslyn compilation is created with only `System.Private.CoreLib` referenced, so resolution of third-party types degrades to the declared syntax. Java's `ReflectionTypeSolver` covers the JDK equivalently.
- **Process startup dominates on small files.** JVM and self-contained-binary startup is tens to hundreds of milliseconds per invocation.

---

## License

MIT — see [LICENSE](LICENSE).
