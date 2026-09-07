. (Join-Path $PSScriptRoot 'common.ps1')
foreach ($item in @(@{name='Application';port=8080}, @{name='Key service';port=8081}, @{name='Processor';port=8082})) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($item.port)/health" -TimeoutSec 2
        Write-Host "$($item.name): HTTP $($response.StatusCode)"
    } catch { Write-Host "$($item.name): unavailable" }
}
if (Test-Path -LiteralPath (Join-Path $script:DemoLocal 'compose.env')) { Invoke-DemoCompose -Arguments @('ps') }
