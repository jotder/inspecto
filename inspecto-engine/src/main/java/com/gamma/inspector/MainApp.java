package com.gamma.inspector;

import com.gamma.config.safety.PathJail;
import com.gamma.util.*;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the URA pre-ETL and pipeline file-management tools.
 *
 * <p>All pre-ETL commands read their configuration directly from the pipeline
 * {@code .toon} file, which is passed as the first positional argument. Each
 * command has a dedicated section in the toon (see below).
 *
 * <p>Usage:
 * <pre>
 *   java -cp inspecto.jar com.gamma.inspector.MainApp [--dry-run] &lt;command&gt; &lt;pipeline.toon&gt; [args...]
 * </pre>
 *
 * <p>Commands and their toon sections:
 * <pre>
 *   search   &lt;pipeline.toon&gt;   → reads: search.*,  dirs.poll
 *   copy     &lt;pipeline.toon&gt;   → reads: search.*,  dirs.poll
 *   copy-tars &lt;pipeline.toon&gt;  → reads: copy_tars.base_dirs, dirs.poll
 *   extract  &lt;pipeline.toon&gt;   → reads: dirs.poll, dirs.temp, dirs.backup
 *   backup   &lt;pipeline.toon&gt;   → reads: backup.*, dirs.backup
 *   prepare-inbox &lt;pipeline.toon&gt; → reads: dirs.poll, dirs.temp, dirs.backup
 *   create-schema &lt;source&gt; &lt;sample.csv&gt; &lt;gen_config.toon&gt;
 * </pre>
 */
public class MainApp {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        // ── parse flags ───────────────────────────────────────────────────────
        boolean dryRun  = false;
        String  command = null;
        List<String> subArgsList = new ArrayList<>();

        for (String a : args) {
            if (a.equalsIgnoreCase("--dry-run")) {
                dryRun = true;
            } else if (a.equalsIgnoreCase("--apply")) {
                // Recognised here ONLY so it is stripped from the positional list; `migrate-configs`
                // reads it off the raw args below. Before this (fixed 2026-09-17,
                // CONFIG-MIGRATOR-LOSES-MAPPINGS-1 (b)) the documented form printed by printUsage() -
                // `migrate-configs <config_root> --apply` - fell through to subArgs, so subArgs.length
                // was 2 and the registry was written to a DIRECTORY LITERALLY NAMED `--apply`, while the
                // command still reported success and still archived the originals.
                continue;
            } else if (command == null) {
                command = a.toLowerCase();
            } else {
                subArgsList.add(a);
            }
        }

        if (command == null) {
            printUsage();
            System.exit(1);
        }

        String[] subArgs = subArgsList.toArray(new String[0]);

        // ── dispatch ──────────────────────────────────────────────────────────
        try {
            switch (command) {

                // ── search: find files from manifest in base_dirs, log only ───

                case "search": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileOrganizer(toon, dryRun, /*searchOnly=*/true).runSearch();
                    break;
                }

                // ── copy: find files from manifest in base_dirs, copy to poll dir ──

                case "copy":
                case "organize": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileOrganizer(toon, dryRun, /*searchOnly=*/false).run();
                    break;
                }

                // ── copy-tars: find *.tar.gz in base_dirs, copy flat to poll dir ──

                case "copy-tars": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new TarArranger(toon, dryRun).copyTars();
                    break;
                }

                // ── extract: unpack *.tar.gz in poll dir, arrange CSVs by date ──

                case "extract": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new TarArranger(toon, dryRun).extract();
                    break;
                }

                // ── backup: move originals listed in available_files.csv ───────

                case "backup": {
                    Map<String, Object> toon = loadToon(subArgs, command);
                    new FileBackup(toon, dryRun).run();
                    break;
                }

                // ── prepare-inbox: toon-driven tar → CSV inbox prep ────────────

                case "prepare-inbox": {
                    if (subArgs.length < 1) {
                        System.err.println("Usage: prepare-inbox <pipeline.toon>");
                        System.exit(1);
                    }
                    new TarInboxPreparer(loadToon(subArgs, command), subArgs[0], dryRun).run();
                    break;
                }

                // ── reprocess: delete-and-reprocess a whole batch by id ────────

                case "reprocess": {
                    if (subArgs.length < 2) {
                        System.err.println("Usage: reprocess <pipeline.toon> <batch_id>");
                        System.exit(1);
                    }
                    ReprocessCommand.run(subArgs[0], subArgs[1]);
                    break;
                }

                // ── create-schema: generate schema + pipeline toon from sample CSV

                case "create-schema": {
                    if (subArgs.length < 3) {
                        System.err.println("Usage: create-schema <source_name> <sample_csv> <gen_config.toon>");
                        System.exit(1);
                    }
                    SchemaExtractor.run(subArgs[0], subArgs[1], subArgs[2]);
                    break;
                }

                // ── legacy lower-level commands ───────────────────────────────

                case "move-by-date":
                    FileMoverByDate.main(args);
                    break;

                case "extract-unknown": {
                    String baseExt = subArgs.length > 0 ? subArgs[0] : ".";
                    String tempExt = subArgs.length > 1 ? subArgs[1] : "./temp";
                    new TarExtractor(baseExt, tempExt, dryRun).run();
                    break;
                }

                case "extract-move": {
                    if (subArgs.length < 3) {
                        System.err.println("Usage: extract-move <walk_root> <temp_dir> <target_base_dir>");
                        System.exit(1);
                    }
                    new IntegratedProcessor(subArgs[0], subArgs[1], subArgs[2], dryRun).run();
                    break;
                }

                // ── migrate-configs: the ELT §6 step-1 one-shot converter ────

                case "migrate-configs": {
                    if (subArgs.length < 1) {
                        System.err.println("Usage: migrate-configs <config_root> [<registry_root>]");
                        System.err.println("       plans only unless --apply is passed; --apply also moves the");
                        System.err.println("       originals under <config_root>/archived-config/");
                        System.exit(2);
                    }
                    java.nio.file.Path configRoot = java.nio.file.Paths.get(subArgs[0]);
                    java.nio.file.Path outRoot = subArgs.length > 1
                            ? java.nio.file.Paths.get(subArgs[1]) : configRoot.resolve("registry");
                    // The flag is deliberately the inverse of --dry-run: this command plans by default, so a
                    // bare invocation can never write. (§6 step 1: "deterministic, dry-run-first".)
                    boolean apply = java.util.Arrays.asList(args).contains("--apply");
                    com.gamma.pipeline.ConfigMigrator.Plan plan =
                            com.gamma.pipeline.ConfigMigrator.migrate(configRoot, outRoot, apply);

                    for (var c : plan.conversions())
                        System.out.println((plan.applied() ? "converted " : "would convert ")
                                + c.kind() + "  " + c.from() + "  ->  " + c.to());
                    for (var r : plan.refusals())
                        System.err.println("REFUSED  " + r.from() + "  -  " + r.reason());

                    System.out.println();
                    System.out.println(plan.conversions().size() + " file(s) "
                            + (plan.applied() ? "converted" : "would convert") + ", "
                            + plan.refusals().size() + " refused.");
                    if (!plan.ok()) {
                        System.err.println("NOTHING WAS WRITTEN - a refusal fails the whole migration, "
                                + "because a half-migrated space is worse than an unmigrated one.");
                        System.exit(1);
                    }
                    if (!apply) System.out.println("Dry run - pass --apply to write.");
                    break;
                }

                case "help":
                    printUsage();
                    break;

                default:
                    printUsage();
                    System.err.println("\nUnknown command: " + command);
                    System.exit(1);
            }

        } catch (Exception e) {
            System.err.println("Fatal error executing '" + command + "': " + e.getMessage());
            log.error("unhandled exception", e);
            System.exit(1);
        }
    }

    // ── toon loading ──────────────────────────────────────────────────────────

    /**
     * Expects {@code subArgs[0]} to be a path to a pipeline {@code .toon} file.
     * Parses it and returns the top-level map, its {@code dirs.*} resolved under the file's Space directory
     * through {@link PathJail#dataPath} — the loader's rule ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}), so
     * {@code ura.sh backup spaces/ucc/config/voucher/voucher_pipeline.toon} from the bundle root reads the
     * same directories the engine writes.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> loadToon(String[] subArgs, String command)
            throws IOException {
        if (subArgs.length < 1) {
            System.err.println("Usage: " + command + " <pipeline.toon>");
            System.exit(1);
        }
        String path = subArgs[0];
        if (!Files.exists(Paths.get(path)))
            throw new IOException("Pipeline toon not found: " + path);
        Map<String, Object> toon = (Map<String, Object>) JToon.decode(
                Files.readString(Paths.get(path), StandardCharsets.UTF_8));
        if (toon.get("dirs") instanceof Map<?, ?> dirs) {
            java.nio.file.Path configDir = Paths.get(path).toAbsolutePath().getParent();
            Map<String, Object> resolved = new java.util.LinkedHashMap<>();
            dirs.forEach((k, v) -> resolved.put(String.valueOf(k),
                    v instanceof String s ? PathJail.dataPath(configDir, s, "dirs." + k) : v));
            toon.put("dirs", resolved);
        }
        return toon;
    }

    // ── usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("URA File Management Suite — Java 24 (Virtual Threads)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ura [--dry-run] <command> <pipeline.toon> [args...]");
        System.out.println();
        System.out.println("  (ura = ura.sh on Linux/Mac, ura.bat on Windows — shipped alongside inspecto.jar)");
        System.out.println("  (dev: ./ura.sh or ura.bat from the inspecto/ source directory)");
        System.out.println("  (raw: java --enable-native-access=ALL-UNNAMED -cp inspecto.jar com.gamma.inspector.MainApp ...)");
        System.out.println();
        System.out.println("Pre-ETL commands (all read from pipeline.toon sections):");
        System.out.println("  search    <pipeline.toon>   Scan base_dirs for manifest files — log only, no copy.");
        System.out.println("                              Toon section: search.*");
        System.out.println("  copy      <pipeline.toon>   Scan base_dirs, copy manifest files to poll dir by date.");
        System.out.println("                              Toon section: search.*, dirs.poll");
        System.out.println("  copy-tars <pipeline.toon>   Find *.tar.gz in base_dirs, copy flat to poll dir.");
        System.out.println("                              Toon section: copy_tars.base_dirs, dirs.poll");
        System.out.println("  extract   <pipeline.toon>   Extract *.tar.gz in poll dir; arrange CSVs by date;");
        System.out.println("                              backup archives; clean temp.");
        System.out.println("                              Toon section: dirs.poll, dirs.temp, dirs.backup");
        System.out.println("  backup    <pipeline.toon>   Move originals (from available_files.csv) to backup dir.");
        System.out.println("                              Toon section: backup.*, dirs.backup");
        System.out.println("  prepare-inbox <pipeline.toon>");
        System.out.println("                              Extract .tar.gz from poll dir, arrange CSVs by date,");
        System.out.println("                              backup archives (toon-native, single-step).");
        System.out.println("                              Toon section: dirs.poll, dirs.temp, dirs.backup");
        System.out.println("  reprocess <pipeline.toon> <batch_id>");
        System.out.println("                              Delete a batch's outputs + markers, restore its");
        System.out.println("                              member files from backup, and reprocess the set.");
        System.out.println();
        System.out.println("Schema generation:");
        System.out.println("  create-schema <source_name> <sample_csv> <gen_config.toon>");
        System.out.println("                              Infer <source>_schema.toon + <source>_pipeline.toon");
        System.out.println("                              from a representative sample CSV.");
        System.out.println();
        System.out.println("ETL pipeline (runs the CollectorProcessor on a pipeline config, not via ura):");
        // The four run-*.sh/.bat wrappers this used to advertise were removed 2026-08-26. Two of them
        // named config paths that no longer existed, so the help was pointing operators at commands that
        // could only fail. The direct form is what the wrappers wrapped.
        System.out.println("  java -jar inspecto-processor-<version>.jar <pipeline.toon>");
        System.out.println("      e.g. spaces/ucc/config/voucher/voucher_pipeline.toon — polls the");
        System.out.println("      configured inbox and processes matching CSVs to Parquet.");
        System.out.println();
        System.out.println("Legacy / low-level commands:");
        System.out.println("  move-by-date                    Move files matching pattern into YYYYMMDD sub-folders.");
        System.out.println("  extract-unknown <base> <temp>   Extract tars found in 'obscure' directories.");
        System.out.println("  extract-move <walk> <temp> <target>  Integrated: find → extract → move.");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --dry-run   Simulate all actions; print what would happen without touching files.");
        System.out.println("  migrate-configs <config_root> [<registry_root>] [--apply]");
        System.out.println("                              ELT \u00a76 step 1: *_pipeline.toon -> pipelines/*.toon");
        System.out.println("                              recipes, *_schema.toon -> schemas/*.toon + mappings/*.csv.");
        System.out.println("                              Plans only unless --apply; refuses rather than lose.");
        System.out.println("  help        Print this message.");
    }
}
