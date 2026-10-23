import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { Signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { AccessNode, AccessProfile } from '../api/access.service';
import { LensService } from '../api/lens.service';
import { deriveDefaultAccessCatalog, indexCatalog } from './access-catalog';
import { AccessStateService } from './access-state.service';

/**
 * Parity between the Access Catalog's action nodes and the node ids `LensService` reads (LA-ACCESS-NODE-IDS-1).
 * A saved Access profile is keyed by CATALOG node ids; a capability that reads any other id is never denied, so
 * the node is a switch wired to nothing. Both sides are read from the real code — the catalog from
 * `deriveDefaultAccessCatalog()`, the lens's ids by recording what each `can*` signal looks up — so neither side
 * is a copy that could drift with the other.
 */

/** Catalog action nodes no screen reads, each with why the node still earns its place. */
const SERVER_ONLY_NODES: Record<string, string> = {
    // No screen offers a Signal type (`POST /exchange/signal-offers` is API-only); a ROLE profile denying the node
    // still strips `canOfferSignals` server-side (`AccessGrants`), so dropping it would lose that.
    'exchange.offer-signals': 'canOfferSignals',
};

/** Lens capabilities with no catalog node: backend-only gates no Access profile can deny yet. */
const UNCATALOGUED_CAPABILITIES = ['canBuildLinkIndex', 'canUploadData', 'canShareInvestigation'];

function actionNodes(): AccessNode[] {
    return [...indexCatalog(deriveDefaultAccessCatalog()).byId.values()].filter((n) => n.kind === 'action');
}

function capabilitySignals(lens: LensService): Record<string, Signal<boolean>> {
    const out: Record<string, Signal<boolean>> = {};
    for (const [key, value] of Object.entries(lens)) {
        if (/^can[A-Z]/.test(key) && typeof value === 'function') out[key] = value as Signal<boolean>;
    }
    return out;
}

describe('Access Catalog ↔ LensService node-id parity', () => {
    beforeEach(() => {
        localStorage.clear();
        TestBed.configureTestingModule({
            providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
        });
    });

    it('every lens capability reads exactly the id of the catalog node that carries it', () => {
        const lens = TestBed.inject(LensService);
        lens.selectLens('builder'); // not read-only, so every capability consults its action node
        const reads: string[] = [];
        const recorder = new Proxy({} as Record<string, Partial<Record<string, boolean>>>, {
            get: (_t, key) => {
                if (typeof key === 'string') reads.push(key);
                return undefined;
            },
        });
        lens.setActionGrants(recorder);

        const nodeByCapability = new Map(actionNodes().map((n) => [n.capability!, n.id]));
        const caps = capabilitySignals(lens);
        expect(Object.keys(caps).length).toBeGreaterThan(15); // the enumeration found the signals

        for (const [cap, sig] of Object.entries(caps)) {
            reads.length = 0;
            sig();
            expect(reads, `${cap} consults one action node`).toHaveLength(1);
            const catalogId = nodeByCapability.get(cap);
            if (UNCATALOGUED_CAPABILITIES.includes(cap)) {
                expect(catalogId, `${cap} is listed as uncatalogued but a catalog node carries it`).toBeUndefined();
                expect(nodeByCapability.has(cap)).toBe(false);
            } else {
                expect(reads[0], `${cap} reads the id of its catalog node`).toBe(catalogId);
            }
        }
    });

    it('every catalog action node is read by the lens capability it names, or is listed as server-only', () => {
        const caps = capabilitySignals(TestBed.inject(LensService));
        for (const node of actionNodes()) {
            if (node.id in SERVER_ONLY_NODES) {
                expect(node.capability).toBe(SERVER_ONLY_NODES[node.id]);
                expect(
                    caps[node.capability!],
                    `${node.id} is listed as server-only but the lens reads it`,
                ).toBeUndefined();
            } else {
                expect(caps[node.capability!], `${node.id}: LensService has no ${node.capability}`).toBeDefined();
            }
        }
        for (const id of Object.keys(SERVER_ONLY_NODES)) {
            expect(
                actionNodes().some((n) => n.id === id),
                `stale server-only entry ${id}`,
            ).toBe(true);
        }
    });

    it('a saved lens profile denying a catalog action node turns its capability off on screen', () => {
        const state = TestBed.inject(AccessStateService);
        const http = TestBed.inject(HttpTestingController);
        const lens = TestBed.inject(LensService);
        lens.selectLens('builder');
        const caps = capabilitySignals(lens);
        const profiles = (): TestRequest => http.expectOne((r) => r.url.endsWith('/access/profiles'));
        profiles().flush([]);

        const gated = actionNodes().filter((n) => !(n.id in SERVER_ONLY_NODES));
        expect(gated.map((n) => n.id)).toEqual(expect.arrayContaining(['linkgraph.reveal', 'linkgraph.approve']));
        for (const node of gated) {
            const cap = caps[node.capability!];
            expect(cap(), `${node.capability} starts allowed`).toBe(true);
            const deny: AccessProfile = {
                subjectType: 'lens',
                subjectId: 'builder',
                label: 'Builder',
                grants: { [node.id]: 'deny' },
            };
            state.reload();
            profiles().flush([deny]);
            expect(cap(), `denying ${node.id} turns ${node.capability} off`).toBe(false);
            state.reload();
            profiles().flush([]);
            expect(cap()).toBe(true);
        }
    });
});
