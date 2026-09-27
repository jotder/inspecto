import { inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AttributeOptionLoader } from 'app/inspecto/components/schema-form.component';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from './datasets.service';

/**
 * Columns of the **Dataset** a sibling field names by id (Alert Rule `by` follows `dataset`, KPI
 * `timeField` follows `dataset`, Expectation `refColumn` follows `refDataset`). A Dataset id is not a
 * store name (demo `orders_dataset` → `physicalRef: orders`), so `columnOptionLoader`'s `/db/table`
 * probe of the raw value 404s. This resolves the Dataset, then asks the rows seam for its columns —
 * declared columns win, else a 1-row probe of what it resolves to. Unknown Dataset / unreadable store
 * ⇒ no suggestions, never a throw. Lives beside {@link DatasetsService} so shared code never imports
 * this feature (`angular-ui` §3); keep `columnOptionLoader` for genuine store-name siblings.
 */
export function datasetColumnOptionLoader(datasetKey: string): AttributeOptionLoader {
    const datasets = inject(DatasetsService);
    const rows = inject(DatasetRowsService);
    return async (v) => {
        const id = String(v[datasetKey] ?? '').trim();
        if (!id) return [];
        try {
            const cols = await rows.columns(await firstValueFrom(datasets.get(id)));
            return cols.map((c) => ({ value: c.name, label: c.type ? `${c.name} (${c.type.toLowerCase()})` : c.name }));
        } catch {
            return [];
        }
    };
}
