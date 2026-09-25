# package-spaces.ps1 — stage the bundled spaces/ tree from COMMITTED content only.
#
# Dot-sourced by package.ps1 (step 4); also callable on its own so the staging can be tested
# without running a whole package build (tools/check-bundle-spaces.mjs does exactly that).
#
# WHY (BUNDLE-UNTRACKED-SPACES-1, 2026-09-25). Step 4 used to enumerate `Get-ChildItem spaces/` in
# the WORKING TREE and copy everything but a skip list — so a bundle built from the shared sandbox
# checkout shipped a git-EXCLUDED client/RFP working set (`telco-assurance`) and a peer session's
# UNTRACKED pilot (`cricket-analytics`) next to the intended samples. The working tree is not the
# artifact; the commit is. Client/project material must never leave the checkout.
#
# WHAT SHIPS. Every file under spaces/ in `git ls-tree HEAD`, minus the unchanged skip rules:
#   · top level: `uat` (generated clone, tools/seed-uat.ps1) and `_shared` (Exchange runtime ledgers);
#   · per Space: `audit`, `duckdb`, `flows`, `views` (runtime state), and under `data/` only `data/samples/`.
#
# ⚖ COMMITTED BLOB, NOT THE WORKING COPY. A tracked file with local edits ships as HEAD has it, and
# a file only `git add`ed (in the index, not yet committed) does not ship at all — so a bundle's
# Space content is reproducible from the commit it was built at. The files are written by
# `git checkout-index` from a throwaway index loaded with HEAD, so .gitattributes eol rules apply
# exactly as in a fresh checkout. Locally modified files are named in a warning, never silently mixed.
#
# GIT IS REQUIRED. There is no directory-listing fallback: falling back is precisely the leak this
# replaces. No git, or not a git work tree → throw.

function Copy-TrackedSpaces {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,   # the git work tree holding spaces/
        [Parameter(Mandatory)][string]$BundleDir   # spaces/ is written to $BundleDir/spaces
    )
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "package: git is required to stage spaces/ from committed content (BUNDLE-UNTRACKED-SPACES-1) — not found on PATH"
    }
    $inside = & git -C $RepoRoot rev-parse --is-inside-work-tree 2>$null
    if ($LASTEXITCODE -ne 0 -or $inside -ne 'true') {
        throw "package: $RepoRoot is not a git work tree — spaces/ can only be staged from committed content (BUNDLE-UNTRACKED-SPACES-1)"
    }

    $skipTop = @('uat', '_shared')
    $skipGen = @('audit', 'duckdb', 'flows', 'views')

    $committed = @(& git -C $RepoRoot -c core.quotePath=false ls-tree -r --name-only HEAD -- spaces)
    if ($LASTEXITCODE -ne 0) { throw "package: git ls-tree HEAD -- spaces failed" }

    $keep = [System.Collections.Generic.List[string]]::new()
    foreach ($p in $committed) {
        $parts = $p.Split('/')
        if ($parts.Count -ge 3) {
            if ($skipTop -contains $parts[1]) { continue }
            if ($skipGen -contains $parts[2]) { continue }
            if ($parts[2] -eq 'data' -and ($parts.Count -lt 5 -or $parts[3] -ne 'samples')) { continue }
        }
        $keep.Add($p)
    }

    $spaces = @($keep | Where-Object { $_.Split('/').Count -ge 3 } |
                ForEach-Object { $_.Split('/')[1] } | Sort-Object -Unique)

    # Working-tree Space dirs with NO committed file: untracked or git-excluded. Named, never copied.
    $committedTop = @($committed | Where-Object { $_.Split('/').Count -ge 3 } |
                      ForEach-Object { $_.Split('/')[1] } | Sort-Object -Unique)
    $spacesDir = Join-Path $RepoRoot 'spaces'
    $untracked = @()
    if (Test-Path $spacesDir) {
        $untracked = @(Get-ChildItem -Path $spacesDir -Directory -Force |
                       Where-Object { $skipTop -notcontains $_.Name -and $committedTop -notcontains $_.Name } |
                       ForEach-Object Name | Sort-Object)
    }

    # Tracked files whose working copy differs from HEAD — the committed version is what ships.
    $keepSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$keep)
    $modified = @(& git -C $RepoRoot -c core.quotePath=false diff --name-only HEAD -- spaces |
                  Where-Object { $keepSet.Contains($_) })

    $out = Join-Path $BundleDir 'spaces'
    $null = New-Item -ItemType Directory $out -Force
    if ($keep.Count -gt 0) {
        $prefix = ((Resolve-Path $BundleDir).Path -replace '\\', '/').TrimEnd('/') + '/'
        $tmpIndex = Join-Path ([System.IO.Path]::GetTempPath()) ("inspecto-spaces-" + [guid]::NewGuid() + ".index")
        $savedIndex = $env:GIT_INDEX_FILE
        try {
            $env:GIT_INDEX_FILE = $tmpIndex
            & git -C $RepoRoot read-tree HEAD
            if ($LASTEXITCODE -ne 0) { throw "package: git read-tree HEAD failed" }
            # Paths as ARGUMENTS, batched under the Windows command-line limit — not `--stdin`: a
            # PowerShell pipe to a native command on Windows ends each line with CRLF, and
            # checkout-index then reports every path "not in the cache".
            for ($i = 0; $i -lt $keep.Count; $i += 100) {
                $batch = $keep.GetRange($i, [Math]::Min(100, $keep.Count - $i))
                & git -C $RepoRoot checkout-index -f "--prefix=$prefix" -- @batch
                if ($LASTEXITCODE -ne 0) { throw "package: git checkout-index of spaces/ failed" }
            }
        } finally {
            if ($null -eq $savedIndex) { Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue }
            else { $env:GIT_INDEX_FILE = $savedIndex }
            Remove-Item $tmpIndex -Force -ErrorAction SilentlyContinue
        }
    }

    [pscustomobject]@{
        Out       = $out
        Spaces    = $spaces
        Files     = $keep.Count
        Untracked = $untracked
        Modified  = $modified
    }
}
