# AutoBridge 便捷构建/运行脚本
#
#   用法：
#     .\dev.ps1 build        构建【用户版】jar（默认关 HUD、无诊断日志）
#     .\dev.ps1 dev          构建【开发版】jar（HUD 默认开、输出全部诊断日志）
#     .\dev.ps1 runClient    启动带模组的客户端（自动用开发版，否则看不到排查信息）
#     .\dev.ps1 clean        清理
#
# 两个版本的源码是同一份，只差编译期常量 Edition.DEV 与产物名。
#
# 本机没有全局 JAVA_HOME，所以在脚本里直接指定 JDK 21。

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Task = @("build")
)

$ErrorActionPreference = "Stop"

# 「dev」是便捷别名，等价于 build -Pedition=dev
if ($Task.Length -ge 1 -and $Task[0] -eq "dev") {
    $Task = @("build", "-Pedition=dev")
}

# runClient 一律走开发版：不带诊断信息的客户端测不出东西
if ($Task -contains "runClient" -and $Task -notcontains "-Pedition=dev") {
    $Task += "-Pedition=dev"
}

# 找一个可用的 JDK 21
$candidates = @(
    "D:\zulu21.48.15-ca-jdk21.0.10-win_x64",
    "D:\graalvm-jdk-21.0.12+7.1",
    "D:\zulu25.30.17-ca-jdk25.0.1-win_x64",
    "C:\Program Files\Android\openjdk\jdk-21.0.8"
)

$jdk = $null
foreach ($c in $candidates) {
    if (Test-Path (Join-Path $c "bin\java.exe")) { $jdk = $c; break }
}

if (-not $jdk) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        $jdk = $env:JAVA_HOME
    } else {
        throw "找不到可用的 JDK。请修改 dev.ps1 里的 `$candidates 列表。"
    }
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
# 这台机器上 Java 直连部分 CDN（services.gradle.org / repo1.maven.org）不通，必须走本地代理
$env:JAVA_OPTS = "-Djava.net.preferIPv4Stack=true " +
    "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 " +
    "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
Write-Host "[AutoBridge] JAVA_HOME = $jdk" -ForegroundColor Cyan
Write-Host "[AutoBridge] gradle $($Task -join ' ')" -ForegroundColor Cyan

& (Join-Path $PSScriptRoot "gradlew.bat") @Task
exit $LASTEXITCODE
