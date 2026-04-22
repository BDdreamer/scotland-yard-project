param(
    [int]$Workers = 4,
    [int]$GamesPerWorker = 5,
    [int]$BaseSeed = 42,
    [bool]$Metrics = $false,
    [int]$RolloutDepth = 10,
    [int]$TimeBudgetMs = 800,
    [string]$JavaOpts = "-Xmx4g",
    [string]$CsvFile = "benchmark_results.csv"
)

$ErrorActionPreference = "Stop"

$runId = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"

Write-Host "=== Benchmark Run: $runId ==="
Write-Host "Config: workers=$Workers, gamesPerWorker=$GamesPerWorker, baseSeed=$BaseSeed"
Write-Host "        rolloutDepth=$RolloutDepth, timeBudgetMs=$TimeBudgetMs"

Write-Host "Preparing benchmark classpath..."
.\mvnw.cmd -q test-compile dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=cp.txt" | Out-Null
$cp = (Get-Content ".\cp.txt" -Raw).Trim()
$classpath = "target\test-classes;target\classes;$cp"

Write-Host "Launching $Workers workers, $GamesPerWorker games each..."
$jobs = @()
for ($i = 0; $i -lt $Workers; $i++) {
    $workerId = $i + 1
    $seed = $BaseSeed + ($workerId * 100000)
    $logFile = "bench_worker_${workerId}.log"

    $argList = @(
        $JavaOpts,
        "-cp", $classpath,
        "-Dbench.games=$GamesPerWorker",
        "-Dbench.metrics=$Metrics",
        "-Dbench.seed=$seed",
        "-Dbench.mrx.rolloutDepth=$RolloutDepth",
        "-Dbench.mrx.timeBudgetMs=$TimeBudgetMs",
        "uk.ac.bris.cs.scotlandyard.ai.BenchmarkRunner"
    )

    $jobs += Start-Process -FilePath "java" `
        -ArgumentList $argList `
        -NoNewWindow `
        -RedirectStandardOutput $logFile `
        -PassThru

    Write-Host "Worker ${workerId} started (seed=$seed, log=$logFile)"
}

Write-Host "Waiting for workers to finish..."
foreach ($job in $jobs) {
    $null = $job.WaitForExit()
}

# Sanity check: all log files exist
$missingLogs = 0
for ($i = 0; $i -lt $Workers; $i++) {
    $workerId = $i + 1
    $logFile = "bench_worker_${workerId}.log"
    if (-not (Test-Path $logFile)) {
        Write-Host "ERROR: Worker ${workerId} log file missing: $logFile"
        $missingLogs++
    }
}
if ($missingLogs -gt 0) {
    Write-Host "ERROR: $missingLogs worker(s) failed to produce log files"
    exit 1
}

$totalGames = 0
$totalMrXWins = 0
$totalDetectiveWins = 0
$totalElapsedMs = 0
$parsedWorkers = 0

Write-Host ""
Write-Host "Per-worker results:"
for ($i = 0; $i -lt $Workers; $i++) {
    $workerId = $i + 1
    $logFile = "bench_worker_${workerId}.log"
    $resultLine = Select-String -Path $logFile -Pattern "^RESULT\s+" | Select-Object -Last 1

    if (-not $resultLine) {
        Write-Host "  Worker ${workerId}: ERROR (missing RESULT line in $logFile)"
        continue
    }

    $line = $resultLine.Line
    if ($line -match "runId=([^\s]+)\s+games=(\d+)\s+mrXWins=(\d+)\s+detectiveWins=(\d+)\s+seed=(\d+)\s+elapsedMs=(\d+)\s+rolloutDepth=(\d+)\s+timeBudgetMs=(\d+)") {
        $wGames       = [int]$matches[2]
        $wMrXWins     = [int]$matches[3]
        $wDetWins     = [int]$matches[4]
        $wSeed        = [long]$matches[5]
        $wElapsedMs   = [long]$matches[6]
        $wRollout     = [int]$matches[7]
        $wTimeBudget  = [int]$matches[8]

        $totalGames        += $wGames
        $totalMrXWins      += $wMrXWins
        $totalDetectiveWins += $wDetWins
        $totalElapsedMs    += $wElapsedMs
        $parsedWorkers++

        Write-Host "  Worker ${workerId}: games=$wGames mrXWins=$wMrXWins detectiveWins=$wDetWins seed=$wSeed elapsedMs=${wElapsedMs}ms"
    } else {
        Write-Host "  Worker ${workerId}: ERROR (unparseable RESULT line)"
        Write-Host "    $line"
    }
}

# Sanity checks
if ($parsedWorkers -ne $Workers) {
    Write-Host "ERROR: Only parsed $parsedWorkers of $Workers workers. Check bench_worker_*.log"
    exit 1
}
if ($totalGames -eq 0) {
    Write-Host "ERROR: No games were parsed from logs."
    exit 1
}

$mrXWinRate  = [math]::Round((100.0 * $totalMrXWins / $totalGames), 4)
$avgPerGameMs = [math]::Round($totalElapsedMs / $totalGames, 0)

Write-Host ""
Write-Host "=== AGGREGATED RESULT ==="
Write-Host "runId=$runId"
Write-Host "totalGames=$totalGames  mrXWins=$totalMrXWins  detectiveWins=$totalDetectiveWins"
Write-Host "mrXWinRate=${mrXWinRate}%"
Write-Host "totalElapsedMs=$totalElapsedMs  avgPerGame=${avgPerGameMs}ms"

# CSV output
$csvExists = Test-Path $CsvFile
if (-not $csvExists) {
    "runId,workers,gamesPerWorker,totalGames,mrXWins,detectiveWins,winRate,totalElapsedMs,avgPerGameMs,rolloutDepth,timeBudgetMs,baseSeed" | Out-File -FilePath $CsvFile -Encoding utf8
}
"$runId,$Workers,$GamesPerWorker,$totalGames,$totalMrXWins,$totalDetectiveWins,$mrXWinRate,$totalElapsedMs,$avgPerGameMs,$RolloutDepth,$TimeBudgetMs,$BaseSeed" | Out-File -FilePath $CsvFile -Append -Encoding utf8

Write-Host ""
Write-Host "Results appended to $CsvFile"
