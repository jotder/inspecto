package com.gamma.inspector;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.LocalFileSystemConnector;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.config.safety.DataRef;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.sql.SqlViews;
import com.gamma.util.Values;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The {@code connector: dataset} source (ELT Phase 3 <b>S3c-2</b>): a pipeline whose entry consumes
 * another store's parquet snapshots. Discovery lists the Dataset's snapshot files and the acquire
 * cycle copies each into <em>this</em> pipeline's own inbox ({@code fetchTo}) — the copy is what
 * kills the producer's stale-delete race (a snapshot deleted mid-copy fails clean and retries next
 * cycle), and it keeps backup/quarantine/markers/retention semantics intact. Refresh semantics come
 * from the existing machinery for free: {@code MaterializeTask} snapshots are timestamp-named, so
 * marker/fingerprint dedup re-ingests a refresh and skips the already-seen — no watermark concept.
 *
 * <p>The Dataset id resolves to its physical directory <b>fresh on every connector build</b> (never
 * baked into config, so relocating a store can't strand a consumer, and no free-text path exists for
 * an operator to hand-edit) through the SAME chain every other reader uses
 * ({@code DatasetRelation}'s): component {@code physicalRef} → {@link DataRef#requireUnder} under
 * the space data root → {@link SqlViews#storeReadRoot}. The ambient inputs are the sanctioned
 * job-lane flags ({@code -Dassist.write.root} for the registry, {@code -Ddata.dir} for the data
 * root — the exact {@code MaterializeTask} pattern), fail-fast when absent.
 *
 * <p>Registered via {@code META-INF/services} in this module: the engine is on every runtime
 * classpath that runs pipelines, so unlike the remote schemes no optional module is involved.
 */
public final class DatasetCollectorConnectorFactory implements CollectorConnectorFactory {

    @Override
    public String scheme() {
        return "dataset";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        String dataset = cfg.collector().dataset();
        if (dataset == null || dataset.isBlank())
            throw new IllegalArgumentException("connector 'dataset' requires collector.dataset: <dataset id>");
        Path sourceDir = resolveDatasetDir(dataset);
        // Delegate to the built-in local connector POINTED AT the resolved snapshot dir: its
        // discover/readiness/open/fetchTo/post already do exactly what a same-host copy needs.
        // Errors/quarantine stay the CONSUMING pipeline's own dirs — never the producer's.
        LocalFileSystemConnector local = new LocalFileSystemConnector(
                sourceDir, abs(cfg.dirs().errors()), abs(cfg.dirs().quarantine()), null);
        return new DatasetConnector(local, dataset);
    }

    /** {@code dataset id → snapshot dir}, by the one resolution every Dataset reader applies. */
    static Path resolveDatasetDir(String dataset) {
        String wr = System.getProperty("assist.write.root");
        if (wr == null || wr.isBlank())
            throw new IllegalStateException("connector 'dataset' needs -Dassist.write.root (the component registry)");
        String dd = System.getProperty("data.dir");
        if (dd == null || dd.isBlank())
            throw new IllegalStateException("connector 'dataset' needs a data root (-Ddata.dir / space dataDir)");
        ComponentStore store = new ComponentStore(Path.of(wr).resolve("registry"));
        Map<String, Object> content = store.get("dataset", dataset)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset '" + dataset + "'"));
        String ref = Values.trimToNull(content.get("physicalRef"));
        if (ref == null)
            throw new IllegalArgumentException("dataset '" + dataset + "' has no physicalRef — a view-backed "
                    + "Dataset has no files to collect (materialize it first)");
        Path base = DataRef.requireUnder(Path.of(dd), ref, "collector.dataset");
        return Path.of(SqlViews.storeReadRoot(base.normalize().toString().replace('\\', '/')));
    }

    private static Path abs(String dir) {
        return Path.of(dir).toAbsolutePath().normalize();
    }

    /** The local connector over the resolved snapshot dir, reporting this scheme's own identity. */
    private record DatasetConnector(LocalFileSystemConnector delegate, String dataset)
            implements CollectorConnector {
        @Override public String scheme() { return "dataset"; }
        @Override public EnumSet<Capability> capabilities() { return delegate.capabilities(); }
        @Override public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
            return delegate.discover(ctx);
        }
        @Override public Readiness readiness(RemoteFile file) throws AcquisitionException {
            return delegate.readiness(file);
        }
        @Override public InputStream open(RemoteFile file) throws AcquisitionException {
            return delegate.open(file);
        }
        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            return delegate.fetchTo(file, dest);
        }
        @Override public void post(RemoteFile file, PostAction action) throws AcquisitionException {
            // The source files belong to the PRODUCING store — a consumer must never delete/move/tag
            // them. Retain is the only honest post-action here, whatever the config says.
            delegate.post(file, PostAction.RETAIN);
        }
    }
}
