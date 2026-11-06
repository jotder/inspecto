package com.gamma.inspector;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-shot ELT §6 step-1 config converter, {@code migrate-configs}.
 *
 * <p>This class used to be the "URA File Management Suite" dispatcher (search, copy, copy-tars, extract,
 * backup, prepare-inbox, reprocess, create-schema, ...), retired 2026-11-06: Add data and Collectors
 * replace the file staging, and reprocess is {@code POST /runs/{pipeline}/reprocess}. {@code migrate-configs}
 * stays because BACKLOG Row 15 (ELT Phase 6) still needs the converter shipped.
 *
 * <p>Usage:
 * <pre>
 *   java -cp &lt;classpath&gt; com.gamma.inspector.MainApp migrate-configs &lt;config_root&gt; [&lt;registry_root&gt;] [--apply]
 * </pre>
 */
public class MainApp {

    private static final Logger log = LoggerFactory.getLogger(MainApp.class);

    public static void main(String[] args) {
        String command = null;
        List<String> subArgsList = new ArrayList<>();

        for (String a : args) {
            if (a.equalsIgnoreCase("--apply")) {
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

        try {
            switch (command) {

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

    // ── usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java --enable-native-access=ALL-UNNAMED -cp <classpath> com.gamma.inspector.MainApp <command> [args...]");
        System.out.println();
        System.out.println("  migrate-configs <config_root> [<registry_root>] [--apply]");
        System.out.println("                              ELT §6 step 1: *_pipeline.toon -> pipelines/*.toon");
        System.out.println("                              recipes, *_schema.toon -> schemas/*.toon + mappings/*.csv.");
        System.out.println("                              Plans only unless --apply; refuses rather than lose.");
        System.out.println("  help                        Print this message.");
    }
}
