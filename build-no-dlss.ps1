$ErrorActionPreference = "Stop"

$jdk = "C:\Program Files\Java\jdk-25.0.3"
if (-not (Test-Path "$jdk\bin\java.exe")) {
    throw "JDK 25 was not found at $jdk"
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:VULKAN_SDK\Bin;$env:Path"

foreach ($tool in @("java", "javac", "slangc", "glslangValidator", "spirv-val")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required build tool '$tool' is not on PATH. Install/repair the Vulkan SDK or JDK 25."
    }
}

Write-Host "Using Java:" -ForegroundColor Cyan
java --version
Write-Host "Using Gradle JVM:" -ForegroundColor Cyan
.\gradlew.bat --stop
.\gradlew.bat --version

.\gradlew.bat clean assemble -PnoNgx

$jar = Get-ChildItem .\build\libs\caustica-*.jar |
    Where-Object Name -NotLike "*-sources.jar" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "Gradle finished but no Caustica JAR was found in build\libs"
}

Write-Host "Build complete: $($jar.FullName)" -ForegroundColor Green
Get-FileHash $jar.FullName -Algorithm SHA256
