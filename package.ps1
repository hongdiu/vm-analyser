# =====================================================================
# package.ps1 - 一键打出可运行的分发包（Windows PowerShell 5+）
#
# 产物：
#   dist\jvm-heap-analyzer-<version>\            便携目录（jar + 启动脚本 + 说明）
#   dist\jvm-heap-analyzer-<version>.zip         可直接拷到服务器解压使用
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\package.ps1
#   powershell -ExecutionPolicy Bypass -File .\package.ps1 -SkipTests
#   powershell -ExecutionPolicy Bypass -File .\package.ps1 -Clean -JdkHome "D:\installed\jdk-17.0.1"
#
# 说明：
#   - 自动定位 JDK 17（JAVA_HOME / D:\installed\jdk-17* / Program Files\Java\jdk-17* 等）
#   - 默认先跑全量测试，再执行 shadowJar 打 fat-jar（MAT/Equinox/FreeMarker 全部内嵌）
#   - 分发包内 analyze.bat / analyze.sh 会自动按转储大小推算 -Xmx，并原样透传 --app-prefix
# =====================================================================
param(
    [string]$JdkHome = "",
    [switch]$SkipTests,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Set-Location $root

function Test-Jdk17 {
    param([string]$JdkPath)
    if ([string]::IsNullOrWhiteSpace($JdkPath)) { return $false }
    $javac = Join-Path $JdkPath "bin\javac.exe"
    if (-not (Test-Path $javac)) { return $false }
    $release = Join-Path $JdkPath "release"
    if (Test-Path $release) {
        $text = Get-Content -LiteralPath $release -Raw
        return ($text -match 'JAVA_VERSION="17')
    }
    return $false
}

function Find-Jdk17 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $patterns = @(
        "D:\installed\jdk-17*",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Microsoft\jdk-17*",
        "D:\Program Files\Java\jdk-17*",
        (Join-Path $env:USERPROFILE ".jdks\*17*")
    )
    foreach ($p in $patterns) {
        $dirs = Get-ChildItem -Path $p -Directory -ErrorAction SilentlyContinue
        foreach ($d in $dirs) { $candidates += $d.FullName }
    }
    foreach ($c in $candidates) {
        if (Test-Jdk17 $c) { return $c }
    }
    return ""
}

# ---------- 1. 定位 JDK 17 ----------
if ($JdkHome -ne "") {
    if (-not (Test-Jdk17 $JdkHome)) {
        Write-Host "[package] 指定目录不是 JDK 17: $JdkHome" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $JdkHome
} elseif (-not (Test-Jdk17 $env:JAVA_HOME)) {
    $found = Find-Jdk17
    if ($found -eq "") {
        Write-Host "[package] 未找到 JDK 17。请先安装 JDK 17，或显式传入 -JdkHome。" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $found
}
Write-Host "[package] JAVA_HOME = $env:JAVA_HOME"

# ---------- 2. 构建 ----------
if ($Clean) {
    Write-Host "[package] gradlew clean ..."
    & .\gradlew.bat clean --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Host "[package] clean 失败" -ForegroundColor Red; exit 1 }
}

if (-not $SkipTests) {
    Write-Host "[package] gradlew test ..."
    & .\gradlew.bat test --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Host "[package] 测试未通过，已中止打包" -ForegroundColor Red; exit 1 }
}

Write-Host "[package] gradlew shadowJar ..."
& .\gradlew.bat shadowJar --console=plain
if ($LASTEXITCODE -ne 0) { Write-Host "[package] shadowJar 失败" -ForegroundColor Red; exit 1 }

# ---------- 3. 组装 dist ----------
$jars = @(Get-ChildItem -Path "build\libs\jvm-heap-analyzer-*.jar" -File |
        Sort-Object LastWriteTime -Descending)
if ($jars.Count -eq 0) {
    Write-Host "[package] 未找到 build\libs\jvm-heap-analyzer-*.jar" -ForegroundColor Red
    exit 1
}
$jar = $jars[0]

$version = "1.0.0"
$versionLine = Select-String -Path "build.gradle" -Pattern "^version\s*=\s*'(.+)'" | Select-Object -First 1
if ($versionLine) { $version = $versionLine.Matches[0].Groups[1].Value }

$distRoot = Join-Path $root "dist"
$distDir = Join-Path $distRoot "jvm-heap-analyzer-$version"
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Path $distDir -Force | Out-Null

Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $distDir $jar.Name)
Copy-Item -LiteralPath (Join-Path $root "analyze.bat") -Destination $distDir
Copy-Item -LiteralPath (Join-Path $root "analyze.sh") -Destination $distDir

$usageLines = @(
    "jvm-heap-analyzer $version 便携包",
    "=====================================================",
    "",
    "运行环境：JDK 17+（无需 Gradle / Maven / 联网）",
    "",
    "Windows",
    "-------",
    "  analyze.bat -i D:\dumps\oom.hprof -o report --app-prefix com.mycorp",
    "  后台运行：",
    "  analyze.bat -b -i D:\dumps\oom.hprof -o report --app-prefix com.mycorp",
    "  查看进度：type analyze.log",
    "",
    "Linux / macOS",
    "-------------",
    "  chmod +x analyze.sh",
    "  ./analyze.sh -i /data/oom.hprof -o report --app-prefix com.mycorp",
    "  后台运行：",
    "  ./analyze.sh -b -i /data/oom.hprof -o report --app-prefix com.mycorp",
    "  查看进度：tail -f analyze.log",
    "",
    "参数要点",
    "--------",
    "  -i / --input          堆转储（.hprof），必填；零参数时自动取脚本同目录最新的 hprof",
    "  -o / --output         报告输出目录，必填（缺省当前目录）",
    "  --app-prefix          业务类包前缀（逗号分隔），用于从线程栈里挑出业务帧，",
    "                        例如：com.mycorp,cn.example.order",
    "  --top                 每类结果最多条数（默认 20；大堆自动 10）",
    "  --min-retained-mb     泄漏嫌疑最小 retained（MB，默认 5；大堆自动 20）",
    "  --baseline            可选，第二份更早的 dump，输出类实例数/浅堆增长证据",
    "  --verbose             输出 MAT 解析细节",
    "",
    "堆内存",
    "------",
    "  启动脚本按 转储GB × 2.5 + 1 自动推算 -Xmx（下限 4g，上限物理内存 60%）。",
    "  如仍出现 GC 风暴：XMX_OVERRIDE=16g（Windows: set XMX_OVERRIDE=16g）。",
    "",
    "报告",
    "----",
    "  report/index.html   诊断结论（代码线索 + 证据 + 规模 + 验证 + 可展开原始堆栈）",
    "  report/summary.json 结构化结果（含 codeLocations / accumulated / confidence）",
    "",
    "注意",
    "----",
    "  本包为 fat-jar，不需要 libs/mat 目录；不要把包内 jar 与旧版本混用。"
)
$usage = [string]::Join([Environment]::NewLine, $usageLines)
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $distDir "USAGE.txt"), $usage, $utf8)

# ---------- 4. 压缩 ----------
$zipPath = Join-Path $distRoot "jvm-heap-analyzer-$version.zip"
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
Compress-Archive -Path $distDir -DestinationPath $zipPath -CompressionLevel Optimal

$zipMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host ""
Write-Host "[package] 完成：" -ForegroundColor Green
Write-Host "  目录 : $distDir"
Write-Host "  压缩 : $zipPath ($zipMb MB)"
Write-Host ""
Write-Host "使用示例："
Write-Host "  $distDir\analyze.bat -i D:\dumps\oom.hprof -o report --app-prefix com.mycorp"
Write-Host "  $distDir\analyze.bat -b -i D:\dumps\oom.hprof -o report --app-prefix com.mycorp"
