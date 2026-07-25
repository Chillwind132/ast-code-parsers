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

Note that `body_code`, `full_code`, and `documentation.raw` echo source text back, so output carries the host's line separator. The committed files use LF because CI runs on Linux; `regen-examples.ps1` normalizes this for you on Windows.

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
