# Acme Spend Watch

An Inspecto App made with the App kit (`node tools/inspecto-app.mjs new acme-spend`).

- Template `acme-spend`: the `acme_spend_events` feed, the `acme_spend_detect` Job, its findings in `acme_spend_findings`.
- Check it: `node tools/inspecto-app.mjs validate --app-root <this folder>`, then `test` (add `--bundle <a built bundle>` to boot one).
- Ship it: `node tools/inspecto-app.mjs package --app-root <this folder>`; build a bundle with it: `pwsh -File inspecto/package.ps1 -AppRoot <this folder> ...`.
