import { provideHttpClient, withXhr } from "@angular/common/http";
import { TestBed } from "@angular/core/testing";
import { provideNoopAnimations } from "@angular/platform-browser/animations";
import { ToastrService } from "ngx-toastr";
import { describe, expect, it } from "vitest";
import { expectNoA11yViolations } from "app/inspecto/testing/a11y";
import { GraphSourceId } from "app/inspecto/graph";
import { LinkAnalysisQueryPanelComponent } from "./link-analysis-query-panel.component";

function make(sourceId: GraphSourceId = "entity-projection") {
  TestBed.configureTestingModule({
    imports: [LinkAnalysisQueryPanelComponent],
    providers: [
      provideNoopAnimations(),
      // The panel now hosts <inspecto-ai-assist> (projection_author), which injects AgentService +
      // ToastrService — without these every test in this file dies at createComponent.
      provideHttpClient(withXhr()),
      {
        provide: ToastrService,
        useValue: { info: () => undefined, error: () => undefined },
      },
    ],
  });
  const fixture = TestBed.createComponent(LinkAnalysisQueryPanelComponent);
  const c = fixture.componentInstance;
  fixture.componentRef.setInput("sourceId", sourceId);
  fixture.componentRef.setInput("sources", [
    {
      id: "entity-projection",
      label: "Entity/Link",
      query: () => Promise.resolve({ nodes: [], edges: [] }),
    },
  ]);
  fixture.detectChanges();
  return { fixture, c };
}

describe("LinkAnalysisQueryPanelComponent", () => {
  it("entity-projection: needs a dataset + source/target columns, then builds a projection", () => {
    const { c } = make("entity-projection");
    expect(c.buildQuery()).toEqual({
      error: expect.stringMatching(/source and target/),
    });

    c.queryForm.patchValue({
      datasetId: "ds",
      sourceCol: "a",
      targetCol: "b",
      linkKindCol: "rel",
    });
    expect(c.buildQuery()).toEqual({
      projection: {
        datasetId: "ds",
        sourceCol: "a",
        targetCol: "b",
        linkKindCol: "rel",
        attrCols: undefined,
        entityType: undefined,
      },
    });
  });

  it("entity-projection: combining extra mappings requires an entity type on each", () => {
    const { c } = make("entity-projection");
    c.queryForm.patchValue({ datasetId: "ds", sourceCol: "a", targetCol: "b" });
    c.addMapping();
    c.extraMappings
      .at(0)
      .patchValue({ datasetId: "ds2", sourceCol: "x", targetCol: "y" });
    expect(c.buildQuery()).toEqual({
      error: expect.stringMatching(/entity type/),
    });

    c.queryForm.patchValue({ entityType: "person" });
    c.extraMappings.at(0).patchValue({ entityType: "account" });
    const q = c.buildQuery();
    expect("projections" in q && q.projections).toHaveLength(2);
  });

  it("provenance: needs a pipeline; folds extra pipelines into roots", () => {
    const { c } = make("provenance");
    expect(c.buildQuery()).toEqual({
      error: expect.stringMatching(/pipeline/i),
    });

    c.queryForm.patchValue({ pipeline: "p1", counts: true });
    expect(c.buildQuery()).toEqual({ from: "p1", counts: true });

    c.queryForm.patchValue({ extraPipelines: ["p2"] });
    expect(c.buildQuery()).toEqual({ roots: ["p1", "p2"], counts: true });
  });

  it("lineage: single root vs multi-root with depth/direction", () => {
    const { c } = make("lineage");
    c.queryForm.patchValue({ from: "table:cdr", depth: 3, direction: "out" });
    expect(c.buildQuery()).toEqual({
      from: "table:cdr",
      depth: 3,
      direction: "out",
    });

    c.queryForm.patchValue({ extraRoots: "table:orders, table:invoices" });
    expect(c.buildQuery()).toEqual({
      roots: ["table:cdr", "table:orders", "table:invoices"],
      depth: 3,
      direction: "out",
    });
  });

  it("patchFormFromView repopulates the form from a saved view", () => {
    const { c } = make("entity-projection");
    c.patchFormFromView({
      id: "v",
      name: "V",
      sourceId: "entity-projection",
      query: {
        projection: { datasetId: "ds", sourceCol: "s", targetCol: "t" },
      },
    });
    expect(c.queryForm.getRawValue().datasetId).toBe("ds");
    expect(c.queryForm.getRawValue().sourceCol).toBe("s");
  });

  it("patchFormFromView reads projections[] as well as projection", () => {
    const { c } = make("entity-projection");
    c.patchFormFromView({
      id: "v",
      name: "V",
      sourceId: "entity-projection",
      query: {
        projections: [
          {
            datasetId: "ds1",
            sourceCol: "s1",
            targetCol: "t1",
            attrCols: ["w"],
            entityType: "person",
          },
          {
            datasetId: "ds2",
            sourceCol: "s2",
            targetCol: "t2",
            entityType: "account",
          },
        ],
      },
    });
    const f = c.queryForm.getRawValue();
    expect(f.datasetId).toBe("ds1");
    expect(f.attrCols).toEqual(["w"]);
    expect(f.entityType).toBe("person");
    expect(c.extraMappings.length).toBe(1);
    expect(c.extraMappings.at(0).getRawValue()).toMatchObject({
      datasetId: "ds2",
      entityType: "account",
    });
    // The round trip is what a multi-mapping saved view needs: it used to load first-only.
    expect(c.buildQuery()).toMatchObject({
      projections: [{ datasetId: "ds1" }, { datasetId: "ds2" }],
    });
  });

  it("projection_author: applying a draft patches the form and persists nothing", () => {
    const { c } = make("entity-projection");
    c.applyProjectionDraft({
      label: "cdr",
      clean: true,
      findings: [],
      config: {
        query: {
          projections: [
            {
              datasetId: "cdr",
              sourceCol: "caller_id",
              targetCol: "callee_id",
              linkKindCol: "call_type",
              attrCols: ["duration_sec"],
            },
          ],
        },
      },
    });
    expect(c.queryForm.getRawValue()).toMatchObject({
      datasetId: "cdr",
      sourceCol: "caller_id",
      targetCol: "callee_id",
      linkKindCol: "call_type",
      attrCols: ["duration_sec"],
      entityType: "", // unset on a single mapping — it would change node ids
    });
    expect(c.queryForm.dirty).toBe(true);
  });

  it("projection_author args carry the pane's own column list, since no route returns one", () => {
    const { c } = make("entity-projection");
    // Order matters: picking a dataset RE-RESOLVES datasetColumns from `datasets()`, which is empty
    // here — so seed the columns after the pick, exactly as onDatasetPicked would from a real Dataset.
    c.queryForm.patchValue({ datasetId: "cdr" });
    c.datasetColumns.set(["caller_id", "callee_id"]);
    expect(c.aiProjectionArgs()).toEqual({
      datasetId: "cdr",
      columns: ["caller_id", "callee_id"],
    });
    // No baseline until the mapping is complete enough to build — a create, so all fields read added.
    expect(c.aiCurrentProjection()).toBeNull();
    c.queryForm.patchValue({ sourceCol: "caller_id", targetCol: "callee_id" });
    expect(c.aiCurrentProjection()).toMatchObject({
      query: { projections: [{ datasetId: "cdr" }] },
    });
  });

  it("renders with no a11y violations", async () => {
    const { fixture } = make();
    await expectNoA11yViolations(fixture.nativeElement);
  });
});
