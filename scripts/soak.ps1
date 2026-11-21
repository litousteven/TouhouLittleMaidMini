param(
    [int]$Minutes = 60,
    [int]$IntervalSeconds = 60,
    [string]$Url = "http://127.0.0.1:8765/mcp",
    [string]$Token = $env:TLMM_MCP_TOKEN,
    [string]$Output = "",
    [double]$MaxFailurePercent = 1.0,
    [int]$MaxConsecutiveFailures = 5,
    [int]$MaxHeapTrendMb = 192,
    [int]$MaxThreadTrend = 8
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Set TLMM_MCP_TOKEN or pass -Token."
}
if ($Minutes -lt 1) {
    throw "-Minutes must be at least 1"
}
if ($IntervalSeconds -lt 1) {
    throw "-IntervalSeconds must be at least 1"
}

$headers = @{
    Authorization = "Bearer $Token"
    "Content-Type" = "application/json"
}

function Invoke-Ping {
    param([int]$Id)
    $body = @{
        jsonrpc = "2.0"
        id = $Id
        method = "ping"
        params = @{}
    } | ConvertTo-Json -Depth 10 -Compress
    return Invoke-RestMethod -Uri $Url -Method Post -Headers $headers -Body $body
}

if ([string]::IsNullOrWhiteSpace($Output)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Output = Join-Path (Join-Path (Get-Location) "run") "soak-$stamp.csv"
}
$outputDirectory = Split-Path -Parent $Output
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

$start = Get-Date
$end = $start.AddMinutes($Minutes)
$samples = New-Object System.Collections.Generic.List[object]
$failures = 0
$consecutiveFailures = 0
$requestId = 100

Write-Host ("Soak start: {0} minutes, sample every {1}s -> {2}" -f $Minutes, $IntervalSeconds, $Output)

while ((Get-Date) -lt $end) {
    $requestId++
    $timestamp = Get-Date
    try {
        $ping = Invoke-Ping -Id $requestId
        $result = $ping.result
        $sample = [pscustomobject]@{
            timestamp = $timestamp.ToString("o")
            companion_count = $result.companion_count
            mspt_average = $result.mspt_average
            mspt_max = $result.mspt_max
            heap_used_mb = $result.heap_used_mb
            heap_max_mb = $result.heap_max_mb
            thread_count = $result.thread_count
            retained_events = $result.retained_events
            mcp_connected = $result.mcp_connected
        }
        $samples.Add($sample)
        $consecutiveFailures = 0
        Write-Host ("{0} companions={1} mspt={2}/{3} heap={4}MB threads={5} events={6}" -f `
            $timestamp.ToString("HH:mm:ss"), $result.companion_count, $result.mspt_average, `
            $result.mspt_max, $result.heap_used_mb, $result.thread_count, $result.retained_events)
    } catch {
        $failures++
        $consecutiveFailures++
        Write-Warning ("sample failed ({0} consecutive): {1}" -f $consecutiveFailures, $_.Exception.Message)
        $samples.Add([pscustomobject]@{
            timestamp = $timestamp.ToString("o")
            companion_count = $null
            mspt_average = $null
            mspt_max = $null
            heap_used_mb = $null
            heap_max_mb = $null
            thread_count = $null
            retained_events = $null
            mcp_connected = $false
        })
        if ($consecutiveFailures -ge $MaxConsecutiveFailures) {
            $samples | Export-Csv -Path $Output -NoTypeInformation -Encoding UTF8
            throw "Soak aborted: $consecutiveFailures consecutive sample failures. Partial CSV: $Output"
        }
    }
    if ((Get-Date) -lt $end) {
        Start-Sleep -Seconds $IntervalSeconds
    }
}

$samples | Export-Csv -Path $Output -NoTypeInformation -Encoding UTF8
if ($samples.Count -eq 0) {
    throw "No samples were collected"
}

$valid = @($samples | Where-Object { $null -ne $_.heap_used_mb })
if ($valid.Count -eq 0) {
    throw "Every sample failed; the endpoint was unreachable"
}
$first = $valid[0]
$last = $valid[$valid.Count - 1]
$maxHeap = ($valid | Measure-Object -Property heap_used_mb -Maximum).Maximum
$maxMspt = ($valid | Measure-Object -Property mspt_max -Maximum).Maximum
$threadGrowth = [int]$last.thread_count - [int]$first.thread_count
$heapGrowth = [int]$last.heap_used_mb - [int]$first.heap_used_mb

# First-vs-last is noisy across a long soak: one GC at either end can hide a real
# upward trend. Compare the mean of the earliest and latest 20% of valid samples.
$windowSize = [Math]::Max(1, [int][Math]::Ceiling($valid.Count * 0.2))
$head = @($valid | Select-Object -First $windowSize)
$tail = @($valid | Select-Object -Last $windowSize)
$headHeap = [double](($head | Measure-Object -Property heap_used_mb -Average).Average)
$tailHeap = [double](($tail | Measure-Object -Property heap_used_mb -Average).Average)
$heapTrend = [int][Math]::Round($tailHeap - $headHeap)
$headThreads = [double](($head | Measure-Object -Property thread_count -Average).Average)
$tailThreads = [double](($tail | Measure-Object -Property thread_count -Average).Average)
$threadTrend = [int][Math]::Round($tailThreads - $headThreads)
$failurePercent = [Math]::Round(100.0 * $failures / $samples.Count, 2)

Write-Host ""
Write-Host ("Samples: {0} ({1} failed, {2}%)" -f $samples.Count, $failures, $failurePercent)
Write-Host ("Heap: first={0}MB last={1}MB max={2}MB growth={3}MB" -f `
    $first.heap_used_mb, $last.heap_used_mb, $maxHeap, $heapGrowth)
Write-Host ("Heap trend (first/last {0}% mean): {1}MB -> {2}MB = {3}MB" -f `
    [int][Math]::Round(100.0 * $windowSize / $valid.Count), [int]$headHeap, [int]$tailHeap, $heapTrend)
Write-Host ("Threads: first={0} last={1} growth={2} trend={3}" -f `
    $first.thread_count, $last.thread_count, $threadGrowth, $threadTrend)
Write-Host ("Max single-tick MSPT: {0}" -f $maxMspt)
Write-Host ("CSV: {0}" -f $Output)

if ($failurePercent -gt $MaxFailurePercent) {
    throw "Soak failed: $failures of $samples.Count samples could not reach the endpoint"
}
if ($threadGrowth -gt 32 -or $threadTrend -gt $MaxThreadTrend) {
    throw "Soak failed: thread count grew (total $threadGrowth, trend $threadTrend)"
}
if ($heapGrowth -gt 512 -or $heapTrend -gt $MaxHeapTrendMb) {
    throw "Soak failed: heap grew (total ${heapGrowth}MB, trend ${heapTrend}MB)"
}
if ($maxMspt -gt 250) {
    throw "Soak failed: a tick took $maxMspt ms"
}

Write-Host "SOAK PASS"
