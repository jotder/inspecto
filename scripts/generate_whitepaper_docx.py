#!/usr/bin/env python3
"""
Generates the Inspecto Enterprise Whitepaper as a professionally formatted Word document (.docx).
Updated to include Page 6: Embedded Intelligence & The Governed Autonomy Ladder (Local Air-Gapped AI Agent).
Uses python-docx with custom typography, tables, callout blocks, headers, footers, and page breaks.
"""

import sys
from pathlib import Path
import docx
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import parse_xml
from docx.oxml.ns import nsdecls

def set_cell_background(cell, fill_hex):
    tcPr = cell._tc.get_or_add_tcPr()
    tcPr.append(parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>'))

def set_cell_margins(cell, top=100, bottom=100, left=150, right=150):
    tcPr = cell._tc.get_or_add_tcPr()
    tcMar = parse_xml(f'<w:tcMar {nsdecls("w")}>'
                      f'<w:top w:w="{top}" w:type="dxa"/>'
                      f'<w:bottom w:w="{bottom}" w:type="dxa"/>'
                      f'<w:left w:w="{left}" w:type="dxa"/>'
                      f'<w:right w:w="{right}" w:type="dxa"/>'
                      f'</w:tcMar>')
    tcPr.append(tcMar)

def set_cell_borders(cell, top=None, bottom=None, left=None, right=None):
    tcPr = cell._tc.get_or_add_tcPr()
    borders_elm = parse_xml(f'<w:tcBorders {nsdecls("w")}/>')
    sides = {'top': top, 'bottom': bottom, 'left': left, 'right': right}
    for side, border in sides.items():
        if border:
            val, sz, color = border
            tag = f'<w:{side} {nsdecls("w")} w:val="{val}" w:sz="{sz}" w:space="0" w:color="{color}"/>'
            borders_elm.append(parse_xml(tag))
        else:
            tag = f'<w:{side} {nsdecls("w")} w:val="none"/>'
            borders_elm.append(parse_xml(tag))
    tcPr.append(borders_elm)

def format_table(table, col_widths, hdr_bg="1E3A8A", alt_bg="F8FAFC"):
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    # Format header
    for cell in table.rows[0].cells:
        set_cell_background(cell, hdr_bg)
        set_cell_margins(cell, top=120, bottom=120, left=160, right=160)
        set_cell_borders(cell, bottom=("single", "12", "0F172A"))
        for p in cell.paragraphs:
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            for r in p.runs:
                r.bold = True
                r.font.color.rgb = RGBColor(255, 255, 255)
                r.font.name = "Calibri"
                r.font.size = Pt(9.5)

    # Format data rows
    for r_idx, row in enumerate(table.rows[1:]):
        bg = alt_bg if r_idx % 2 == 1 else "FFFFFF"
        for cell in row.cells:
            set_cell_background(cell, bg)
            set_cell_margins(cell, top=90, bottom=90, left=150, right=150)
            set_cell_borders(cell, bottom=("single", "4", "E2E8F0"), top=("none", "0", "auto"))
            for p in cell.paragraphs:
                for r in p.runs:
                    r.font.name = "Calibri"
                    r.font.size = Pt(9.0)

    # Set column widths
    for row in table.rows:
        for i, w in enumerate(col_widths):
            row.cells[i].width = Inches(w)

def add_callout(doc, text_paragraphs, border_color="2563EB", bg_color="F1F5F9"):
    tbl = doc.add_table(rows=1, cols=1)
    tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = tbl.cell(0, 0)
    cell.width = Inches(6.8)
    set_cell_background(cell, bg_color)
    set_cell_margins(cell, top=140, bottom=140, left=200, right=180)
    set_cell_borders(cell, left=("single", "32", border_color),
                           top=("none", "0", "auto"),
                           bottom=("none", "0", "auto"),
                           right=("none", "0", "auto"))
    cell.text = ""
    for idx, (p_text, bold_prefix) in enumerate(text_paragraphs):
        p = cell.add_paragraph() if idx > 0 else cell.paragraphs[0]
        p.paragraph_format.space_before = Pt(2)
        p.paragraph_format.space_after = Pt(2)
        p.paragraph_format.line_spacing = 1.15
        if bold_prefix:
            r_bold = p.add_run(bold_prefix)
            r_bold.bold = True
            r_bold.font.name = "Calibri"
            r_bold.font.size = Pt(9.5)
            r_bold.font.color.rgb = RGBColor(15, 23, 42)
        r_body = p.add_run(p_text)
        r_body.font.name = "Calibri"
        r_body.font.size = Pt(9.5)
        r_body.font.color.rgb = RGBColor(30, 41, 59)
    doc.add_paragraph()

def add_code_block(doc, ascii_art):
    tbl = doc.add_table(rows=1, cols=1)
    tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = tbl.cell(0, 0)
    cell.width = Inches(6.8)
    set_cell_background(cell, "0F172A")
    set_cell_margins(cell, top=120, bottom=120, left=160, right=160)
    set_cell_borders(cell, left=("single", "12", "38BDF8"),
                           top=("single", "4", "334155"),
                           bottom=("single", "4", "334155"),
                           right=("single", "4", "334155"))
    cell.text = ""
    p = cell.paragraphs[0]
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(0)
    p.paragraph_format.line_spacing = 1.05
    run = p.add_run(ascii_art.strip("\n"))
    run.font.name = "Consolas"
    run.font.size = Pt(8.0)
    run.font.color.rgb = RGBColor(241, 245, 249)
    doc.add_paragraph()

def build_whitepaper_docx(output_path: Path):
    doc = Document()

    # Configure Margins
    for s in doc.sections:
        s.top_margin = Inches(0.75)
        s.bottom_margin = Inches(0.75)
        s.left_margin = Inches(0.85)
        s.right_margin = Inches(0.85)
        
        # Header & Footer
        footer = s.footer
        p_ft = footer.paragraphs[0]
        p_ft.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        r_ft = p_ft.add_run("Inspecto — Sovereign Data Operations Platform | Confidential")
        r_ft.font.name = "Calibri"
        r_ft.font.size = Pt(8.0)
        r_ft.font.color.rgb = RGBColor(148, 163, 184)

    # Colors Setup
    NAVY = RGBColor(30, 58, 138)       # #1E3A8A
    SLATE = RGBColor(15, 23, 42)       # #0F172A
    MUTED = RGBColor(71, 85, 105)      # #475569
    BLUE = RGBColor(37, 99, 235)       # #2563EB

    def add_page_header(page_num, title, subtitle=None):
        p_pg = doc.add_paragraph()
        p_pg.paragraph_format.space_before = Pt(0)
        p_pg.paragraph_format.space_after = Pt(2)
        r_pg = p_pg.add_run(f"PAGE {page_num} OF 10 • {title.upper()}")
        r_pg.font.name = "Calibri"
        r_pg.font.size = Pt(8.5)
        r_pg.bold = True
        r_pg.font.color.rgb = BLUE

        p_h1 = doc.add_heading(level=1)
        p_h1.paragraph_format.space_before = Pt(0)
        p_h1.paragraph_format.space_after = Pt(4)
        r_h1 = p_h1.add_run(title)
        r_h1.font.name = "Calibri"
        r_h1.font.size = Pt(17)
        r_h1.bold = True
        r_h1.font.color.rgb = NAVY

        if subtitle:
            p_sub = doc.add_paragraph()
            p_sub.paragraph_format.space_before = Pt(0)
            p_sub.paragraph_format.space_after = Pt(10)
            r_sub = p_sub.add_run(subtitle)
            r_sub.font.name = "Calibri"
            r_sub.font.size = Pt(10.5)
            r_sub.italic = True
            r_sub.font.color.rgb = MUTED

    def add_h2(title):
        p = doc.add_heading(level=2)
        p.paragraph_format.space_before = Pt(8)
        p.paragraph_format.space_after = Pt(3)
        r = p.add_run(title)
        r.font.name = "Calibri"
        r.font.size = Pt(12.5)
        r.bold = True
        r.font.color.rgb = SLATE

    def add_p(text, bold_prefix=None, italic=False, space_after=6):
        p = doc.add_paragraph()
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(space_after)
        p.paragraph_format.line_spacing = 1.15
        if bold_prefix:
            r_b = p.add_run(bold_prefix)
            r_b.bold = True
            r_b.font.name = "Calibri"
            r_b.font.size = Pt(10)
            r_b.font.color.rgb = SLATE
        r = p.add_run(text)
        r.font.name = "Calibri"
        r.font.size = Pt(10)
        r.italic = italic
        r.font.color.rgb = SLATE
        return p

    def add_bullet(text, bold_prefix=None):
        p = doc.add_paragraph(style='List Bullet')
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(3)
        p.paragraph_format.line_spacing = 1.15
        if bold_prefix:
            r_b = p.add_run(bold_prefix)
            r_b.bold = True
            r_b.font.name = "Calibri"
            r_b.font.size = Pt(9.5)
            r_b.font.color.rgb = SLATE
        r = p.add_run(text)
        r.font.name = "Calibri"
        r.font.size = Pt(9.5)
        r.font.color.rgb = SLATE

    # =========================================================================
    # PAGE 1: Executive Summary & The Problem Statement
    # =========================================================================
    add_page_header(1, "Executive Summary & The Problem Statement", 
                    "The Crisis of the Heavyweight Multi-Vendor Data Stack")

    add_p("Inspecto is a lean, configuration-driven data acquisition, management, reconciliation, business intelligence (BI), embedded AI, and forensic investigation platform delivered as a single ~90 MB self-contained artifact. It operates seamlessly on a commodity laptop, an air-gapped bare-metal server, or a standard container—with zero external runtime dependencies below Enterprise.",
          bold_prefix="Platform Overview: ")

    add_p("By embedding a vectorized columnar database engine (DuckDB) directly over an open Parquet lakehouse, Inspecto collapses four traditionally fragmented enterprise tool categories—ETL/ingest pipelines, data lakehouse storage, self-service BI, and operational incident workflows—into a unified operational fabric. Built specifically for regulated, sovereign, and air-gapped environments where cloud-SaaS tools and commercial cloud LLMs are legally or structurally disqualified, Inspecto enables organizations to ingest complex binary formats, detect reconciliation breaks, maintain an immutable audit trail, and investigate fraud without a distributed cluster.")

    add_code_block(doc, """
                      THE DISTRIBUTED CLUSTER TAX
 ┌─────────────────────────────────────────────────────────────────────────┐
 │ Ingest (NiFi/Airbyte) ──► Storage (Hadoop/S3) ──► Quality (Great Expect)│
 │           │                        │                         │          │
 │     [Glue Script]            [Glue Script]             [Glue Script]    │
 │           ▼                        ▼                         ▼          │
 │ BI (Superset/Tableau) ──► Alerting (PagerDuty) ──► Ticketing (ServiceNow│
 └─────────────────────────────────────────────────────────────────────────┘
   • 5 to 15 Virtual Machines           • High Serialization / Shuffle Lag
   • Thousands of Dependencies (CVEs)   • Stale Dashboards & Broken Lineage
   • Unsafe Cloud LLM Egress            • Fragile Glue Code Between Silos
                                     VS
 ┌─────────────────────────────────────────────────────────────────────────┐
 │                      INSPECTO UNIFIED ARTIFACT                          │
 │  Acquire ──► Vectorized Lakehouse ──► Reconcile ──► Breaks ──► AI Agent   │
 └─────────────────────────────────────────────────────────────────────────┘
   • One ~90 MB Executable              • 523,000+ Rows/Sec Single-Node
   • Zero External Runtime Services     • 100% Air-Gapped / Zero Cloud Egress
   • Embedded Sovereign Intelligence    • Governed Autonomy with Human Approval
""")

    add_h2("The Three Industry Failures Inspecto Solves")
    add_bullet(" Connecting ingestion tools (NiFi, Airbyte) to storage, data quality engines (Great Expectations, Collibra), and ticketing systems (Jira, ServiceNow) requires brittle Python scripts. When an ingest job fails or an upstream partner drifts a schema, the seam breaks silently. Inspecto unifies the end-to-end lifecycle under one declarative configuration.",
               bold_prefix="1. The 'Glue Code' Liability:")
    add_bullet(" Leading data observability, analytics, and AI platforms (Snowflake, Datadog, OpenAI, Monte Carlo) are cloud-first SaaS platforms. Defense agencies, central banks, and telco operators operating under strict data-residency laws or air-gaps cannot legally use them. Inspecto operates 100% offline with zero outbound calls.",
               bold_prefix="2. The Cloud & SaaS Egress Exclusion:")
    add_bullet(" Running distributed compute clusters (Spark, Kafka, Flink) for feeds under 2 billion records/day wastes 70–80% of compute on network serialization, JVM GC pauses, and coordination overhead. Inspecto processes up to 1 billion 100-column rows per day on a single 8-core node.",
               bold_prefix="3. The Distributed Cluster Tax:")

    # =========================================================================
    # PAGE 2: System Architecture
    # =========================================================================
    doc.add_page_break()
    add_page_header(2, "System Architecture", "Collapsing the Enterprise Stack Across the Seams")

    add_code_block(doc, """
  ┌───────────────────────────────────────────────────────────────────────┐
  │                         OPERATOR CONSOLE                              │
  │     Business Lens       │     Builder Lens      │     Ops Lens        │
  └───────────┬─────────────────────────┬─────────────────────┬───────────┘
              │                         │                     │
  ┌───────────▼─────────────────────────▼─────────────────────▼───────────┐
  │                    CONTROL PLANE & REST API (/api/v1)                 │
  │   OIDC / OAuth2 SSO   │   RBAC / ABAC Policies   │  Single-Seam Audit │
  └───────────┬───────────────────────────────────────────────┬───────────┘
              │                                               │
  ┌───────────▼───────────────────────────────────────────────▼───────────┐
  │                           DATA PLANE                                  │
  │ ┌────────────────────────┐                   ┌──────────────────────┐ │
  │ │ ACQUISITION & PARSING  │                   │ RECONCILIATION & OPS │ │
  │ │ • SFTP / S3 / DB / GCS │                   │ • Dataset vs Dataset │ │
  │ │ • ASN.1 (CDRs)         │                   │ • Automated Breaks   │ │
  │ │ • Fixed-Width / CSV    │                   │ • Incidents & Cases  │ │
  │ └───────────┬────────────┘                   └──────────▲───────────┘ │
  │             │                                           │             │
  │ ┌───────────▼───────────────────────────────────────────┴───────────┐ │
  │ │             VECTORIZED PARQUET LAKEHOUSE (DuckDB Engine)          │ │
  │ │  • In-Memory Columnar Transforms    • Hive Partitioning           │ │
  │ │  • SQL Sandbox & Query Library      • Studio BI & Visualizations  │ │
  │ └───────────────────────────────────┬───────────────────────────────┘ │
  └─────────────────────────────────────┼─────────────────────────────────┘
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │         EMBEDDED INTELLIGENCE LAYER (Local / Air-Gapped)              │
  │  Reflex Skills (7)  │  Governed Autonomy (L0-L3)  │  Approvals Inbox  │
  └───────────────────────────────────────────────────────────────────────┘
""")

    add_h2("The Five Integrated Architectural Layers")
    add_bullet(" Automated polling, watermark gap detection, deduplication, and streaming decompression across SFTP, FTPS, S3, GCS, and JDBC databases.", bold_prefix="1. Acquisition & Ingestion:")
    add_bullet(" Columnar in-memory execution paired with Hive-partitioned Parquet storage, executing analytical SQL directly on local disk.", bold_prefix="2. Vectorized Lakehouse:")
    add_bullet(" Rule-based and schema-level validation comparing disparate feeds to catch financial or operational discrepancies at the source.", bold_prefix="3. Reconciliation & Quality:")
    add_bullet(" An integrated operational incident system with root-cause analysis, forensic Link Analysis, and offline Geo Mapping.", bold_prefix="4. Operations & Investigation:")
    add_bullet(" An in-process, air-gapped AI agent with a governed autonomy ladder, providing failure diagnostics, automated root-cause analysis, and human-gated remediation.", bold_prefix="5. Embedded Sovereign Intelligence:")

    add_callout(doc, [
        ("In legacy multi-vendor stacks, an ingestion failure requires custom code to alert Jira, leaving downstream dashboards stale. In Inspecto, a collection gap or reconciliation break immediately flags affected Dashboard Tiles as stale, emits a tracked Signal, and opens an Incident with a threaded causationId—preserving complete audit and execution context.", 
         "The Power of the Architectural Seam: ")
    ])

    # =========================================================================
    # PAGE 3: Acquisition & The Vectorized Lakehouse
    # =========================================================================
    doc.add_page_break()
    add_page_header(3, "Acquisition & The Vectorized Lakehouse", "High-Speed Ingestion of Complex Formats & Analytical Storage")

    add_code_block(doc, """
  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
  │  Source Feeds   │      │ Native Parser   │      │ PartitionWriter │
  │  • Raw CDRs     ├─────►│  • C++ Zero-Copy├─────►│  • Stage & Reveal│
  │  • Delimited    │      │  • Vector Cast  │      │  • Hive Layout  │
  │  • Binary/Fixed │      │  • Quarantine   │      │  • Manifest Commit
  └─────────────────┘      └─────────────────┘      └────────┬────────┘
                                                             ▼
                                                    ┌─────────────────┐
                                                    │ Parquet Lakehouse│
                                                    │  (Local Disk)   │
                                                    └─────────────────┘
""")

    add_h2("1. File-Native Acquisition of Complex Formats")
    add_p("Inspecto is purpose-built for proprietary, binary, and uncooperative file formats that generic cloud connectors cannot process:")
    add_bullet(" Built-in 154-file decoder subsystem with vendor corpora, enabling direct ingestion of raw Call Detail Records (CDRs) from major switch manufacturers without external pre-processors.", bold_prefix="Telecom-Grade ASN.1 Decoding:")
    add_bullet(" High-performance binary and text layout parsers utilizing memory-mapped buffers for sub-microsecond cell decoding.", bold_prefix="Fixed-Width & Binary Parsing:")
    add_bullet(" Fully supported connectors for SFTP, FTPS, FTP, AWS S3, Google Cloud Storage (GCS), and relational databases with watermark tracking and retry backoff.", bold_prefix="Multi-Protocol Connectors:")
    add_bullet(" Malformed headers, unreadable encodings, and schema mismatches are segregated into dedicated error ledgers (errors/<file>_errors.csv) and quarantined. Healthy rows continue uninterrupted.", bold_prefix="Automated Quarantine:")

    add_h2("2. The Vectorized Lakehouse Architecture")
    add_bullet(" Executes vectorized, SIMD-accelerated SQL queries directly against columnar Parquet files with zero external database processes.", bold_prefix="Embedded DuckDB Engine:")
    add_bullet(" Reads Parquet pages straight into memory, eliminating JVM object wrapping and garbage collection overhead.", bold_prefix="Zero-Copy Memory Mapping:")
    add_bullet(" Automatically arranges landed data into date- or key-partitioned Parquet files (year=YYYY/month=MM/day=DD/), optimizing partition pruning.", bold_prefix="Partitioned Hive Layout:")
    add_bullet(" Staged in working directories and revealed via atomic filesystem renames, preventing dirty reads by concurrent BI dashboards.", bold_prefix="Atomic Staging & Reveal:")

    # =========================================================================
    # PAGE 4: Reconciliation to Breaks to Incidents
    # =========================================================================
    doc.add_page_break()
    add_page_header(4, "Reconciliation to Breaks to Incidents", "Automated Revenue Assurance & Operational Discrepancy Workflows")

    add_code_block(doc, """
  Source Feed A ──┐
                  ├──► [Reconciliation Engine] ──► [Discrepancy?]
  Source Feed B ──┘            ▲                          │
                               │                          ▼ (YES)
                       [Match Rules / Keys]       ┌──────────────────┐
                                                  │ Automated Break  │
                                                  └────────┬─────────┘
                                                           ▼
                                                  ┌──────────────────┐
                                                  │  Tracked Incident│
                                                  │  • Root Cause ID │
                                                  │  • SLA Deadline  │
                                                  │  • Assignee / LOB│
                                                  └──────────────────┘
""")

    add_h2("1. Dataset-vs-Dataset Reconciliation")
    add_p("Identifying variances between source generation feeds (e.g., network switch logs) and target systems (e.g., billing registers) is essential in banking and telecoms:")
    add_bullet(" Define multi-column join keys, tolerance bands (percentage or absolute monetary value), and 1-to-1, 1-to-many, or many-to-many matching rules.", bold_prefix="Declarative Matching Rules:")
    add_bullet(" Leverages vectorised SQL execution to perform cross-dataset joins and delta calculations over millions of records in seconds.", bold_prefix="High-Speed Execution:")

    add_h2("2. The Stateful Break Lifecycle")
    add_bullet(" Discrepancies generate persistent Break records capturing exact source rows, variance magnitude, and detection timestamps.", bold_prefix="Automated Break Creation:")
    add_bullet(" If an upstream timing difference resolves itself in the subsequent batch, Inspecto automatically reconciles and closes the Break.", bold_prefix="Auto-Close Lifecycle:")
    add_bullet(" Tracks open breaks by age (0–30, 30–60, 90+ days) to satisfy financial audit and regulatory compliance requirements.", bold_prefix="Break Aging & Reporting:")

    add_h2("3. Integrated Incident Management & RCA")
    add_bullet(" Groups of related breaks can be promoted directly into formal operational Incidents.", bold_prefix="Break-to-Incident Promotion:")
    add_bullet(" Every incident maintains a causationId linking through the reconciliation run to the raw ingestion file.", bold_prefix="Threaded Lineage:")
    add_bullet(" Operators annotate Incidents with root-cause classifications, corrective actions, and post-mortems in the console.", bold_prefix="Root Cause Analysis (RCA):")

    # =========================================================================
    # PAGE 5: Forensic Investigation
    # =========================================================================
    doc.add_page_break()
    add_page_header(5, "Forensic Investigation", "Graph Link Analysis & Offline Spatial Geo Mapping")

    add_h2("1. Link Analysis Studio (Entity/Link Graphs)")
    add_p("Inspecto embeds a dedicated graph investigation studio to uncover fraud rings, money laundering networks, and coordinated irregular behavior without external graph databases:")
    add_bullet(" The InvRoutes backend dynamically projects relational datasets into graph structures (nodes and edges) using server-side DuckDB aggregations.", bold_prefix="Server-Side Entity Projection:")
    add_bullet(" Calculates PageRank, betweenness, and eigenvector centrality to highlight key network brokers and kingpins.", bold_prefix="Centrality & Influence:")
    add_bullet(" Automatically identifies hidden syndicates using Louvain and label propagation algorithms.", bold_prefix="Community Detection:")
    add_bullet(" Applies composite scoring (0–100) to flag high-risk nodes based on connectivity density and transaction frequency.", bold_prefix="Suspicion Scoring:")
    add_bullet(" Pre-configured detection motifs for Circular Flow, Pass-Through Shells, Inbound Aggregators, and Layering Chains.", bold_prefix="Pre-Built Pattern Packs:")
    add_bullet(" Filters edges chronologically to play back the historical evolution of a network.", bold_prefix="Interactive Timeline Slider:")

    add_h2("2. Geo Map Analysis Studio")
    add_p("Answering the 'where' of an investigation alongside the 'who-connects-to-whom':")
    add_bullet(" Runs entirely without internet access. Bundles a lightweight MapLibre basemap engine with Natural Earth vector datasets (~2.7 MB) for global boundaries, coastlines, and places.", bold_prefix="100% Air-Gapped Basemap:")
    add_bullet(" Projects coordinate pairs into weighted great-circle routes to visualize physical movement, logistics flows, or telecom cell-tower handoffs.", bold_prefix="Origin-Destination (OD) Routes:")
    add_bullet(" Replays device or transaction movements over time with configurable playback speed and time horizons.", bold_prefix="Spatio-Temporal Playback:")
    add_bullet(" Automatically identifies when distinct entities (e.g., suspect SIM cards or vehicles) were within an identical radius during the same time window.", bold_prefix="Co-Location Intelligence:")

    # =========================================================================
    # PAGE 6: Embedded Intelligence & The Governed Autonomy Ladder
    # =========================================================================
    doc.add_page_break()
    add_page_header(6, "Embedded Intelligence & Governed Autonomy", "Air-Gapped AI: Explain, Draft, and Act with Human Approval")

    add_code_block(doc, """
                       THE GOVERNED AUTONOMY LADDER
  ┌────────────────────────────────────────────────────────────────────────┐
  │ LEVEL 3: BOUNDED AUTONOMY (Enterprise / Opt-in)                        │
  │ • Hands-off automated remediation for bounded, low-risk operational    │
  │   failures (e.g., automated partition retries, quarantined cleanups).  │
  │ • Strict Hourly Budgets • Global Kill Switch • Mandatory SHADOW Mode.  │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 2: ACT WITH APPROVAL (Standard+)                                 │
  │ • Agent drafts a mutation (e.g., config patch, schema fix, job retry). │
  │ • Routed to human operator's Approvals Inbox with a full visual diff.  │
  │ • Zero execution without explicit cryptographically signed sign-off.   │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 1: AUTHORING & DRAFTING (All Editions)                           │
  │ • Natural language to SQL queries, pipeline configs, and schedules.   │
  │ • Generates proposed components in-session; human reviews and saves.   │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 0: EXPLAIN & INVESTIGATE (All Editions)                          │
  │ • "Why did batch #408 failure quarantine 12% of rows?"                │
  │ • Grounded RCA diagnosis, schema explaining, and data lineage tracing. │
  │ • 100% Read-Only • Fully Offline • Zero Egress Guarantee.              │
  └────────────────────────────────────────────────────────────────────────┘
""")

    add_h2("1. The Sovereign AI Imperative: Zero Cloud Egress")
    add_p("In banking, national defense, and intelligence environments, transmitting proprietary customer records or network logs to commercial cloud LLMs (OpenAI, Anthropic) is strictly prohibited. Inspecto solves this with an uncompromised local architecture:")
    add_bullet(" Connects natively to local inference runtimes (Ollama, llama.cpp, vLLM, ONNX Runtime) running directly on the host or inside the customer's secure perimeter.", bold_prefix="Local / Air-Gapped Model Transport:")
    add_bullet(" Cloud LLM SDKs are physically excluded from air-gapped production builds, validated on every CI commit by EgressGuardTest. No prompt, token, or dataset cell ever leaves the physical node.", bold_prefix="Enforced Air-Gap Invariant:")

    add_h2("2. The Governed Autonomy Ladder: 'Sell the Ladder, Not the Ceiling'")
    add_bullet(" Investigates batch crashes, explains regex grammars, and translates natural language questions into validated SQL queries against the local lakehouse with ranked root-cause analysis.", bold_prefix="Tier A — Explain & Investigate (Levels 0 & 1):")
    add_bullet(" The agent drafts an operational fix (updating a delimiter, expanding field width, or triggering backfills). The action lands in the operator's console with a visual diff. Nothing executes until an operator approves. Approved actions ride the exact same authenticated /api/v1 REST routes that human operators use, generating identical audit trails.", bold_prefix="Tier B — Author & Act with Approval (Level 2):")
    add_bullet(" For repetitive operational failures, operators can enable bounded autonomy under strict policies. Mandatory SHADOW mode runs first (logging what it would have done in the /autonomy ledger). Enforces strict hourly execution budgets with an instant global kill switch.", bold_prefix="Tier C — Bounded Autonomy (Level 3):")

    add_h2("3. Seven Built-In Reflex Skills & 23-Tool Deliberative Belt")
    add_p("Includes 7 deterministic reflex skills (DiagnoseAndAlert, ExplainEntity, KpiToSql, NlToSchedule, ReportNarrative, ReportSql, SuggestConfig) plus a 23-tool belt with dry-run verification (AuthorPipelineDryRun, EditConfigDryRun, TriggerJobDryRun). Inline natural language authoring (<inspecto-ai-assist>) is embedded directly across pipeline, query, and dashboard editors.")

    # =========================================================================
    # PAGE 7: Performance & Measured Capacity
    # =========================================================================
    doc.add_page_break()
    add_page_header(7, "Performance & Measured Capacity", "Single-Node Vectorization vs. Distributed Cluster Bloat")

    add_p("Distributed big-data frameworks (Spark, Hadoop) were designed when servers were limited to 4–8 cores and 16 GB of RAM. Today, modern single servers feature 64–128 cores, hundreds of gigabytes of DDR5 RAM, and high-throughput NVMe storage. For workloads under two billion rows per day, distributed clustering introduces more network latency and operational cost than it solves.",
          bold_prefix="The Performance Thesis: ")

    add_h2("Stage-Isolated Benchmark Results (JDK 26 / DuckDB 1.5.2)")
    add_p("Measured on real production-profile files via PipelineBenchmark (docs/okf/backend/build-run/performance.md):", italic=True)

    t_perf = doc.add_table(rows=6, cols=3)
    t_perf_data = [
        ["Workload / Stage", "Measured Throughput", "Unit Processing Cost"],
        ["Native Ingest (12 columns, batch)", "523,000 rows / sec", "0.16 µs / cell"],
        ["Native Ingest (40 columns)", "125,000 rows / sec", "0.20 µs / cell"],
        ["Java Fallback Ingest (Complex files)", "25,000–50,000 rows / sec / core", "0.65–1.0 µs / cell"],
        ["Vectorized SQL Transforms", "1,400,000 rows / sec", "Direct SIMD execution"],
        ["Parquet Lakehouse Writes", "1,100,000 rows / sec", "Compressed disk I/O"]
    ]
    for r_i, row in enumerate(t_perf_data):
        for c_i, val in enumerate(row):
            t_perf.cell(r_i, c_i).text = val
    format_table(t_perf, [2.5, 2.3, 2.0])
    doc.add_paragraph()

    add_h2("Production Sizing: Single 8-Core Commodity Server")
    add_bullet(" 100 columns (standard telecom CDR or banking transaction record).", bold_prefix="Average Row Width:")
    add_bullet(" 4,000,000 to 10,000,000 cells per second per node.", bold_prefix="Processing Density:")
    add_bullet(" 40,000 to 100,000 rows per second sustained.", bold_prefix="Throughput:")
    add_bullet(" 3.5 to 8.6 billion rows/day at 100% capacity.", bold_prefix="Daily Volume:")
    add_bullet(" ~1.0 Billion rows per day sized to real-world peak surges.", bold_prefix="Sized for Peak (50% Headroom):")

    add_callout(doc, [
        ("A single commodity 8-core server running Inspecto comfortably processes the entire daily CDR volume of a mid-sized national telecom carrier or the daily transaction clearing ledger of a major retail bank—without a single external cluster service.",
         "Grounded Capacity Reality: ")
    ])

    # =========================================================================
    # PAGE 8: Fault Tolerance, HA & Disaster Recovery
    # =========================================================================
    doc.add_page_break()
    add_page_header(8, "Fault Tolerance, HA & Disaster Recovery", "Zero-Loss Ingest & Formally Contracted Recovery Targets")

    add_h2("1. In-Process Fault Tolerance (NFR-3)")
    add_bullet(" Batches execute in parallel using dedicated, ephemeral DuckDB connections. A query failure or memory error in one feed cannot impact concurrent feeds.", bold_prefix="Crash-Isolated Batch Execution:")
    add_bullet(" Ingest commits follow a strict order: Catalog Register -> Manifest -> Backup Original -> Markers -> Ledger Last. Arrival markers are committed last, so interrupted runs leave zero dirty state and resume 100% idempotently.", bold_prefix="Markers-Last Invariant:")
    add_bullet(" Processing is stateless with respect to the JVM. An unexpected process crash is resolved by the service wrapper restarting the process; in-flight tasks re-arm and resume automatically.", bold_prefix="Restart-as-Recovery:")

    add_h2("2. The Disaster Recovery (DR) Ladder & Signed SLOs")
    add_p("Formally signed recovery targets from docs/okf/capabilities/editions/editions.md §3.14:", italic=True)

    t_dr = doc.add_table(rows=5, cols=5)
    t_dr_data = [
        ["Tier", "Deployment Profile", "RPO Target", "RTO Target", "Recovery Mechanism"],
        ["T1", "Personal / Workstation", "≤ 24 hours", "≤ 4 hours", "Daily config zip + local restore"],
        ["T2", "Standard Single Server", "≤ 1 hour", "≤ 1 hour", "Hourly config + daily full backup"],
        ["T3", "Enterprise Multi-Team", "≤ 15 minutes", "≤ 2 hours", "T2 + Postgres PITR / WAL + off-site copy"],
        ["T4", "Standard Warm Standby", "≤ 5–15 minutes", "≤ 30 minutes", "Active/Passive streaming replication"]
    ]
    for r_i, row in enumerate(t_dr_data):
        for c_i, val in enumerate(row):
            t_dr.cell(r_i, c_i).text = val
    format_table(t_dr, [0.8, 1.8, 1.2, 1.2, 1.8])
    doc.add_paragraph()

    add_h2("3. T4 Warm Standby Architecture (Standard Edition)")
    add_bullet(" Continuous PostgreSQL streaming replication copies relational metadata to Site B.", bold_prefix="Relational State:")
    add_bullet(" Scheduled incremental synchronization of the spaces/ directory tree (Parquet files, configs, and checkpointed metadata).", bold_prefix="File Lakehouse:")
    add_bullet(" If Site A fails, promote Postgres, launch the standby Inspecto binary on Site B, and repoint DNS/load balancers. Downtime ≤ 30 minutes; Data loss ≤ 15 minutes.", bold_prefix="Failover Protocol:")

    # =========================================================================
    # PAGE 9: Operational Governance, Security & Compliance
    # =========================================================================
    doc.add_page_break()
    add_page_header(9, "Operational Governance, Security & Compliance", "Active SLA Sweeps, Auditor-Grade Governance, and Sovereign Data Security")

    add_h2("1. Operational Governance & Automated SLA Sweeps")
    add_bullet(" Inspecto tracks delivery watermarks and sequential batch numbering ({seq}). Missing files immediately trip a SEQUENCE_GAP Signal and open an Incident.", bold_prefix="Feed Freshness & Gaps:")
    add_bullet(" Every Incident can carry an explicit resolution deadline (e.g., dueInMinutes: 120). A background daemon sweeps open Incidents every 60 seconds (-Dobjects.sla.sweep.seconds).", bold_prefix="60-Second Daemon Sweep:")
    add_bullet(" Upon breach, the engine permanently stamps slaBreachedAt, emits an OBJECT_SLA_BREACH event, escalates priority from P2 to P1, and dispatches webhook alerts via *_escalation.toon.", bold_prefix="Policy Escalation:")
    add_bullet(" Executive tracking of Mean Time to Detect (MTTD), Mean Time to Resolve (MTTR), and Incident aging metrics.", bold_prefix="Operational Reporting (/kpi-reports):")

    add_h2("2. Security & Sovereign Compliance Frameworks (compliance/controls-matrix.md)")
    add_p("Inspecto runs on-premise or in private VPCs. Customer data never crosses an external network boundary.")

    add_bullet(" Zero cleartext credentials. SecretResolver resolves credentials at runtime from ${ENV}, ${FILE}, or JCEKS keystores. A build guard (tools/check-secrets.mjs) fails CI if any credential literal is introduced. 100% air-gapped with zero telemetry.", bold_prefix="SOC 2 Type II (Security CC6.1, CC6.7):")
    add_bullet(" Dependencies are locked and diff-checked per build (tools/dependencies.lock). Releases include SHA-256 checksums and detached GPG signatures (package.ps1 -Sign).", bold_prefix="SOC 2 Supply Chain (CC8):")
    add_bullet(" All mutating operations (POST, PUT, DELETE, /export) pass through a single architectural dispatch seam in AuditTrail.java, logging an append-only, actor-attributed audit trail. Authentication is delegated to enterprise IdPs via OIDC with PKCE.", bold_prefix="ISO 27001 Annex A (A.8.2, A.8.15):")
    add_bullet(" Pre-mapped customer-responsibility matrices accelerate agency Authority to Operate (ATO) certifications. SI-10 input validation and CM-6 declarative configuration (.toon) with PathJail path containment protect against directory traversal.", bold_prefix="NIST 800-53 / FedRAMP Moderate:")

    # =========================================================================
    # PAGE 10: TCO, Editions & The 60-Minute Evaluation
    # =========================================================================
    doc.add_page_break()
    add_page_header(10, "TCO, Editions & The 60-Minute Evaluation", "Predictable Economics & Immediate Evaluation Path")

    add_h2("The 3-Year Total Cost of Ownership (TCO) Comparison")
    add_p("Based on an enterprise workload processing 100M–500M records per day:", italic=True)

    t_tco = doc.add_table(rows=6, cols=3)
    t_tco_data = [
        ["Cost Category", "Legacy Multi-Vendor Stack", "Inspecto Standard Edition"],
        ["Infrastructure Compute", "8–15 VMs (Kafka, Spark, NiFi, Superset): $36,000/yr", "1 Production VM + 1 DR Standby VM: $6,000/yr"],
        ["Software Subscriptions", "Multi-vendor licenses (ETL, BI, Observability): $80,000/yr", "Single platform license: Predictable Flat Fee"],
        ["Engineering Headcount", "2–3 Dedicated Platform/DevOps Engineers: $300,000/yr", "0.5 FTE Data Operations Operator: $60,000/yr"],
        ["Compliance & Audit", "Multi-vendor SOC 2 reviews, pen tests: $40,000/yr", "Single binary compliance audit: $10,000/yr"],
        ["3-Year Estimated Total", "$1,368,000", "$228,000 (83% Net Savings)"]
    ]
    for r_i, row in enumerate(t_tco_data):
        for c_i, val in enumerate(row):
            t_tco.cell(r_i, c_i).text = val
    format_table(t_tco, [1.8, 2.7, 2.3])
    doc.add_paragraph()

    add_h2("The Clean Edition Ladder")
    add_bullet(" Full data plane on your laptop. Ingestion (SFTP/S3/DB), ASN.1 parsing, pipelines, Parquet lakehouse, Query Library, Studio BI dashboards, and reconciliation with breaks. 100% Free, zero authentication friction.", bold_prefix="Personal Edition (Free Adoption):")
    add_bullet(" The operational and commercial core. Adds OIDC/OAuth2 SSO, HTTPS, RBAC, attributed audit logging, Incident/Case management, Link Analysis, Geo Mapping, T4 Active/Passive Warm Standby DR, and Embedded Intelligence (Tiers A & B).", bold_prefix="Standard Edition (Revenue Gate):")
    add_bullet(" High-throughput distributed clustering. Partitioned scale-out on Kubernetes, ABAC tenant boundary isolation, shared object storage, and Bounded Autonomy (Tier C).", bold_prefix="Enterprise Edition (Scale-Out):")

    add_h2("The 60-Minute Evaluation Challenge")
    add_callout(doc, [
        ("1. Download the ~90 MB self-contained binary.\n"
         "2. Launch locally with zero external services: ./inspecto -Dcontrol.port=8080\n"
         "3. Load your most complex feed using a single declarative .toon config.\n\n"
         "Within 60 minutes, evaluate high-speed ingestion, automated break detection, interactive dashboards, and offline AI assistance on your own hardware.",
         "Experience Inspecto Today: ")
    ], border_color="10B981", bg_color="ECFDF5")

    add_p("Commercial Inquiries: sales@inspecto.io  •  Documentation & Blueprints: https://inspecto.io/docs\nInspecto Data Systems • Sovereign Data Operations Platform", italic=True)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(str(output_path))
    print(f"Successfully generated whitepaper at: {output_path}")

if __name__ == "__main__":
    out_file = Path(r"c:\sandbox\inspecto-clean\docs\stakeholders\INSPECTO_ENTERPRISE_WHITEPAPER.docx")
    build_whitepaper_docx(out_file)
