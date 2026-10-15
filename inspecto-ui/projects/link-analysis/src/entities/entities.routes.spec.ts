import { describe, expect, it } from 'vitest';
import routes from './entities.routes';
import { EntitiesComponent } from './entities.component';
import { EntityComponent } from './entity.component';
import { EntityTypeComponent } from './entity-type.component';

describe('entities routes', () => {
    it('mounts the list, the Entity Type page and the Entity page, each lazily', async () => {
        expect(routes.map((r) => r.path)).toEqual(['', ':type', ':type/:key']);
        expect(await routes[0].loadComponent!()).toBe(EntitiesComponent);
        expect(await routes[1].loadComponent!()).toBe(EntityTypeComponent);
        expect(await routes[2].loadComponent!()).toBe(EntityComponent);
    }, 60_000);
});
