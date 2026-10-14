package com.gamma.geolink;

import com.gamma.la.core.CollectorCoveragePort.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-DATA-TRUST-1 S1: the per-File join of one run's status, batches and lineage ledgers, over real CSVs in the ledgers' own headers. */
class HostCollectorCoveragePortFilesTest {

    private static final String STATUS = "start_time,end_time,filename,status,parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error,consignment_id,origin,logical_name\n";
    private static final String BATCHES = "consignment_id,pipeline,schema_name,output_table,start_time,end_time,status,member_count,rejected_files,rejected_rows,total_input_rows,total_output_rows,output_file_count,total_output_bytes,duration_ms,error,cast_failures\n";
    private static final String LINEAGE = "consignment_id,src_id,input_file,output_file,partition,row_count\n";

    private Path[] run(Path dir, String status, String batches, String lineage) throws Exception {
        Path b = dir.resolve("p_batches_20260905.csv");
        Files.writeString(b, BATCHES + batches);
        Files.writeString(dir.resolve("p_status_20260905.csv"), STATUS + status);
        Path l = dir.resolve("p_lineage_20260905.csv");
        Files.writeString(l, LINEAGE + lineage);
        return new Path[]{b, l};
    }

    @Test
    void oneRowPerFileWithDayRowsOutcomeAndOutputFilesAndTheProblemFilesOfAMatchedPipeline(@TempDir Path dir) throws Exception {
        Path[] run = run(dir,
                "t0,2026-09-05T10:00:00Z,a.csv,SUCCESS,100,0,\"x\",\"1\",5,\"\",c1,,\"\"\n"
                        + "t0,2026-09-05T10:01:00Z,b.csv,SUCCESS,40,3,\"x\",\"1\",5,\"\",c1,,\"\"\n"
                        + "t0,2026-09-05T10:02:00Z,empty.csv,QUARANTINED_EMPTY,0,0,\"\",\"\",5,\"\",,,\"\"\n"
                        + "t0,2026-09-05T10:03:00Z,bad.csv,QUARANTINED_MISMATCH,10,0,\"\",\"\",5,\"\",,,\"\"\n"
                        + "t0,2026-09-05T10:04:00Z,boom.csv,FAILED,0,0,\"\",\"\",5,\"x\",c9,,\"\"\n"
                        + "t0,2026-09-05T10:05:00Z,other.csv,SUCCESS,9,0,\"x\",\"1\",5,\"\",c2,,\"\"\n",
                "c1,p,s,calls,t0,t1,SUCCESS,2,0,0,143,140,2,2,5,,\nc2,p,s,elsewhere,t0,t1,SUCCESS,1,0,0,9,9,1,1,5,,\n",
                "c1,0,a.csv,/db/calls/year=2026/month=09/day=01/a.parquet,year=2026/month=09/day=01,60\n"
                        + "c1,0,a.csv,/db/calls/year=2026/month=09/day=02/a.parquet,year=2026/month=09/day=02,40\n"
                        + "c1,1,b.csv,/db/calls/year=2026/month=09/day=02/b.parquet,year=2026/month=09/day=02,37\n");
        List<FileEntry> mine = new ArrayList<>(), problems = new ArrayList<>();

        assertTrue(HostCollectorCoveragePort.collectFiles(run, Set.of("calls"), false, "col-1", "p", mine, problems));

        assertEquals(2, mine.size(), "the other Dataset's File is not this Dataset's");
        FileEntry a = mine.get(0);
        assertEquals("a.csv", a.name());
        assertEquals("2026-09-01", a.day(), "the earliest event day of its partitions");
        assertEquals(100, a.rows());
        assertEquals("SUCCESS", a.outcome());
        assertEquals("col-1", a.collector());
        assertEquals("p", a.pipeline());
        assertEquals("c1", a.consignmentId());
        assertEquals("2026-09-05T10:00:00Z", a.ingestedAt());
        assertEquals(2, a.outputFiles().size());
        assertEquals("PARTIAL", mine.get(1).outcome(), "committed with error rows");
        assertEquals(3, mine.get(1).errorRows());

        assertEquals(3, problems.size());
        FileEntry empty = problems.get(0);
        assertEquals("QUARANTINED", empty.outcome());
        assertEquals(0, empty.rows(), "read, held no rows: a File row, not an absent one");
        assertNull(empty.day());
        assertTrue(empty.outputFiles().isEmpty());
        assertEquals("QUARANTINED", problems.get(1).outcome());
        assertEquals("FAILED", problems.get(2).outcome());
    }

    @Test
    void aRunWhoseBatchesNeverWroteTheDatasetReportsNoMatch(@TempDir Path dir) throws Exception {
        Path[] run = run(dir, "t0,t1,x.csv,SUCCESS,1,0,\"\",\"\",5,\"\",c1,,\"\"\n", "c1,p,s,elsewhere,t0,t1,SUCCESS,1,0,0,1,1,1,1,5,,\n", "");
        List<FileEntry> mine = new ArrayList<>(), problems = new ArrayList<>();
        assertFalse(HostCollectorCoveragePort.collectFiles(run, Set.of("calls"), false, "c", "p", mine, problems));
        assertTrue(mine.isEmpty());
    }

    @Test
    void outcomeWordsAreTheFourTheAnalystSeesAndAnUnknownWordPassesThrough() {
        assertEquals("SUCCESS", HostCollectorCoveragePort.outcome("SUCCESS", 0));
        assertEquals("SUCCESS", HostCollectorCoveragePort.outcome("success", -1));
        assertEquals("PARTIAL", HostCollectorCoveragePort.outcome("SUCCESS", 1));
        assertEquals("QUARANTINED", HostCollectorCoveragePort.outcome("QUARANTINED_RESTRICTED", 0));
        assertEquals("QUARANTINED", HostCollectorCoveragePort.outcome("SKIPPED_UNREADABLE", 0));
        assertEquals("FAILED", HostCollectorCoveragePort.outcome("FAILED", 0));
        assertEquals("FAILED", HostCollectorCoveragePort.outcome(null, 0));
        assertEquals("PARKED", HostCollectorCoveragePort.outcome("PARKED", 0));
    }

    /** S10 live drive: rows with no usable event day sit in the NULL partition; the File must not read as a clean SUCCESS. */
    @Test
    void rowsInTheNullPartitionCountAsErrorRowsAndMakeTheFilePartial(@TempDir Path dir) throws Exception {
        String nul = "year=__HIVE_DEFAULT_PARTITION__/month=__HIVE_DEFAULT_PARTITION__/day=__HIVE_DEFAULT_PARTITION__";
        Path[] run = run(dir,
                "t0,2026-09-05T10:00:00Z,a.csv,SUCCESS,300,0,\"x\",\"1\",5,\"\",c1,,\"\"\n",
                "c1,p,s,calls,t0,t1,SUCCESS,1,0,0,300,300,1,1,5,,\n",
                "c1,0,a.csv,/db/calls/year=2026/month=09/day=11/a.parquet,year=2026/month=09/day=11,280\n"
                        + "c1,0,a.csv,/db/calls/" + nul + "/a.parquet," + nul + ",20\n");
        List<FileEntry> mine = new ArrayList<>(), problems = new ArrayList<>();
        assertTrue(HostCollectorCoveragePort.collectFiles(run, Set.of("calls"), false, "col-1", "p", mine, problems));
        FileEntry a = mine.get(0);
        assertEquals("2026-09-11", a.day());
        assertEquals(20, a.errorRows(), "the undated rows");
        assertEquals("PARTIAL", a.outcome());
    }

    @Test
    void aZoneNaiveLedgerTimeIsStatedAsAUtcInstantInTheHostZoneItWasWrittenIn() {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));   // UTC+05:30
            assertEquals("2026-10-14T06:24:40Z", HostCollectorCoveragePort.utcInstant("2026-10-14 11:54:40"));
            assertEquals("2026-10-14T06:24:40Z", HostCollectorCoveragePort.utcInstant("2026-10-14T11:54:40.123"));
            assertEquals("2026-09-05T10:00:00Z", HostCollectorCoveragePort.utcInstant("2026-09-05T10:00:00Z"), "a zoned instant passes through");
            assertEquals(null, HostCollectorCoveragePort.utcInstant(null));
            assertEquals("garbage-time-value!", HostCollectorCoveragePort.utcInstant("garbage-time-value!"));
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }
}
