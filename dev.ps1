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

# 找一个可用的 JDK：优先环境变量与 PATH，最后才试下面的本机路径。
$jdk = $null

if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = $env:JAVA_HOME
}

if (-not $jdk) {
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        # 从 ...\bin\java.exe 反推出 JDK 根目录
        $jdk = Split-Path (Split-Path $onPath.Source -Parent) -Parent
    }
}

# 本机（开发者的机器）专用回退路径 —— 别人的机器上通常走不到这里
$localCandidates = @(
    "D:\zulu21.48.15-ca-jdk21.0.10-win_x64",
    "D:\graalvm-jdk-21.0.12+7.1",
    "C:\Program Files\Android\openjdk\jdk-21.0.8"
)

if (-not $jdk) {
    foreach ($c in $localCandidates) {
        if (Test-Path (Join-Path $c "bin\java.exe")) { $jdk = $c; break }
    }
}

if (-not $jdk) {
    throw "找不到可用的 JDK。请设置 JAVA_HOME 环境变量，或在 dev.ps1 的 `$localCandidates 里补一个路径。"
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
# 这里刻意不设代理。本机 Java 直连 maven.fabricmc.net / libraries.minecraft.net /
# piston-meta.mojang.com 都通，本项目构建所需的依赖全在这几个源上；
# 而 Gradle 发行版已指向本地 file:// 的 zip，也不需要 services.gradle.org。
# 反之，一旦在这里写死代理，Clash Verge 没开的时候整个构建会连带失败
# （连直连能通的 Fabric 仓库也一起挂）。确实需要代理时再手动加：
#   $env:JAVA_OPTS = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 " +
#                    "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890"
$env:JAVA_OPTS = "-Djava.net.preferIPv4Stack=true"
Write-Host "[AutoBridge] JAVA_HOME = $jdk" -ForegroundColor Cyan
Write-Host "[AutoBridge] gradle $($Task -join ' ')" -ForegroundColor Cyan

& (Join-Path $PSScriptRoot "gradlew.bat") @Task
exit $LASTEXITCODE
