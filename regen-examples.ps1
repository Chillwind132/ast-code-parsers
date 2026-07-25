# regen-examples.ps1
# Regenerates the committed example outputs in examples\.
# Run this after changing a parser's output, then commit the result: the diff
# shows reviewers exactly how the schema changed.
#
# Requires both parsers to be built first:
#   pwsh java\build.ps1 ; pwsh csharp\build.ps1
#
# The flags here must match the ones in .github\workflows\ci.yml, which diffs
# live output against these files.

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

$jar = "java\JavaCodeParser_Full.jar"
$exe = "csharp\win-x64\CSharpCodeParser.exe"

if (!(Test-Path $jar)) { throw "Missing $jar - run pwsh java\build.ps1 first" }
if (!(Test-Path $exe)) { throw "Missing $exe - run pwsh csharp\build.ps1 first" }

# The parsers echo source text back under --include-source, so output can carry the host's
# line separator. CI runs on Linux, so normalize to LF here or the committed files will not match.
function Save-Normalized($lines, $path) {
    $text = ($lines -join "`n") -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $PWD $path), $text.TrimEnd() + "`n")
}

foreach ($name in @("OrderService", "Nesting")) {
    Write-Host "==> examples\$name.java.output.json"
    Save-Normalized (Get-Content "examples\$name.java" -Raw |
            java -jar $jar --pretty --file "examples/$name.java") "examples\$name.java.output.json"

    Write-Host "==> examples\$name.cs.output.json"
    Save-Normalized (Get-Content "examples\$name.cs" -Raw |
            & ".\$exe" --pretty --file "examples/$name.cs") "examples\$name.cs.output.json"
}

Write-Host "==> Done. Review the diff with: git diff examples\" -ForegroundColor Green
