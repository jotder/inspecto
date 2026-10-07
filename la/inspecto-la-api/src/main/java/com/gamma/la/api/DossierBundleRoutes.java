package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.http.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.la.core.InvestigationEvaluator.canonical;
import static com.gamma.la.core.InvestigationEvaluator.sha256;

/**
 * D-6 — the <b>Dossier export bundle</b>: one portable JSON document an analyst can hand to someone outside the
 * system, sealed so that any later edit is detectable.
 *
 * <ul>
 *   <li>{@code GET  /inv/investigations/{id}/dossier/bundle?at=&snapshots=a,b} — the bundle.</li>
 *   <li>{@code POST /inv/investigations/{id}/dossier/bundle/verify} — body: a bundle (or {@code {bundle}}). Checks the
 *       seal, the Dossier manifest's own root, the appended references, and — against the store as it is now — the
 *       custody of every artefact the Dossier drew on ({@link DossierRoutes#verifyManifest}, the same check
 *       {@code /dossier/verify} runs).</li>
 * </ul>
 *
 * <p><b>Shape.</b> {@code format} {@value #FORMAT} · {@code investigationId} · {@code at} · {@code snapshots} ·
 * {@code masking} · {@code dossier} (exactly what {@code GET …/dossier} answers, masked) · {@code references} ·
 * {@code custody} {@code {manifestRoot, referencesCount, referencesHash}} · {@code seal}
 * {@code {algorithm, value}} — SHA-256 over the canonical JSON (map keys sorted) of every field above except
 * {@code seal}; {@code generatedAt} sits outside the seal, like the Dossier's own.
 *
 * <p><b>Masking applies on export, and the root stays verifiable (D-U6).</b> The Dossier and the references are masked
 * per the Space's {@code maskingMode} as the bundle is built, so a bundle never carries a raw id the caller could not
 * see. The Dossier manifest hashes the RAW store, so its {@code root} is identical masked or not; {@code custody
 * .referencesHash} likewise hashes the raw reference records. The seal covers the MASKED bundle as shipped. What a
 * holder can check offline is therefore the seal and the manifest's self-consistent root; whether the store still
 * agrees is the one thing that needs the server — this verify route.
 *
 * <p><b>Gates.</b> The export is a read, so it is judged exactly as the Dossier is ({@link InvestigationRoutes#openForRead}:
 * owner or Case member, R3 Dataset visibility on every included snapshot, the Enterprise PDP). It carries only the
 * sealed log, so four-eyes (D-U7) holds by construction: a PENDING sensitive expand never entered the log and is
 * absent; only an approved one — which names who requested and who approved it — appears. It persists nothing, so it
 * takes no capability; both routes are audited, because handing evidence outside the system is an act the trail
 * must show.
 *
 * <p><b>References are carried, never trusted.</b> Each is {@code trusted:false} and is only a pointer. They are
 * append-only, so a later reference does not break a bundle: verify requires the bundle's references to still be the
 * FIRST {@code referencesCount} records of the store ({@code referencesIntact}), and reports how many were added since.
 */
public final class DossierBundleRoutes implements RouteModule {

    static final String FORMAT = "inspecto-dossier-bundle/1";

    @Override
    public void register(ApiContext api) {
        api.get("/inv/investigations/([^/]+)/dossier/bundle", (e, m) -> export(api, e, m.group(1)));
        api.post("/inv/investigations/([^/]+)/dossier/bundle/verify", (e, m) -> verify(api, e, m.group(1), api.body(e)));
    }

    /** {@code GET …/dossier/bundle}. Gates: 503 → 422 id → 404 absent / not owner / R3 → 422 params. */
    @SuppressWarnings("unchecked")
    private static Object export(ApiContext api, HttpExchange ex, String id) throws IOException {
        DossierRoutes.Opened inv = DossierRoutes.open(api, ex, id);
        List<String> log = inv.store().log(InvestigationStore.Scope.main(id));
        int at = DossierRoutes.parseAt(ex, log);
        List<String> snapshotIds = DossierRoutes.parseSnapshotIds(ex);
        DossierRoutes.Masked built = DossierRoutes.maskedDossier(ex, inv, log, at, snapshotIds);
        Map<String, Object> dossier = built.dossier();
        Map<String, Object> manifest = (Map<String, Object>) dossier.get("manifest");

        List<String> rawRefs = inv.store().references(id);
        List<Map<String, Object>> refs = InvestigationReferenceRoutes.parse(rawRefs);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", FORMAT);
        bundle.put("investigationId", id);
        bundle.put("at", at);
        bundle.put("snapshots", manifest.get("snapshots"));
        bundle.put("masking", built.mask().describe());
        bundle.put("dossier", dossier);
        bundle.put("references", built.mask().apply(refs));
        Map<String, Object> custody = new LinkedHashMap<>();
        custody.put("manifestRoot", manifest.get("root"));
        custody.put("referencesCount", rawRefs.size());
        custody.put("referencesHash", sha256(canonical(refs)));
        bundle.put("custody", custody);
        bundle.put("seal", seal(bundle));
        bundle.put("generatedAt", Instant.now().toString());   // NOT part of the seal

        Map<String, Object> integrity = (Map<String, Object>) dossier.get("integrity");
        DossierRoutes.emit(ex, LinkEventTypes.LINK_DOSSIER_EXPORTED, "link.dossier.exported",
                "link.dossier.exported — " + id + " at step " + at + (Boolean.TRUE.equals(integrity.get("intact"))
                        ? "" : " (INTEGRITY FAILURE)"),
                b -> b.attr("investigationId", id).attr("at", at).attr("root", manifest.get("root"))
                        .attr("seal", ((Map<String, Object>) bundle.get("seal")).get("value"))
                        .attr("references", rawRefs.size()).attr("masking", String.valueOf(built.mask().describe().get("mode")))
                        .attr("intact", integrity.get("intact")));
        return bundle;
    }

    /**
     * {@code POST …/dossier/bundle/verify}. Gates: the same read gate → 422 not a bundle / not this Investigation.
     * A seal that does not match is a RESULT ({@code verified:false}), not an error, so tampering is reported.
     */
    @SuppressWarnings("unchecked")
    private static Object verify(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        DossierRoutes.Opened inv = DossierRoutes.open(api, ex, id);
        Map<String, Object> bundle = body.get("bundle") instanceof Map<?, ?> m ? (Map<String, Object>) m : body;
        if (!FORMAT.equals(bundle.get("format")) || !(bundle.get("dossier") instanceof Map<?, ?> d)
                || !(d.get("manifest") instanceof Map<?, ?> mf) || !(bundle.get("seal") instanceof Map<?, ?> sealed)
                || !(bundle.get("custody") instanceof Map<?, ?> custody) || !(bundle.get("references") instanceof List<?>))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must be a " + FORMAT + " bundle");
        if (!id.equals(bundle.get("investigationId")))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "the bundle is for investigation '"
                    + bundle.get("investigationId") + "', not '" + id + "'");

        boolean sealIntact = String.valueOf(((Map<String, Object>) seal(bundle)).get("value")).equals(String.valueOf(sealed.get("value")));
        boolean rootMatches = String.valueOf(custody.get("manifestRoot")).equals(String.valueOf(mf.get("root")));

        List<String> rawRefs = inv.store().references(id);
        int had = custody.get("referencesCount") instanceof Number n ? n.intValue() : -1;
        boolean referencesIntact = had >= 0 && had <= rawRefs.size()
                && sha256(canonical(InvestigationReferenceRoutes.parse(rawRefs.subList(0, had))))
                        .equals(String.valueOf(custody.get("referencesHash")));

        Map<String, Object> custodyResult = DossierRoutes.verifyManifest(ex, inv, id, (Map<String, Object>) mf);
        boolean verified = sealIntact && rootMatches && referencesIntact && Boolean.TRUE.equals(custodyResult.get("verified"));
        List<String> problems = new ArrayList<>();
        if (!sealIntact) problems.add("the bundle's seal does not match its content — it was edited after export");
        if (!rootMatches) problems.add("custody.manifestRoot is not the embedded Dossier manifest's root");
        if (!referencesIntact) problems.add("the bundle's references are not the first " + had + " references of the store now");
        if (!Boolean.TRUE.equals(custodyResult.get("verified")))
            problems.add("the Dossier manifest no longer matches the store (see custody)");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("verified", verified);
        out.put("sealIntact", sealIntact);
        out.put("rootMatches", rootMatches);
        out.put("referencesIntact", referencesIntact);
        out.put("referencesAddedSince", referencesIntact ? rawRefs.size() - had : null);
        out.put("problems", problems);
        out.put("custody", custodyResult);
        DossierRoutes.emit(ex, LinkEventTypes.LINK_DOSSIER_BUNDLE_VERIFIED, "link.dossier.bundle.verified",
                "link.dossier.bundle.verified — " + id + (verified ? "" : " (FAILED)"),
                b -> b.attr("investigationId", id).attr("verified", verified).attr("sealIntact", sealIntact)
                        .attr("referencesIntact", referencesIntact)
                        .attr("changed", ((List<?>) custodyResult.get("changed")).size()));
        return out;
    }

    /** The seal over every bundle field except {@code seal} and {@code generatedAt}: SHA-256 of the canonical JSON. */
    static Map<String, Object> seal(Map<String, Object> bundle) {
        Map<String, Object> body = new LinkedHashMap<>(bundle);
        body.remove("seal");
        body.remove("generatedAt");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("algorithm", "SHA-256");
        s.put("value", sha256(canonical(body)));
        return s;
    }
}
