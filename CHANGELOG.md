# Changelog

Versioning applies to the **output schema** rather than the code; see [CONTRIBUTING.md](CONTRIBUTING.md#versioning).

## 2.0.0

Breaking. The output is now one document per file instead of a flat array of methods.

### Added

- `schema_version`, currently `2`, at the document root.
- A `types` array. Each entry carries `name`, `qualified_name`, `namespace`, `kind`, `inherits_from` and `inheritance_hierarchy` once, and owns its `methods`. Nested types get their own entry.
- `resolved` on every type reference — parameters, return types, call targets and thrown exceptions. Where symbol resolution fails, `type` falls back to the name as written and `resolved` is `false`, so silent degradation is now visible.
- `calls[].count` and `calls[].line_spans`, replacing one entry per call site.
- `--file <path>`, recorded in the `file` field for consumers that build node ids.
- `--include-source`, which adds `full_code` and `body_offset`.
- `--pretty`. Output is compact by default.
- C# now populates `imports`, `top_level_comment`, `inheritance_hierarchy` and `thrown_exceptions`, and extracts attribute arguments and qualified attribute names. All four were hardcoded empty.

### Changed

- Call targets carry parameter types in both languages: `Type.method(java.lang.String)`. C# previously emitted `Type.Method`.
- `full_code` is now the exact text that `line_span` selects, in both languages. Java previously emitted a pretty-printed reconstruction that no span could reproduce.
- `documentation.throws` and `thrown_exceptions` are merged into one `thrown_exceptions` list keyed by type, with `sources` recording whether the signature, the doc comment, or both mentioned it.
- `top_level_comment` is now populated. It was always `null` in 1.x, including in the committed examples.
- Java emits type nodes for annotation declarations (`@interface`), which 1.x skipped entirely.

### Removed

Per method: `symbol_type`, `language`, `namespace`, `imported_types`, `top_level_comment`, `inherits_from`, `inheritance_hierarchy` (all hoisted to the file or type), `body_code` (`body_offset` under `--include-source` replaces it), `callee_name` and `parameter_types` from calls (folded into `target`), every `fully_qualified_*` twin (folded into `type` plus `resolved`), `documentation.throws` and `documentation.raw`.

### Migration

| 1.x | 2.0 |
| --- | --- |
| `doc[i].qualified_name` | `doc.types[t].methods[m].qualified_name` |
| `method.namespace` | `type.namespace` |
| `method.parameters[i].fully_qualified_type` | `method.parameters[i].type` (with `resolved`) |
| `method.calls[i].fully_qualified_callee_name` | `method.calls[i].target`, now including parameter types |
| `method.body_code` | `full_code.substring(body_offset)` under `--include-source` |
| `method.documentation.throws` | `method.thrown_exceptions` where `sources` contains the doc source |

Output is roughly 60% smaller: parsing the Java parser's own source went from 5.9x the input size to 2.3x, and a file of 2,000 small methods from 22.8x to 11.2x.

## 1.0.1

Bug fixes only; no schema change.

- **C#**: file-scoped namespaces were not recognised, nested namespaces reported only their first segment, nested types collapsed onto the outermost type, and records lost their type name entirely. Sibling methods in nested types could share one `qualified_name`.
- **Java**: nested types kept only the innermost simple name, dropping the outer type. Both parsers now emit `Outer.Inner`.
- **Java**: JSON is written as UTF-8 rather than the platform charset. Non-ASCII identifiers were corrupted on Windows and CJK was replaced by `?`.
- **Both**: unparseable input now exits 2 with the problem described on stderr, and still writes valid JSON on stdout. Java previously aborted with a stack trace and no output; C# silently returned plausible-looking results.
- **Java**: TestNG `@DataProvider` linkage now works when testng is not on the type solver path, which is the standalone stdin case it was written for.
- **Both**: line endings are normalised, so the same input produces identical output on Windows and Linux.

## 1.0.0

Initial release.
