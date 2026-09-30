package com.gamma.job;

import com.gamma.objects.ObjectAccess;
import com.gamma.objects.ObjectType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code incident.open} Job Type: opens a managed {@link ObjectType#INCIDENT} in THIS Space from the Signal
 * that fired the Job. Built for the cross-Space consequence ({@code archived-documents/plans-archive/cross-space-consequence-design.md}
 * D10): a hub Space's Job, {@code on_signal: exchange.<opco>.fraud.alert}, opens a hub Incident. The target Space
 * authors what happens in it; the origin only announces.
 *
 * <p>The Incident carries <b>only</b> the payload keys the Job names in {@code fields}, as attributes. What a
 * delivered Signal's payload can hold was already cut to the offer's allowlist at the Space boundary, so an
 * Incident attribute can never carry a key the owner did not allowlist, and the hub author narrows it further.
 * {@code dedupe_key} (usually bound, e.g. {@code $signal.caseId}) keeps one open Incident per value.
 *
 * <p>No object engine wired (a build without {@code inspecto-ops}) fails the Run rather than succeeding
 * silently: the Job's whole purpose is the Incident.
 */
final class IncidentOpenJob implements Job {

    static final String TYPE_ID = "incident.open";

    private final JobConfig cfg;
    private final Supplier<ObjectAccess> objects;

    IncidentOpenJob(JobConfig cfg, Supplier<ObjectAccess> objects) {
        this.cfg = cfg;
        this.objects = objects;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return TYPE_ID; }

    @Override public JobResult run() {
        throw new UnsupportedOperationException("incident.open requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) {
        long t0 = System.nanoTime();
        ObjectAccess svc = objects == null ? null : objects.get();
        if (svc == null) throw new IllegalStateException("incident.open needs the operational-objects module");
        Map<String, String> p = ctx.params();
        Map<String, Object> payload = ctx.signalPayload();
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String f : p.getOrDefault("fields", "").split(",")) {
            String key = f.trim();
            if (!key.isEmpty() && payload.get(key) != null) attrs.put(key, String.valueOf(payload.get(key)));
        }
        String dedupe = p.get("dedupe_key");
        if (dedupe != null && !dedupe.isBlank() && svc.hasActive(ObjectType.INCIDENT, dedupe)) {
            ctx.log().info("an open Incident already exists for " + dedupe);
            return JobResult.ok("Incident already open for " + dedupe, ms(t0));
        }
        String title = p.get("title");
        String id = svc.open(ObjectType.INCIDENT, title, p.getOrDefault("description", title),
                p.getOrDefault("severity", "WARNING"), dedupe == null || dedupe.isBlank() ? null : dedupe, attrs);
        ctx.log().info("opened Incident " + id);
        return JobResult.ok("opened Incident " + id, ms(t0));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
