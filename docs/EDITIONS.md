# Inspecto editions — Basic, Standard and Advanced

Inspecto is one product sold in three editions. They run the same engine and give the same answers: an edition
never sells weaker analysis or weaker evidence. The editions differ by **who** uses Inspecto (one analyst, a team,
an organisation), **how much data** it handles, and **how much governance** surrounds it.

| Edition | For | In short |
|---|---|---|
| **Basic** | One analyst on one machine | Add data, explore it, find links. No sign-in. |
| **Standard** | A team | Everything in Basic, plus sign-in and roles, Investigations, Cases and Incidents, automatic data feeds, the Geo map and the Assistant. |
| **Advanced** | An organisation | Everything in Standard, plus Access Policies and shared PostgreSQL state for several servers. |

The three editions come from the same build, packaged differently. Your bundle names its edition in
`lib/edition.properties`.

## Inspecto LA (Link Analysis) by edition

| Area | Feature | Basic | Standard | Advanced |
|---|---|:-:|:-:|:-:|
| Data | *Add data*: upload a file, review its columns, get a Dataset | ✅ | ✅ | ✅ |
| | An import inbox: *Import waiting files*, or *Watch this folder* (checked every 30 seconds, off by default) | ✅ | ✅ | ✅ |
| | *Choose a table*: a PostgreSQL table copied as a snapshot, updated with *Refresh now* | ✅ | ✅ | ✅ |
| | *Keep this up to date* on a schedule you choose (a watched folder or an SFTP feed) | — | ✅ | ✅ |
| Explore | Graph studio, graphs from any Dataset, saved views | ✅ | ✅ | ✅ |
| | Find paths, neighbours, "Investigate a number", connect seeds | ✅ | ✅ | ✅ |
| | Temporal and branching patterns, pattern packs | ✅ | ✅ | ✅ |
| | Value Measures, graph algorithms in the browser | ✅ | ✅ | ✅ |
| | Entity Lists and identity resolution; reference data beside a node | ✅ | ✅ | ✅ |
| | Export (PNG, CSV) and snapshots (not evidence-grade) | ✅ | ✅ | ✅ |
| | Industry packs (Telecom, Mobile Money) | ✅ | ✅ | ✅ |
| | Geo map, routes and co-location | — | ✅ | ✅ |
| | Entity profile pages | — | ✅ | ✅ |
| | Link analysis and Geo map widgets on dashboards | — | ✅ | ✅ |
| Investigate | Investigations: a sealed history of every step, replay, compare, temporal findings | — | ✅ | ✅ |
| | Dossier and Dossier bundle, with verification | — | ✅ | ✅ |
| | Templates and external references | — | ✅ | ✅ |
| | Data trust: the data strip, Files, coverage and delivery Alerts | — | ✅ | ✅ |
| Team | Sign-in, members and roles, four-eyes approval, audited reveal of masked values | — | ✅ | ✅ |
| | Drafts: several analysts in parallel, rebase, promote | — | ✅ | ✅ |
| Share | Embed links (a read-only view of an Investigation) | — | ✅ | ✅ |
| | Case linking, and Case history beside a node | — | ✅ | ✅ |
| Scale | Graph runs on the server, the link index and daily ingestion | — | ✅ | ✅ |
| Operate | Alert Rules on Investigations and standing detection | — | ✅ | ✅ |
| | Assistant: AI-proposed column mappings | — | ✅ | ✅ |
| Integrate | Third-party integration (case managers, fraud products) | — | ✅ | ✅ |
| Govern | Investigations stored on PostgreSQL, shared by several servers | — | — | ✅ |
| | Access Policies (attribute-based; they can only narrow what roles allow) | — | — | ✅ |
| All | Inspecto LA as its own application, or inside the Inspecto console | ✅ | ✅ | ✅ |

Rows from *Investigate* down need the **Investigations add-on** (see below). A feature your edition does not
include is shown locked, with the reason, rather than hidden behind an error. On Basic, the Alerts area of the
Inspecto LA application is not shown, and "Investigate a number" opens the number as a graph instead of starting
an Investigation.

## The data layer by edition

| Data layer | Basic | Standard | Advanced |
|---|:-:|:-:|:-:|
| *Add data*: upload, an import inbox (*Watch this folder*, 30 seconds), a PostgreSQL snapshot (*Refresh now*) | ✅ | ✅ | ✅ |
| Column review: names, types, column roles (Entity, Time, Measure, Category, Detail), Entity Types | ✅ | ✅ | ✅ |
| A row filter on the review step, and *Edit filter* on the Dataset card (applies to the next files) | ✅ | ✅ | ✅ |
| Expectations, quarantine and duplicate removal, checked in the import's own run | ✅ | ✅ | ✅ |
| The arrivals list, column name checks, duplicate refusal, *Replace the period*, compaction (automatic, and *Compact now*) | ✅ | ✅ | ✅ |
| A later file's new columns kept as details (*Keep new columns as details*) | ✅ | ✅ | ✅ |
| Discover, the entity summary, the query builder and preset questions in Inspecto LA | ✅ | ✅ | ✅ |
| A retention policy on an import Dataset (*keep N arrivals*) | — every file is kept, compacted | ✅ | ✅ |
| *Keep this up to date*: a watched folder or SFTP feed on a schedule | locked | ✅ | ✅ |
| *Add data* from another database or a remote system | locked | through a Pipeline | through a Pipeline |
| Enrichment on the Dataset page (*Add the details from X to each row*) | locked (join in a Studio query instead) | ✅ | ✅ |

**The locks above are in the screens, not the engine.** None of them makes Basic refuse a Pipeline or Collector
you bring. Steps whose module Basic does not include (for example e-mail and webhook delivery) are not available
there, and three things are refused on Basic when the configuration loads: the PII masking Step, DuckLake
registration and the compliance archive. Alert Rules on Basic are kept but not armed.

## Security and deployment by edition

| | Basic | Standard | Advanced |
|---|---|---|---|
| Sign-in | None: whoever can reach the server can use it | Your OpenID Connect identity provider (for example Keycloak or WSO2); Inspecto checks its tokens | As Standard |
| Who may do what | Everyone may do everything | Roles, from your identity provider's claims | Roles, plus Access Policies down to single rows, and per-Space isolation |
| HTTPS | Plain HTTP by default; HTTPS when you set `HTTPS_KEYSTORE` (a PKCS12 file) and `HTTPS_KEYSTORE_PASSWORD` | As Basic | As Basic, or end TLS at your load balancer |
| Secrets in configuration | `${ENV:…}` and `${SYS:…}` references | Also `${FILE:…}` (a mounted secret file) and `${KEYSTORE:…}` | As Standard |
| Audit trail | Kept on disk and readable in every edition; entries name no person, because nobody signs in | Every entry names the signed-in person | As Standard |
| State | DuckDB files on the local disk | Local disk, or PostgreSQL (the driver is in the bundle) | Incidents, Cases, notes, links and tags on PostgreSQL (required); Investigations on PostgreSQL when you choose it |
| Scheduler | Inside the server | Inside the server | Inside each server |

The launchers in `bin/` set the edition's sign-in mode for you. The [Advanced guide](ADVANCED_GUIDE.md) lists every
start-up setting, and [the API documentation](api/) describes the deployment blueprints for an identity provider.

> ### Listen address: read this before running Basic on a shared network
>
> The server listens on **every network interface** by default, in every edition. Set
> `-Dcontrol.bind=127.0.0.1` to listen on this machine only, or a host name or address of your choice; a value that
> does not resolve stops the server from starting rather than falling back to a wider address.
>
> **This matters most on Basic, which has no sign-in.** Left on the default, a Basic server offers its whole
> control plane, including the screens and calls that change configuration, to every computer that can reach the
> port. For a single-user install, set `-Dcontrol.bind=127.0.0.1` or block the port with a firewall.

## Masking on Basic

On Basic, masking is **off by default**. Wherever people sign in (Standard and Advanced), the shipped masking
mode `typed` applies, with or without the Investigations add-on.

A Space may state its own masking mode, and that mode is kept in every edition. On Basic it is **display only**:
because nobody signs in, anyone can reveal a masked value. It stops a casual glance; it is not access control.
Because a Space has one masking mode, Basic's dashboards, queries and exports are unmasked by default too. The
exception is a public dashboard share, whose viewer is anonymous and cannot reveal: it is masked with `typed` unless
the Space states a mode. Enforced masking, and the audited reveal, start in Standard.

## The Investigations add-on

Investigations are a billable add-on, **included by default** in Standard and Advanced. They cover everything in
the Inspecto LA table from *Investigate* down: Investigations themselves, Dossiers, drafts, sharing, server-side
graph runs and standing detection.

An installation that does not hold the add-on runs Inspecto LA at the **Explorer** level, as Basic does. The
analysis toolbox then shows only its *On screen* tab, and the other Investigation actions are shown locked with
the reason. Sign-in, roles and enforced masking do not change: those come with the edition, not the add-on. To
run without the add-on, start the server with `-Daddons.laInvestigations=false` (for example in
`INSPECTO_JAVA_OPTS`). Basic never includes the add-on, whatever this setting says.

## Upgrading

**A Basic install upgrading from an earlier version loses four features**, which are now Standard and above.
There is no migration; to keep them, move to Standard.

- **Case history** beside a node.
- **The Geo map**, routes and co-location. The rest of Inspecto LA keeps working, and a Space can still switch all
  of Inspecto LA off as before.
- **Entity profile pages.** The Entity Type list and finding an entity keep working.
- **Adding Link analysis and Geo map widgets** to dashboards. A widget saved earlier still opens.

**The data layer (2026-11-06):**

- **New in every edition:** the three ways into *Add data* (upload, an import inbox with *Watch this folder*, a
  PostgreSQL table snapshot with *Refresh now*), column roles, row filtering and *Edit filter*, the arrivals list
  with compaction, a later file's new columns kept as details, and Discover, the entity summary and the query
  builder in Inspecto LA.
- **Standard and above:** *Keep this up to date* and a retention policy now work on a Standard install (before
  this release they were refused). An import's link index is still built in the person's own run.
- **Basic:** *Keep this up to date* is locked with a reason, and so are another database or a remote system in
  *Add data* and enrichment on the Dataset page. The retention form is not shown: every file is kept, compacted.
  A PostgreSQL snapshot is updated with *Refresh now* only; the 30-second refresh it had briefly is gone.
- **Nothing breaks on load.** A Basic install keeps running the configuration it has. The earlier refusals (PII
  masking, DuckLake, the compliance archive) are unchanged.

## More

- [User guide](USER_GUIDE.md): how to use Inspecto, screen by screen.
- [Advanced guide](ADVANCED_GUIDE.md): operations, start-up settings and troubleshooting.
- [API documentation](api/): the HTTP contract and deployment blueprints.
