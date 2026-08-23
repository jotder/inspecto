package com.gamma.etl.unpack;

import com.gamma.etl.PipelineConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared loadable-pipeline fixture for the unpack tests — a 3-column CSV schema, native engine. */
final class UnpackFixtures {

    private UnpackFixtures() {}

    private static final String SCHEMA = """
            partitionKey: V
            raw:
              name: t
              format: CSV
              fields[2]{name,selector,type}:
                ID,"0",VARCHAR
                V,"1",VARCHAR
            mapping:
              canonicalName: t
              rawName: t
              rules[2]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                V,V,DIRECT
            """;

    /** Load a minimal pipeline whose {@code processing:} block carries {@code procExtra} verbatim. */
    static PipelineConfig load(Path dir, String procExtra) throws Exception {
        Files.createDirectories(dir);
        Path schema = dir.resolve("schema_uf.toon");
        if (!Files.exists(schema)) Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = dir.toAbsolutePath().toString().replace('\\', '/');
        Path pipe = dir.resolve("pipe_uf_" + Math.abs(procExtra.hashCode()) + ".toon");
        Files.writeString(pipe,
                "name: UF\n"
              + "version: 1\n"
              + "dirs:\n"
              + "  poll: " + d + "/inbox\n"
              + "  database: " + d + "/db\n"
              + "  backup: " + d + "/backup\n"
              + "  temp: " + d + "/temp\n"
              + "  errors: " + d + "/errors\n"
              + "  quarantine: " + d + "/quarantine\n"
              + "  markers: " + d + "/markers\n"
              + "  status_dir: " + d + "/status\n"
              + "  log_dir: " + d + "/logs\n"
              + "output:\n"
              + "  format: CSV\n"
              + "processing:\n"
              + "  threads: 1\n"
              + "  file_pattern: \"glob:**/*\"\n"
              + "  schema_file: " + d + "/schema_uf.toon\n"
              + "  csv_settings:\n"
              + "    delimiter: \",\"\n"
              + "    engine: duckdb\n"
              + procExtra, StandardCharsets.UTF_8);
        return PipelineConfig.load(pipe.toString());
    }
}
