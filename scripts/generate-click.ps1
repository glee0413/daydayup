$target = Join-Path $PSScriptRoot '..\app\src\main\res\raw\click.wav'
$rate = 22050
$count = [int]($rate * 0.06)
$stream = [System.IO.File]::Create($target)
$writer = [System.IO.BinaryWriter]::new($stream)
try {
    $dataBytes = $count * 2
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
    $writer.Write([int](36 + $dataBytes))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
    $writer.Write([int]16)
    $writer.Write([short]1)
    $writer.Write([short]1)
    $writer.Write([int]$rate)
    $writer.Write([int]($rate * 2))
    $writer.Write([short]2)
    $writer.Write([short]16)
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
    $writer.Write([int]$dataBytes)
    for ($i = 0; $i -lt $count; $i++) {
        $time = $i / $rate
        $attack = [Math]::Min(1.0, $time / 0.0008)
        $decay = if ($time -lt 0.025) { 1.0 } else { [Math]::Exp(-80 * ($time - 0.025)) }
        $wave = ([Math]::Sin(2 * [Math]::PI * 1800 * $time) +
            0.28 * [Math]::Sin(2 * [Math]::PI * 3600 * $time)) / 1.15
        $sample = [int](32000 * $attack * $decay * $wave)
        $writer.Write([short]$sample)
    }
} finally {
    $writer.Dispose()
}
