param(
    [string]$Url = "http://127.0.0.1:8765/mcp",
    [string]$Token = $env:TLMM_MCP_TOKEN
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Set TLMM_MCP_TOKEN or pass -Token. Tokens are issued in game with /tlmm mcp token create <days>."
}

$headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

function Invoke-Mcp {
    param([int]$Id, [string]$Method, [hashtable]$Params)
    $body = @{
        jsonrpc = "2.0"
        id = $Id
        method = $Method
        params = $Params
    } | ConvertTo-Json -Depth 20 -Compress
    try {
        return Invoke-RestMethod -Uri $Url -Method Post -Headers $headers -Body $body
    } catch {
        throw "MCP $Method failed: $($_.Exception.Message)"
    }
}

function Show-ToolText {
    param($Response)
    if ($null -eq $Response.result -or $null -eq $Response.result.content) {
        return "<no content>"
    }
    return $Response.result.content[0].text
}

Write-Host "1/6 initialize"
$initialize = Invoke-Mcp -Id 1 -Method "initialize" -Params @{}
if ([string]::IsNullOrWhiteSpace($initialize.result.protocolVersion)) {
    throw "initialize did not return a protocolVersion"
}

Write-Host "2/6 tools/list"
$tools = Invoke-Mcp -Id 2 -Method "tools/list" -Params @{}
$names = @($tools.result.tools | ForEach-Object { $_.name })
if ($names.Count -eq 0) {
    throw "tools/list returned no tools"
}
Write-Host ("    tools: " + ($names -join ", "))

Write-Host "3/6 ping (health snapshot)"
$ping = Invoke-Mcp -Id 3 -Method "ping" -Params @{}
if ($null -eq $ping.result.mspt_average) {
    throw "ping did not return the server health snapshot"
}
Write-Host ("    mspt_avg={0} mspt_max={1} heap={2}/{3}MB threads={4} companions={5}" -f `
    $ping.result.mspt_average, $ping.result.mspt_max, $ping.result.heap_used_mb, `
    $ping.result.heap_max_mb, $ping.result.thread_count, $ping.result.companion_count)

Write-Host "4/6 companion_list"
$list = Invoke-Mcp -Id 4 -Method "tools/call" -Params @{ name = "companion_list"; arguments = @{} }
$listText = Show-ToolText $list
Write-Host ("    " + $listText)

$companions = @()
if ($listText.TrimStart().StartsWith("[")) {
    $parsed = $listText | ConvertFrom-Json
    if ($null -ne $parsed) {
        $companions = @($parsed)
    }
}

if ($companions.Count -eq 0) {
    Write-Host "5/6 companion actions skipped: summon a companion in game to exercise state/context."
    Write-Host "6/6 companion actions skipped."
    Write-Host "E2E PASS (protocol and health only)"
    exit 0
}

$companionId = $companions[0].companion_id
Write-Host "5/6 companion_state"
$state = Invoke-Mcp -Id 5 -Method "tools/call" -Params @{
    name = "companion_state"
    arguments = @{ companion_id = $companionId }
}
if ($state.result.isError) {
    throw ("companion_state failed: " + (Show-ToolText $state))
}
Write-Host ("    " + (Show-ToolText $state))

Write-Host "6/6 companion_context self and wait_events"
$context = Invoke-Mcp -Id 6 -Method "tools/call" -Params @{
    name = "companion_context"
    arguments = @{ companion_id = $companionId; category = "self"; radius = 8 }
}
if ($context.result.isError) {
    throw ("companion_context failed: " + (Show-ToolText $context))
}
Write-Host ("    " + (Show-ToolText $context))
$events = Invoke-Mcp -Id 7 -Method "tools/call" -Params @{
    name = "companion_wait_events"
    arguments = @{ companion_id = $companionId; after_seq = 0; timeout_seconds = 1; max_events = 20 }
}
if ($events.result.isError) {
    throw ("companion_wait_events failed: " + (Show-ToolText $events))
}
Write-Host ("    " + (Show-ToolText $events))

Write-Host "E2E PASS"
