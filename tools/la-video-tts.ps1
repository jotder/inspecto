# Speaks each {file, text} item of a JSON array to a WAV file with the Windows offline voices.
# Used by tools/render-la-case-video.mjs.   Usage: la-video-tts.ps1 -Items items.json [-Voice 'Microsoft Mark'] [-Rate -1]
param(
  [Parameter(Mandatory = $true)][string]$Items,
  [string]$Voice = 'Microsoft Mark',
  [int]$Rate = 2
)
Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try { $synth.SelectVoice($Voice) } catch { Write-Warning "voice '$Voice' not found; using the default" }
$synth.Rate = $Rate
$list = Get-Content -Raw -Encoding UTF8 $Items | ConvertFrom-Json
foreach ($it in $list) {
  $synth.SetOutputToWaveFile($it.file)
  $synth.Speak($it.text)
  $synth.SetOutputToNull()
}
$synth.Dispose()
