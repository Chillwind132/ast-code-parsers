# regen-examples.ps1
# Regenerates the committed example outputs in examples\.
# Run this after changing a parser's output, then commit the result: the diff
# shows reviewers exactly how the schema changed.
#
# Requires both parsers to be built first:
#   pwsh java\build.ps1 ; pwsh csharp\build.ps1

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

$jar = "java\JavaCodeParser_Full.jar"
$exe = "csharp\win-x64\CSharpCodeParser.exe"

if (!(Test-Path $jar)) { throw "Missing $jar - run pwsh java\build.ps1 first" }
if (!(Test-Path $exe)) { throw "Missing $exe - run pwsh csharp\build.ps1 first" }

# The parsers echo source text back in body_code / full_code / raw, so output
# carries the host's line separator. CI runs on Linux, so normalize to LF here
# or the committed files will not match.
function Save-Normalized($lines, $path) {
    $text = ($lines -join "`n") -replace "`r`n", "`n" -replace '\\r\\n', '\n'
    [IO.File]::WriteAllText((Join-Path $PWD $path), $text.TrimEnd() + "`n")
}

Write-Host "==> examples\OrderService.java.output.json"
Save-Normalized (Get-Content examples\OrderService.java -Raw | java -jar $jar) "examples\OrderService.java.output.json"

Write-Host "==> examples\OrderService.cs.output.json"
Save-Normalized (Get-Content examples\OrderService.cs -Raw | & ".\$exe") "examples\OrderService.cs.output.json"

Write-Host "==> examples\Nesting.java.output.json"
Save-Normalized (Get-Content examples\Nesting.java -Raw | java -jar $jar) "examples\Nesting.java.output.json"

Write-Host "==> examples\Nesting.cs.output.json"
Save-Normalized (Get-Content examples\Nesting.cs -Raw | & ".\$exe") "examples\Nesting.cs.output.json"

Write-Host "==> Done. Review the diff with: git diff examples\" -ForegroundColor Green
