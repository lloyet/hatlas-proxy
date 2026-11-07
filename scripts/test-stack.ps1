<#
.SYNOPSIS
  Controls the LOCAL TEST STACK of hatlas-proxy: Velocity 4.3.1 + a hub and a faction Paper 1.21.11
  backend, for in-game tests with VitaminMCP bots through the proxy.

.DESCRIPTION
  Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-stack.ps1 <action> [target] [command]

  - install : creates the stack folder, downloads Velocity / Paper (PaperMC Fill API, SHA-256 checked),
              writes the configs (offline proxy + modern forwarding, localhost-only backends, VitaminMCP
              on its own port per backend), copies VitaminMCP.jar, creates the database. Re-run safe:
              existing server.properties / paper-global.yml / secrets are kept.
  - start [all|proxy|hub|faction]   : starts (backends first, then the proxy), waits for "Done (".
  - stop  [all|proxy|hub|faction]   : backends stop over RCON (clean save); the proxy process is
              ended (Velocity has no RCON; the stack is disposable).
  - restart [target] : stop then start.
  - status  : running / stopped for each component.
  - deploy  : gradlew build, replaces plugins/hatlas.proxy-*.jar on the proxy, restarts the proxy.
  - reset   : stops everything, deletes the backend worlds (and bot player data), drops and recreates
              the test database, loads the ticket fixture (test-stack/web-tickets.sql: Tester1 valid,
              Tester2 expired, Tester3 none, Tester4 valid), rewrites the plugin config, starts
              everything. Run before a test campaign.
  - db-up   : checks the PostgreSQL container is running and creates the test database if missing.
  - console <hub|faction> "<cmd>" : one console command over RCON.

  Layout (outside the repo): <TEST_STACK_DIR>\{proxy,hub,faction,cache}.
  Ports (all bound to 127.0.0.1):
    proxy 25577 | hub 25601 (RCON 25611, VitaminMCP 25621) | faction 25602 (RCON 25612, VitaminMCP 25622)
  Bots join through the proxy: session_start {"name":"hub","port":25577,"mcpPort":25621}, and
  {"name":"faction","port":25577,"mcpPort":25622} for the faction backend.

  SAFETY: this script never touches the shared Hatlas test server (25565 / 25575 / 25585) nor the
  hatlas / hatlas_test databases, and never starts, stops or recreates the PostgreSQL container
  (it belongs to ../hatlas); it only creates / drops TEST_DB_NAME inside it.

  Environment (all optional; missing values read from <repo>\.env then ..\hatlas\docker\postgres\.env):
    TEST_STACK_DIR        stack folder (default C:\Users\lloyet\Documents\Projects\servers\hatlas-proxy-stack)
    TEST_STACK_JAVA       java executable (default: newest JDK 25 in ~/.jdks)
    TEST_STACK_XMX        heap per backend (default 1G; proxy 512M)
    TEST_DB_NAME          database (default hatlas_proxy_dev)
    PG_CONTAINER          PostgreSQL container (default hatlas-postgres-postgres-1)
    HATLAS_DB_PASSWORD    PostgreSQL password (passed to the proxy; the plugin reads it)
    VITAMINMCP_PLUGIN_JAR VitaminMCP plugin jar to copy (default: the one in the shared test server, read only)
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('install', 'start', 'stop', 'restart', 'status', 'deploy', 'reset', 'db-up', 'console')]
    [string]$Action,
    [Parameter(Position = 1)]
    [string]$Target = 'all',
    [Parameter(Position = 2)]
    [string]$Command
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot

foreach ($envFile in @((Join-Path $ProjectDir '.env'),
                       (Join-Path (Split-Path -Parent $ProjectDir) 'hatlas\docker\postgres\.env'))) {
    if (-not (Test-Path $envFile)) { continue }
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not [Environment]::GetEnvironmentVariable($Matches[1])) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"', "'"))
        }
    }
}

$StackDir    = if ($env:TEST_STACK_DIR) { $env:TEST_STACK_DIR } else { 'C:\Users\lloyet\Documents\Projects\servers\hatlas-proxy-stack' }
$Xmx         = if ($env:TEST_STACK_XMX) { $env:TEST_STACK_XMX } else { '1G' }
$DbName      = if ($env:TEST_DB_NAME) { $env:TEST_DB_NAME } else { 'hatlas_proxy_dev' }
$PgContainer = if ($env:PG_CONTAINER) { $env:PG_CONTAINER } else { 'hatlas-postgres-postgres-1' }
$SharedServer = 'C:\Users\lloyet\Documents\Projects\servers\minecraft-paper1.21.11'
$VitaminJar  = if ($env:VITAMINMCP_PLUGIN_JAR) { $env:VITAMINMCP_PLUGIN_JAR } else { Join-Path $SharedServer 'plugins\VitaminMCP.jar' }
$JavaExe     = if ($env:TEST_STACK_JAVA) { $env:TEST_STACK_JAVA } else {
    $jdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -match '(^|[^0-9])25[.\-]' -and (Test-Path (Join-Path $_.FullName 'bin\java.exe')) } |
           Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { Join-Path $jdk.FullName 'bin\java.exe' } else { 'java' }
}

$PaperVersion    = '1.21.11'
$VelocityVersion = '4.3.1'
$ProxyPort = 25577
# Superflat layers (an empty generator-settings makes Paper log "No key layers").
$FlatLayers = '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
$Backends = [ordered]@{
    hub     = @{ Port = 25601; Rcon = 25611; Mcp = 25621 }
    faction = @{ Port = 25602; Rcon = 25612; Mcp = 25622 }
}

# ── Guards: never reach the shared Hatlas test server or its databases ───────
$Forbidden = @(25565, 25575, 25585)
foreach ($b in $Backends.Values) {
    foreach ($p in @($b.Port, $b.Rcon, $b.Mcp)) { if ($Forbidden -contains $p) { throw "port $p belongs to the shared test server" } }
}
if ($DbName -in @('hatlas', 'hatlas_test', 'postgres')) { throw "TEST_DB_NAME '$DbName' is not a proxy test database" }
if ((Resolve-Path -LiteralPath $StackDir -ErrorAction SilentlyContinue).Path -eq $SharedServer) { throw 'TEST_STACK_DIR points at the shared test server' }

function Get-Dir([string]$name) { Join-Path $StackDir $name }

function Get-StackProcess([string]$name) {
    $pidFile = Join-Path (Get-Dir $name) 'stack.pid'
    if (-not (Test-Path $pidFile)) { return $null }
    $proc = Get-Process -Id ([int](Get-Content $pidFile -Raw)) -ErrorAction SilentlyContinue
    if ($null -eq $proc -or $proc.ProcessName -notmatch 'java') { return $null }
    return $proc
}

function Get-Targets([string]$t, [switch]$StartOrder) {
    $all = if ($StartOrder) { @('hub', 'faction', 'proxy') } else { @('proxy', 'hub', 'faction') }
    if ($t -eq 'all') { return $all }
    if ($all -notcontains $t) { throw "unknown target '$t' (all, proxy, hub, faction)" }
    return @($t)
}

# ── Minimal RCON client (Source RCON protocol) ──────────────────────────────
function Send-RconPacket([System.IO.Stream]$stream, [int]$id, [int]$type, [string]$body) {
    $payload = [System.Text.Encoding]::UTF8.GetBytes($body)
    $length  = 4 + 4 + $payload.Length + 2
    $buf = New-Object byte[] (4 + $length)
    [BitConverter]::GetBytes([int]$length).CopyTo($buf, 0)
    [BitConverter]::GetBytes($id).CopyTo($buf, 4)
    [BitConverter]::GetBytes($type).CopyTo($buf, 8)
    $payload.CopyTo($buf, 12)
    $stream.Write($buf, 0, $buf.Length)
    $stream.Flush()
}

function Read-RconPacket([System.IO.Stream]$stream) {
    $header = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) { $read += $stream.Read($header, $read, 4 - $read) }
    $length = [BitConverter]::ToInt32($header, 0)
    $data = New-Object byte[] $length
    $read = 0
    while ($read -lt $length) { $read += $stream.Read($data, $read, $length - $read) }
    return @{ Id = [BitConverter]::ToInt32($data, 0); Body = [System.Text.Encoding]::UTF8.GetString($data, 8, [Math]::Max(0, $length - 10)) }
}

function Get-RconPassword([string]$name) {
    $props = Join-Path (Get-Dir $name) 'server.properties'
    $line = Select-String -Path $props -Pattern '^rcon\.password=(.+)$' | Select-Object -First 1
    if (-not $line) { throw "no rcon.password in $props (run install)" }
    return $line.Matches[0].Groups[1].Value
}

function Invoke-Rcon([string]$name, [string]$cmd) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Backends[$name].Rcon)
        $client.ReceiveTimeout = 10000
        $stream = $client.GetStream()
        Send-RconPacket $stream 1 3 (Get-RconPassword $name)
        if ((Read-RconPacket $stream).Id -eq -1) { throw "RCON authentication failed on $name" }
        Send-RconPacket $stream 2 2 $cmd
        try { return (Read-RconPacket $stream).Body } catch { return '' }  # "stop" may close the socket first
    } finally { $client.Close() }
}

# ── Database (shared PostgreSQL container, own database) ────────────────────
function Invoke-Psql([string]$db, [string]$sql) {
    $out = & docker exec $PgContainer psql -U hatlas -d $db -v ON_ERROR_STOP=1 -tAc $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $sql" }
    return $out
}

function Start-Database {
    $running = & docker ps --filter "name=^$PgContainer$" --filter 'status=running' --format '{{.Names}}' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'docker is not reachable (is Docker Desktop running?)' }
    if (-not $running) {
        throw "PostgreSQL container '$PgContainer' is not running. It belongs to ../hatlas: start it there (docker compose -f docker/postgres/docker-compose.yml up -d --wait); this script never starts it."
    }
    if (-not (Invoke-Psql 'hatlas' "SELECT 1 FROM pg_database WHERE datname = '$DbName'")) {
        Invoke-Psql 'hatlas' "CREATE DATABASE $DbName OWNER hatlas" | Out-Null
        Write-Output "created database $DbName"
    }
    Write-Output "database ready ($DbName in $PgContainer)"
}

function Reset-Database {
    Start-Database | Out-Null
    Invoke-Psql 'hatlas' "DROP DATABASE IF EXISTS $DbName WITH (FORCE)" | Out-Null
    Invoke-Psql 'hatlas' "CREATE DATABASE $DbName OWNER hatlas" | Out-Null
    Write-Output "database $DbName recreated (empty)"
}

# Website ticket table with rows for the test bots (dates relative to now), for the access suites.
function Import-WebTickets {
    $sql = Get-Content (Join-Path $PSScriptRoot 'test-stack\web-tickets.sql') -Raw
    $sql | & docker exec -i $PgContainer psql -U hatlas -d $DbName -v ON_ERROR_STOP=1 -q -f - | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'web-tickets.sql failed' }
    Write-Output 'web.tickets fixture loaded (Tester1 valid, Tester2 expired, Tester3 none, Tester4 valid)'
}

# ── Install ─────────────────────────────────────────────────────────────────
function Get-PaperMcJar([string]$project, [string]$version) {
    $cache = Get-Dir 'cache'
    New-Item -ItemType Directory -Force $cache | Out-Null
    $headers = @{ 'User-Agent' = 'hatlas-proxy-test-stack/1.0' }
    $build = Invoke-RestMethod -Headers $headers "https://fill.papermc.io/v3/projects/$project/versions/$version/builds/latest"
    $dl = $build.downloads.'server:default'
    $file = Join-Path $cache $dl.name
    if (-not (Test-Path $file)) {
        Invoke-WebRequest -Headers $headers -Uri $dl.url -OutFile "$file.part" -UseBasicParsing
        $sha = (Get-FileHash "$file.part" -Algorithm SHA256).Hash.ToLower()
        if ($sha -ne $dl.checksums.sha256.ToLower()) { Remove-Item "$file.part"; throw "SHA-256 mismatch for $($dl.name)" }
        Move-Item "$file.part" $file
        Write-Host "downloaded $($dl.name)"
    }
    return $file
}

function New-Secret([int]$bytes = 24) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    return ([Convert]::ToBase64String($b) -replace '[^A-Za-z0-9]', '')
}

function Write-Utf8([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding $false))
}

function Install-Stack {
    New-Item -ItemType Directory -Force $StackDir | Out-Null
    $paper = Get-PaperMcJar 'paper' $PaperVersion
    $velocity = Get-PaperMcJar 'velocity' $VelocityVersion
    if (-not (Test-Path $VitaminJar)) { throw "VitaminMCP plugin jar not found at $VitaminJar (set VITAMINMCP_PLUGIN_JAR)" }

    # Proxy
    $proxy = Get-Dir 'proxy'
    New-Item -ItemType Directory -Force (Join-Path $proxy 'plugins\hatlas-proxy') | Out-Null
    Copy-Item $velocity (Join-Path $proxy 'server.jar') -Force
    $secretFile = Join-Path $proxy 'forwarding.secret'
    if (-not (Test-Path $secretFile)) { Write-Utf8 $secretFile (New-Secret 32) }
    $secret = (Get-Content $secretFile -Raw).Trim()
    Copy-Item (Join-Path $PSScriptRoot 'test-stack\velocity.toml') (Join-Path $proxy 'velocity.toml') -Force
    Copy-Item (Join-Path $PSScriptRoot 'test-stack\hatlas-proxy-config.yml') (Join-Path $proxy 'plugins\hatlas-proxy\config.yml') -Force

    # Backends
    foreach ($name in $Backends.Keys) {
        $b = $Backends[$name]
        $dir = Get-Dir $name
        New-Item -ItemType Directory -Force (Join-Path $dir 'config'), (Join-Path $dir 'plugins\VitaminMCP') | Out-Null
        Copy-Item $paper (Join-Path $dir 'server.jar') -Force
        Copy-Item $VitaminJar (Join-Path $dir 'plugins\VitaminMCP.jar') -Force
        # The user accepts the Minecraft EULA for the servers they run (https://aka.ms/MinecraftEULA).
        Write-Utf8 (Join-Path $dir 'eula.txt') "eula=true`n"
        $props = Join-Path $dir 'server.properties'
        if (-not (Test-Path $props)) {
            Write-Utf8 $props (@(
                "server-ip=127.0.0.1", "server-port=$($b.Port)", "online-mode=false", "enforce-secure-profile=false",
                "enable-rcon=true", "rcon.port=$($b.Rcon)", "rcon.password=$(New-Secret)", "broadcast-rcon-to-ops=false",
                "motd=hatlas-proxy test $name", "level-type=minecraft\:flat", "generate-structures=false",
                ('generator-settings=' + $FlatLayers),
                "spawn-protection=0", "difficulty=peaceful", "spawn-monsters=false", "view-distance=4",
                "simulation-distance=4", "max-players=20", "allow-flight=true"
            ) -join "`n")
        }
        $global = Join-Path $dir 'config\paper-global.yml'
        if (-not (Test-Path $global)) {
            # Paper fills in every other key on first start and keeps these.
            Write-Utf8 $global "_version: 31`nproxies:`n  velocity:`n    enabled: true`n    online-mode: false`n    secret: '$secret'`n"
        }
        # Unknown keys keep VitaminMCP's defaults; the token is generated on first start.
        Write-Utf8 (Join-Path $dir 'plugins\VitaminMCP\config.yml') "enabled: true`nbind-address: 127.0.0.1`nport: $($b.Mcp)`nlocal-handshake: true`nread-only: false`n"
    }
    Start-Database
    Write-Output "stack installed in $StackDir (Paper $PaperVersion, Velocity $VelocityVersion). Next: deploy, then start."
}

# ── Start / stop ────────────────────────────────────────────────────────────
function Start-Component([string]$name) {
    if (Get-StackProcess $name) { Write-Output "$name already running"; return $null }
    $dir = Get-Dir $name
    if (-not (Test-Path (Join-Path $dir 'server.jar'))) { throw "$name is not installed (run install)" }
    $log = Join-Path $dir 'logs\latest.log'
    if (Test-Path $log) { Move-Item $log (Join-Path $dir 'logs\previous.log') -Force }
    $heap = if ($name -eq 'proxy') { '512M' } else { $Xmx }
    $javaArgs = @("-Xms$heap", "-Xmx$heap", '-Dlog4j2.formatMsgNoLookups=true', '-XX:+UseG1GC', '-jar', 'server.jar')
    if ($name -ne 'proxy') { $javaArgs += @('--nojline', 'nogui') }
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $javaArgs -WorkingDirectory $dir -WindowStyle Minimized -PassThru
    Set-Content -Path (Join-Path $dir 'stack.pid') -Value $proc.Id
    return @{ Name = $name; Proc = $proc; Log = $log }
}

function Wait-Ready($started) {
    foreach ($s in $started) {
        if ($null -eq $s) { continue }
        $ready = $false
        for ($i = 0; $i -lt 180 -and -not $ready; $i++) {
            if ($s.Proc.HasExited) { throw "$($s.Name) exited during startup (code $($s.Proc.ExitCode)), see $($s.Log)" }
            if ((Test-Path $s.Log) -and (Select-String -Path $s.Log -Pattern 'Done \(' -Quiet)) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "$($s.Name): startup timeout (180 s)" }
        Write-Output "$($s.Name) ready (pid $($s.Proc.Id))"
    }
}

function Start-Stack([string]$t) {
    $targets = Get-Targets $t -StartOrder
    if ($targets | Where-Object { $_ -ne 'proxy' }) { Start-Database | Out-Null }
    # Backends boot in parallel, the proxy after them.
    $backends = @($targets | Where-Object { $_ -ne 'proxy' } | ForEach-Object { Start-Component $_ })
    Wait-Ready $backends
    if ($targets -contains 'proxy') { Start-Database | Out-Null; Wait-Ready @(Start-Component 'proxy') }
}

function Stop-Component([string]$name) {
    $proc = Get-StackProcess $name
    $pidFile = Join-Path (Get-Dir $name) 'stack.pid'
    if (-not $proc) { Remove-Item $pidFile -ErrorAction SilentlyContinue; Write-Output "$name already stopped"; return }
    if ($name -eq 'proxy') {
        Stop-Process -Id $proc.Id -Force
        $proc.WaitForExit(30000) | Out-Null
    } else {
        Invoke-Rcon $name 'stop' | Out-Null
        if (-not $proc.WaitForExit(120000)) { throw "$name did not stop within 120 s (pid $($proc.Id))" }
    }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Output "$name stopped"
}

function Stop-Stack([string]$t) { foreach ($n in (Get-Targets $t)) { Stop-Component $n } }

# ── Deploy / reset ──────────────────────────────────────────────────────────
function Deploy-Plugin {
    Push-Location $ProjectDir
    try { & .\gradlew.bat build -q; if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' } } finally { Pop-Location }
    $jar = Get-ChildItem (Join-Path $ProjectDir 'build\libs') -Filter 'hatlas.proxy-*.jar' |
           Where-Object { $_.Name -notmatch '-(sources|javadoc|plain)\.jar$' } |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'no hatlas.proxy-*.jar in build\libs' }
    $plugins = Join-Path (Get-Dir 'proxy') 'plugins'
    if (-not (Test-Path $plugins)) { throw 'proxy is not installed (run install)' }
    Stop-Component 'proxy'
    Get-ChildItem $plugins -Filter 'hatlas.proxy-*.jar' | Remove-Item
    Copy-Item $jar.FullName $plugins
    Write-Output "deployed: $($jar.Name)"
    Start-Stack 'proxy'
}

function Reset-Stack {
    Stop-Stack 'all'
    foreach ($name in $Backends.Keys) {
        foreach ($w in 'world', 'world_nether', 'world_the_end') {
            $p = Join-Path (Get-Dir $name) $w
            if (Test-Path $p) { Remove-Item $p -Recurse -Force }
        }
    }
    Reset-Database
    Import-WebTickets
    Copy-Item (Join-Path $PSScriptRoot 'test-stack\hatlas-proxy-config.yml') (Join-Path (Get-Dir 'proxy') 'plugins\hatlas-proxy\config.yml') -Force
    Write-Output 'worlds deleted, database emptied, plugin config restored'
    Start-Stack 'all'
}

switch ($Action) {
    'install' { Install-Stack }
    'start'   { Start-Stack $Target }
    'stop'    { Stop-Stack $Target }
    'restart' { Stop-Stack $Target; Start-Stack $Target }
    'status'  { foreach ($n in @('proxy', 'hub', 'faction')) { "{0,-8} {1}" -f $n, $(if (Get-StackProcess $n) { 'running' } else { 'stopped' }) } }
    'deploy'  { Deploy-Plugin }
    'reset'   { Reset-Stack }
    'db-up'   { Start-Database }
    'console' {
        if (-not $Backends.Contains($Target) -or -not $Command) { throw 'usage: console <hub|faction> "<command>" (the proxy has no RCON)' }
        Invoke-Rcon $Target $Command
    }
}
