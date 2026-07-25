# build.ps1
# A PowerShell script to create a fat JAR from compiled classes and dependencies
# Run this in PowerShell: 
#   pwsh .\build.ps1
#
# Requirements:
# - JDK on PATH (javac, jar)
# - Internet access on first run, to download dependency JARs into lib/
# - Update $MainClass below if needed

param(
    [string]$MainClass = "JavaCodeParser",  # Fully qualified main class if needed, e.g. com.example.JavaCodeParser
    [string]$OutputJar = "JavaCodeParser_Full.jar",
    [string]$ManifestFile = "MANIFEST.MF"
)

# Set execution directory to script location
$scriptPath = $MyInvocation.MyCommand.Path
$scriptDir = Split-Path -Parent $scriptPath
Set-Location $scriptDir

Write-Host "==> Starting build process..."

# Check if jar is available
$jarVersion = & jar --help 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: 'jar' command not found or not accessible. Ensure JDK bin is on PATH." -ForegroundColor Red
    exit 1
}

# Resolve dependencies into lib/
$maven = "https://repo1.maven.org/maven2"
$deps = @(
    "com/github/javaparser/javaparser-core/3.26.2/javaparser-core-3.26.2.jar",
    "com/github/javaparser/javaparser-symbol-solver-core/3.26.2/javaparser-symbol-solver-core-3.26.2.jar",
    "com/google/code/gson/gson/2.11.0/gson-2.11.0.jar",
    "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"
)
New-Item -ItemType Directory -Force -Path lib | Out-Null
foreach ($dep in $deps) {
    $jar = "lib\$(Split-Path -Leaf $dep)"
    if (!(Test-Path $jar)) {
        Write-Host "Downloading $(Split-Path -Leaf $dep)..."
        Invoke-WebRequest -Uri "$maven/$dep" -OutFile $jar
    }
}

# Compile sources
Write-Host "Compiling src/ to bin/..."
Remove-Item -Recurse -Force bin -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path bin | Out-Null
javac -encoding UTF-8 -d bin -cp "lib/*" src\JavaCodeParser.java
if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: compilation failed." -ForegroundColor Red
    exit 1
}

# Remove old build_tmp if exists
if (Test-Path build_tmp) {
    Write-Host "Removing old build_tmp directory..."
    Remove-Item -Recurse -Force build_tmp
}

# Recreate build_tmp
Write-Host "Creating build_tmp directory..."
New-Item -ItemType Directory -Path build_tmp | Out-Null

# Check if bin directory exists
if (!(Test-Path bin)) {
    Write-Host "Error: 'bin' directory not found. Ensure you have compiled .class files in 'bin/'." -ForegroundColor Red
    exit 1
}

# Copy compiled classes to build_tmp
Write-Host "Copying compiled classes from bin/ to build_tmp/..."
Copy-Item -Recurse -Path bin\* build_tmp\

# Check for lib directory
if (!(Test-Path lib)) {
    Write-Host "No 'lib' directory found. Assuming no dependencies." -ForegroundColor Yellow
} else {
    # Extract each dependency JAR into build_tmp
    $jarFiles = Get-ChildItem lib\*.jar -ErrorAction SilentlyContinue
    if ($jarFiles -and $jarFiles.Count -gt 0) {
        foreach ($jarFile in $jarFiles) {
            Write-Host "Processing JAR file: $($jarFile.FullName)"
            Push-Location build_tmp
            # Extract the jar here
            jar xf $jarFile
            Pop-Location
            Write-Host "Extraction complete for: $($jarFile.FullName)"
        }
    } else {
        Write-Host "No JAR files found in lib/, continuing without dependencies."
    }
}

# Ensure MANIFEST.MF exists and has the correct Main-Class
if (!(Test-Path $ManifestFile)) {
    Write-Host "MANIFEST.MF not found, creating one..."
    "Main-Class: $MainClass`r`n" | Out-File -Encoding ASCII $ManifestFile
} else {
    # Validate the Main-Class line (optional)
    $manifestContent = Get-Content $ManifestFile
    if ($manifestContent -notmatch 'Main-Class:') {
        Write-Host "MANIFEST.MF found but no Main-Class defined. Adding it..."
        Add-Content -Encoding ASCII $ManifestFile "Main-Class: $MainClass`r`n"
    } else {
        Write-Host "MANIFEST.MF found, ensuring main class is $MainClass..."
        # If you must enforce the main class, uncomment below lines:
        # $newContent = $manifestContent -replace "(?m)^Main-Class:.*", "Main-Class: $MainClass"
        # $newContent | Out-File -Encoding ASCII $ManifestFile
    }
}

# Create the final fat JAR
Write-Host "Creating $OutputJar ..."
# Note: The '-C build_tmp .' means: change directory to build_tmp and include all files
jar cvfm $OutputJar $ManifestFile -C build_tmp .

if ($LASTEXITCODE -ne 0) {
    Write-Host "Error: Failed to create $OutputJar" -ForegroundColor Red
    exit 1
}

Write-Host "$OutputJar created successfully." -ForegroundColor Green

Write-Host "Build complete. Parse a file with:"
Write-Host "  java -jar $OutputJar < ..\examples\OrderService.java"
