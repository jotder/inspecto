# Static check: under Set-StrictMode, reading a variable that no dominating assignment set throws.
# Reports every variable read at a point where no assignment precedes it in the same or an enclosing block.
# Reads inside function bodies are accepted if the name is assigned anywhere in the file (dynamic scope).
param([Parameter(Mandatory)][string]$Path)
$tokens = $null; $errs = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $Path).Path, [ref]$tokens, [ref]$errs)
if ($errs -and $errs.Count) { $errs | ForEach-Object { Write-Output "PARSE-ERROR $($_.Message)" }; exit 2 }
$TAssign = [System.Management.Automation.Language.AssignmentStatementAst]
$TVar = [System.Management.Automation.Language.VariableExpressionAst]
$auto = 'true','false','null','_','args','input','PSItem','this','PSScriptRoot','PSCommandPath','MyInvocation','Error','LASTEXITCODE','PWD','HOME','env','ErrorActionPreference','ProgressPreference','VerbosePreference','PSBoundParameters','Matches','ExecutionContext','Host','IsWindows','IsLinux','IsMacOS','PSVersionTable','StrictMode','?','OFS','ConfirmPreference','WarningPreference','DebugPreference','InformationPreference','PID','ShellId','StackTrace','PSCmdlet','switch','foreach','NestedPromptLevel','MaximumHistoryCount','PSCulture','PSUICulture','PSHOME','PSEdition'
$declared = @{}
foreach ($p in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.ParameterAst] }, $true)) { $declared[$p.Name.VariablePath.UserPath.ToLower()] = $true }
foreach ($f in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.ForEachStatementAst] }, $true)) { $declared[$f.Variable.VariablePath.UserPath.ToLower()] = $true }
if ($ast.ParamBlock) { foreach ($p in $ast.ParamBlock.Parameters) { $declared[$p.Name.VariablePath.UserPath.ToLower()] = $true } }
$assigns = @()
foreach ($a in $ast.FindAll({ param($n) $n -is $TAssign }, $true)) {
  foreach ($v in $a.Left.FindAll({ param($n) $n -is $TVar }, $true)) { $assigns += [pscustomobject]@{ Name = $v.VariablePath.UserPath.ToLower(); End = $a.Extent.EndOffset; Node = $a } }
}
# also treat [ref]$x / -OutVariable / catch / switch -regex style writes loosely: any variable under a [ref] cast counts as assigned
foreach ($c in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.ConvertExpressionAst] -and $n.Type.TypeName.Name -eq 'ref' }, $true)) {
  foreach ($v in $c.FindAll({ param($n) $n -is $TVar }, $true)) { $declared[$v.VariablePath.UserPath.ToLower()] = $true }
}
function Get-Scopes($node) {
  $s = @(); $p = $node.Parent
  while ($p) { if (($p -is [System.Management.Automation.Language.StatementBlockAst] -and -not ($p.Parent -is [System.Management.Automation.Language.TryStatementAst] -and $p.Parent.Body -eq $p)) -or $p -is [System.Management.Automation.Language.NamedBlockAst]) { $s += $p }; $p = $p.Parent }
  $s
}
function In-Function($node) { $p = $node.Parent; while ($p) { if ($p -is [System.Management.Automation.Language.FunctionDefinitionAst]) { return $true }; $p = $p.Parent }; $false }
$bad = [ordered]@{}
foreach ($v in $ast.FindAll({ param($n) $n -is $TVar }, $true)) {
  $name = $v.VariablePath.UserPath.ToLower()
  if ($v.VariablePath.IsDriveQualified -or $name.Contains(':')) { continue }
  if ($auto -contains $name -or $declared.ContainsKey($name)) { continue }
  # skip assignment targets themselves
  $p = $v.Parent; $isTarget = $false
  while ($p) { if ($p -is $TAssign -and $p.Left.Extent.StartOffset -le $v.Extent.StartOffset -and $v.Extent.EndOffset -le $p.Left.Extent.EndOffset) { $isTarget = $true; break }; if ($p -is [System.Management.Automation.Language.StatementAst]) { break }; $p = $p.Parent }
  if ($isTarget) { continue }
  if ($v.Splatted) { }
  $mine = $assigns | Where-Object { $_.Name -eq $name }
  if (In-Function $v) { if ($mine) { continue } }
  $scopes = Get-Scopes $v
  $ok = $false
  foreach ($m in $mine) {
    if ($m.End -gt $v.Extent.StartOffset) { continue }
    $ms = (Get-Scopes $m.Node)
    if ($ms.Count -eq 0 -or ($scopes -contains $ms[0])) { $ok = $true; break }
  }
  if (-not $ok) { if (-not $bad.Contains($name)) { $bad[$name] = $v.Extent.StartLineNumber } }
}
foreach ($k in $bad.Keys) { Write-Output "UNSET-READ `$$k line $($bad[$k])" }
if ($bad.Count) { exit 1 }
