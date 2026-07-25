# Contributing

## Keep the two schemas in sync

The Java and C# parsers deliberately emit the **same field names**, so consumers never have to branch on language. A field added to one parser should be added to the other in the same change. If a language genuinely cannot express a field, emit it as `null` or an empty list rather than omitting it.

## Regenerate the examples

CI verifies that live parser output still matches the committed files in `examples/`. Any change to the output will therefore fail CI until you regenerate them:

```bash
./regen-examples.sh          # Linux / macOS
pwsh ./regen-examples.ps1    # Windows
```

Commit the regenerated files alongside your change. This is intentional: the diff is the clearest description of how the schema changed, and it prevents accidental output regressions from going unnoticed.

The flags in `regen-examples.*` must match the ones CI passes, or the diff will fail for reasons that have nothing to do with your change. Both currently use `--pretty --file <path>`.

Note that `full_code` echoes source text back under `--include-source`, so output can carry the host's line separator. The committed files use LF because CI runs on Linux; `regen-examples.ps1` normalizes this for you on Windows.

## Two invariants worth protecting

- **`line_span` selects exactly `full_code`.** A consumer must be able to reconstruct the source of a method from the file and the span alone. Slice the original text; do not print the AST back out, or the two drift apart.
- **`resolved` means resolved.** Every type reference falls back to the name as written when symbol resolution fails. That fallback is the only place `resolved: false` should be set, and it must be set there.

## Versioning

Releases follow semantic versioning, applied to the **output schema** rather than the code:

- **Patch** — bug fixes that do not change output for valid input.
- **Minor** — new fields added. Existing consumers keep working.
- **Major** — a field renamed, removed, or given a different type or meaning.

## Before opening a pull request

```bash
cd java   && ./build.sh && cd ..
cd csharp && ./build.sh && cd ..
./regen-examples.sh
git diff examples/
```

Both parsers should build with no warnings, and any diff under `examples/` should be one you intended.
