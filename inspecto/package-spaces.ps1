# package-spaces.ps1 — stage the bundled spaces/ tree: ONLY spaces/_templates, from COMMITTED content.
#
# Dot-sourced by package.ps1 (step 4); also callable on its own so the staging can be tested
# without running a whole package build (tools/check-bundle-spaces.mjs does exactly that).
#
# BUNDLES SHIP NO SPACES (operator decision 2026-09-25). Every edition's bundle carries the Space-template
# gallery (spaces/_templates — an underscore sentinel, never booted as a Space) and nothing else under
# spaces/. Spaces are ATTACHED at deploy time: drop the Space folder(s) into the bundle's spaces/ directory
# (or point SPACES_ROOT / -Dspaces.root at a folder holding them), or create one in Settings -> Spaces.
# Until then every edition shipped the committed sample Spaces (default, demo, ucc), and a hand-over built
# as "the bundle plus ONE Space folder" opened the sample `default` Space instead of the one dropped in
# beside it (DEMO-AUTH-1, 2026-09-25 rehearsal).
#
# WHY COMMITTED CONTENT (BUNDLE-UNTRACKED-SPACES-1, 2026-09-25). Step 4 once enumerated `Get-ChildItem
# spaces/` in the WORKING TREE, so a bundle built from the shared sandbox checkout shipped a git-EXCLUDED
# client/RFP working set and a peer session's UNTRACKED pilot. The working tree is not the artifact; the
# commit is. Client/project material must never leave the checkout.
#
# WHAT SHIPS. Every file under spaces/_templates/ in `git ls-tree HEAD`. Every other top-level entry under
# spaces/ — committed or only in the working tree — is left out and NAMED in the result (NotBundled).
#
# ⚖ COMMITTED BLOB, NOT THE WORKING COPY. A tracked file with local edits ships as HEAD has it, and
# a file only `git add`ed (in the index, not yet committed) does not ship at all — so a bundle's
# template content is reproducible from the commit it was built at. The files are written by
# `git checkout-index` from a throwaway index loaded with HEAD, so .gitattributes eol rules apply
# exactly as in a fresh checkout. Locally modified files are named in a warning, never silently mixed.
#
# GIT IS REQUIRED. There is no directory-listing fallback: falling back is precisely the leak this
# replaces. No git, or not a git work tree → throw.

function Copy-SpaceTemplates {
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

    $committed = @(& git -C $RepoRoot -c core.quotePath=false ls-tree -r --name-only HEAD -- spaces)
    if ($LASTEXITCODE -ne 0) { throw "package: git ls-tree HEAD -- spaces failed" }

    $keep = [System.Collections.Generic.List[string]]::new()
    foreach ($p in $committed) {
        if ($p.StartsWith('spaces/_templates/')) { $keep.Add($p) }
    }

    # The template names: spaces/_templates/<name>/...
    $templates = @($keep | Where-Object { $_.Split('/').Count -ge 4 } |
                   ForEach-Object { $_.Split('/')[2] } | Sort-Object -Unique)

    # Every other top-level entry under spaces/, committed or only in the working tree. Named, never copied.
    $notBundled = @($committed | Where-Object { -not $_.StartsWith('spaces/_templates/') } |
                    ForEach-Object { $_.Split('/')[1] })
    $spacesDir = Join-Path $RepoRoot 'spaces'
    if (Test-Path $spacesDir) {
        $notBundled += @(Get-ChildItem -Path $spacesDir -Force |
                         Where-Object { $_.Name -ne '_templates' } | ForEach-Object Name)
    }
    $notBundled = @($notBundled | Sort-Object -Unique)

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
        Out        = $out
        Templates  = $templates
        Files      = $keep.Count
        NotBundled = $notBundled
        Modified   = $modified
    }
}
