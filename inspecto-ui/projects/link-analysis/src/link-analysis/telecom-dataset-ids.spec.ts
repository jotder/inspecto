import { describe, expect, it } from 'vitest';
import { riskMappingFor } from './node-risk';

/**
 * The telecom node-risk mapping names Datasets by their COMPONENT id in the la-showcase Space Template
 * (`spaces/_templates/la-showcase/config/registry/datasets/<id>.toon`) — what LA_DATASETS lists and
 * `POST /inv/reference-rows` resolves — never by the store a Job writes (`telecom_links`, `crm_kyc`, ...).
 * Pinned: renaming a template Dataset must update this list and the mapping together. The telecom Industry pack's
 * investigate Dataset is pinned server-side (IndustryPacksTest), where the pack lives.
 */
const TEMPLATE_DATASET_IDS = [
    'crm_kyc_dataset',
    'hlr_eir_dataset',
    'telecom_links_dataset',
    'telecom_msisdn_indicators_dataset',
    'telecom_typology_counts_dataset',
];

describe('telecom profile Dataset ids', () => {
    it('the node-risk indicators and enrichment name template Datasets', () => {
        const m = riskMappingFor('telecom');
        expect(TEMPLATE_DATASET_IDS).toContain(m?.indicators.dataset);
        expect(TEMPLATE_DATASET_IDS).toContain(m?.enrichment?.dataset);
    });
});
