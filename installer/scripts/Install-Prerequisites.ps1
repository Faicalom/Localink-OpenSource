param()

$ErrorActionPreference = "Stop"

function Write-SetupLog {
    param([string]$Message)
    Write-Host "[Localink Setup] $Message"
}

function Test-DotNetSharedFrameworkInstalled {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FrameworkName,
        [string]$MajorMinor = "10.0"
    )

    $dotnetCommand = Get-Command dotnet.exe -ErrorAction SilentlyContinue
    if ($dotnetCommand) {
        try {
            $runtimeLines = & $dotnetCommand.Source --list-runtimes 2>$null
            if ($LASTEXITCODE -eq 0 -and $runtimeLines) {
                foreach ($line in $runtimeLines) {
                    $parts = ($line -split '\s+') | Where-Object { $_ -ne '' }
                    if ($parts.Length -ge 2 -and $parts[0] -eq $FrameworkName) {
                        $version = $parts[1]
                        if ($version -like "$MajorMinor.*") {
                            return $true
                        }
                    }
                }
            }
        }
        catch {
            Write-SetupLog "dotnet runtime probe failed for $FrameworkName. Falling back to registry lookup."
        }
    }

    $paths = @(
        "HKLM:\SOFTWARE\WOW6432Node\dotnet\Setup\InstalledVersions\x64\sharedfx\$FrameworkName",
        "HKLM:\SOFTWARE\dotnet\Setup\InstalledVersions\x64\sharedfx\$FrameworkName"
    )

    foreach ($path in $paths) {
        if (-not (Test-Path $path)) {
            continue
        }

        $item = Get-ItemProperty -Path $path
        foreach ($property in $item.PSObject.Properties.Name) {
            if ($property -like "$MajorMinor.*") {
                return $true
            }
        }
    }

    return $false
}

function Get-WingetPath {
    $winget = Get-Command winget.exe -ErrorAction SilentlyContinue
    if ($winget) {
        return $winget.Source
    }

    $knownPath = Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps\winget.exe"
    if (Test-Path $knownPath) {
        return $knownPath
    }

    return $null
}

function Install-WithWinget {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageId,
        [Parameter(Mandatory = $true)]
        [string]$FriendlyName
    )

    $wingetPath = Get-WingetPath
    if (-not $wingetPath) {
        throw "winget is not available. Install Windows App Installer first, then run Localink Setup again."
    }

    Write-SetupLog "Installing $FriendlyName..."
    $arguments = @(
        "install",
        "--id", $PackageId,
        "-e",
        "--accept-package-agreements",
        "--accept-source-agreements",
        "--disable-interactivity",
        "--force"
    )

    $process = Start-Process -FilePath $wingetPath -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "$FriendlyName installation failed with exit code $($process.ExitCode)."
    }
}

Write-SetupLog "Checking Microsoft .NET prerequisites..."

$desktopRuntimeInstalled = Test-DotNetSharedFrameworkInstalled -FrameworkName "Microsoft.WindowsDesktop.App"
$aspNetRuntimeInstalled = Test-DotNetSharedFrameworkInstalled -FrameworkName "Microsoft.AspNetCore.App"

if (-not $desktopRuntimeInstalled) {
    Install-WithWinget -PackageId "Microsoft.DotNet.DesktopRuntime.10" -FriendlyName ".NET Desktop Runtime 10"
} else {
    Write-SetupLog ".NET Desktop Runtime 10 already present."
}

if (-not $aspNetRuntimeInstalled) {
    Install-WithWinget -PackageId "Microsoft.DotNet.AspNetCore.10" -FriendlyName "ASP.NET Core Runtime 10"
} else {
    Write-SetupLog "ASP.NET Core Runtime 10 already present."
}

if (-not (Test-DotNetSharedFrameworkInstalled -FrameworkName "Microsoft.WindowsDesktop.App")) {
    throw ".NET Desktop Runtime 10 is still missing after installation."
}

if (-not (Test-DotNetSharedFrameworkInstalled -FrameworkName "Microsoft.AspNetCore.App")) {
    throw "ASP.NET Core Runtime 10 is still missing after installation."
}

Write-SetupLog "All Localink prerequisites are ready."
