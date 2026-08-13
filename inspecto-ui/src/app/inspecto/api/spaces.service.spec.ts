import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { provideHttpClient, withXhr } from "@angular/common/http";
import {
  HttpTestingController,
  provideHttpClientTesting,
} from "@angular/common/http/testing";
import { TestBed } from "@angular/core/testing";
import { Space, SpacesService } from "./spaces.service";
import { environment } from "../../../environments/environment";

const base = environment.apiBaseUrl + "/v1"; // W7: apiUrl() builds /api/v1 paths

const SPACES: Space[] = [
  {
    id: "alpha",
    displayName: "Alpha",
    description: "",
    createdAt: "2026-06-23 10:00:00",
  },
  {
    id: "beta",
    displayName: "Beta",
    description: "",
    createdAt: "2026-06-23 11:00:00",
  },
];

describe("SpacesService", () => {
  let svc: SpacesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        SpacesService,
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
      ],
    });
    svc = TestBed.inject(SpacesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it("refresh() loads the capability flag + list and auto-selects a space", () => {
    svc.refresh().subscribe();
    httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
    httpMock.expectOne(`${base}/spaces`).flush(SPACES);
    expect(svc.multiSpace()).toBe(true);
    expect(svc.availableSpaces().length).toBe(2);
    expect(svc.currentSpaceId()).toBe("alpha"); // no 'default' present → first space
  });

  it("refresh() survives a 200 carrying a non-array body", () => {
    // Containment for the NG0201 bootstrap cascade: `spaces.some(...)` used to throw on this.
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    svc.refresh().subscribe();
    httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
    httpMock
      .expectOne(`${base}/spaces`)
      .flush({ data: SPACES } as unknown as Space[]);
    expect(svc.availableSpaces()).toEqual([]);
    expect(svc.currentSpaceId()).toBeNull();
    expect(warn).toHaveBeenCalled(); // loud, not silent — the root cause is still unknown
    warn.mockRestore();
  });

  it("refresh() prefers a persisted selection that is still valid", () => {
    svc.selectSpace("beta");
    svc.refresh().subscribe();
    httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
    httpMock.expectOne(`${base}/spaces`).flush(SPACES);
    expect(svc.currentSpaceId()).toBe("beta");
  });

  it("refresh() clears the selection on a single-tenant server", () => {
    svc.selectSpace("alpha");
    svc.refresh().subscribe();
    httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: false });
    httpMock
      .expectOne(`${base}/spaces`)
      .flush([
        { id: "default", displayName: "", description: "", createdAt: "" },
      ]);
    expect(svc.multiSpace()).toBe(false);
    expect(svc.currentSpaceId()).toBeNull();
  });

  it("create() POSTs the snake_case body to /spaces", () => {
    svc
      .create({ id: "gamma", display_name: "Gamma", description: "x" })
      .subscribe();
    const req = httpMock.expectOne(`${base}/spaces`);
    expect(req.request.method).toBe("POST");
    expect(req.request.body).toEqual({
      id: "gamma",
      display_name: "Gamma",
      description: "x",
    });
    req.flush(SPACES[0]);
  });

  it("remove() DELETEs /spaces/{id} with ?purge=true only when purging", () => {
    svc.remove("alpha", true).subscribe();
    httpMock
      .expectOne(
        (r) =>
          r.url === `${base}/spaces/alpha` && r.params.get("purge") === "true",
      )
      .flush({});
    svc.remove("alpha", false).subscribe();
    httpMock
      .expectOne(
        (r) => r.url === `${base}/spaces/alpha` && !r.params.has("purge"),
      )
      .flush({});
  });

  it("exportSpace() GETs the whole-space zip as a blob", () => {
    svc.exportSpace("alpha").subscribe();
    const req = httpMock.expectOne(`${base}/spaces/alpha/export`);
    expect(req.request.method).toBe("GET");
    expect(req.request.responseType).toBe("blob");
    req.flush(new Blob());
  });

  it("importPreview() POSTs the zip to /import/preview", () => {
    svc.importPreview("alpha", new Blob()).subscribe();
    const req = httpMock.expectOne(`${base}/spaces/alpha/import/preview`);
    expect(req.request.method).toBe("POST");
    req.flush({
      kind: "data_source",
      sourceSpace: null,
      dataSources: [],
      files: [],
      hasSpaceToon: false,
      conflicts: [],
      findings: {},
      valid: true,
    });
  });

  it("importBundle() adds on_conflict=overwrite only when overwriting", () => {
    svc.importBundle("alpha", new Blob(), true).subscribe();
    httpMock
      .expectOne(
        (r) =>
          r.url === `${base}/spaces/alpha/import` &&
          r.params.get("on_conflict") === "overwrite",
      )
      .flush({});
    svc.importBundle("alpha", new Blob(), false).subscribe();
    httpMock
      .expectOne(
        (r) =>
          r.url === `${base}/spaces/alpha/import` &&
          !r.params.has("on_conflict"),
      )
      .flush({});
  });

  it("createFromBundle() POSTs the zip to /spaces/import?id=", () => {
    svc.createFromBundle("delta", new Blob()).subscribe();
    const req = httpMock.expectOne(
      (r) =>
        r.url === `${base}/spaces/import` && r.params.get("id") === "delta",
    );
    expect(req.request.method).toBe("POST");
    req.flush(SPACES[0]);
  });
});
