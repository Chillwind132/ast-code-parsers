# build.ps1
# Publishes a self-contained single-file CSharpCodeParser binary.
# Run in PowerShell:
#   pwsh .\build.ps1
#
# Requirements:
# - .NET SDK 8 or newer on PATH

param(
    [string]$Runtime = "win-x64"
)

$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

Write-Host "==> Publishing CSharpCodeParser for $Runtime"
dotnet publish CSharpCodeParser.csproj -c Release -r $Runtime `
    --self-contained true /p:PublishSingleFile=true /p:DebugType=None -o $Runtime

if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: publish failed." -ForegroundColor Red
    exit 1
}

$exe = if ($Runtime -like "win-*") { "CSharpCodeParser.exe" } else { "CSharpCodeParser" }
Write-Host "==> Built $Runtime\$exe" -ForegroundColor Green
Write-Host "Run it with:"
Write-Host "  Get-Content MyClass.cs -Raw | .\$Runtime\$exe"
