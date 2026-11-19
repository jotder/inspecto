/**
 * Which Handbook chapter answers "how do I use this screen?" (USER-HANDBOOK-1). The Handbook is a pre-rendered static
 * site the bundle ships at `/help/` (`tools/render-handbook.mjs`), one `<id>.html` per chapter; these ids are its
 * stable chapter landing pages. This map is the ONE place a screen is tied to a chapter: key = the route's first path
 * segment, in the console and in Inspecto LA alike. A screen not listed opens the Handbook's home.
 */
export type HelpChapter =
    | 'start'
    | 'understand'
    | 'build_bring_data_in'
    | 'build_shape'
    | 'build_trust'
    | 'build_analyse'
    | 'build_detect'
    | 'build_respond'
    | 'build_present'
    | 'ship'
    | 'manage_app'
    | 'manage_platform'
    | 'develop'
    | 'reference'
    | 'troubleshooting';

/** The Handbook's home, relative to the site root. */
export const HELP_HOME = '/help/';

const CHAPTER_BY_SCREEN: Readonly<Record<string, HelpChapter>> = {
    home: 'start',
    'add-data': 'build_bring_data_in',
    collectors: 'build_bring_data_in',
    connections: 'build_bring_data_in',
    catalog: 'build_bring_data_in',
    'data-browser': 'build_bring_data_in',
    data: 'build_bring_data_in',
    pipelines: 'build_shape',
    enrichment: 'build_shape',
    jobs: 'build_shape',
    registry: 'build_shape',
    components: 'build_shape',
    expectations: 'build_trust',
    requirements: 'build_trust',
    reconciliation: 'build_trust',
    'link-analysis': 'build_analyse',
    'geo-map': 'build_analyse',
    entities: 'build_analyse',
    'entity-lists': 'build_analyse',
    'risk-scores': 'build_analyse',
    'anomaly-models': 'build_analyse',
    'analytics-functions': 'build_analyse',
    screening: 'build_analyse',
    'kpi-reports': 'build_analyse',
    'decision-rules': 'build_detect',
    alerts: 'build_respond',
    incidents: 'build_respond',
    cases: 'build_respond',
    'action-requests': 'build_respond',
    approvals: 'build_respond',
    runbooks: 'build_respond',
    studio: 'build_present',
    dashboard: 'build_present',
    overview: 'build_present',
    'regulatory-reports': 'build_present',
    'pending-changes': 'ship',
    runs: 'manage_app',
    'processing-status': 'manage_app',
    events: 'manage_app',
    maintenance: 'manage_app',
    settings: 'manage_platform',
    spaces: 'manage_platform',
    access: 'manage_platform',
    'investigation-access': 'manage_platform',
    audit: 'manage_platform',
    design: 'develop',
};

/** The chapter for a router URL (`/pipelines/orders?x=1#y` → `build_shape`), or `null` when no chapter is mapped. */
export function helpChapterFor(url: string): HelpChapter | null {
    const first =
        url
            .split(/[?#]/, 1)[0]
            .split('/')
            .find((s) => s.length > 0) ?? '';
    return Object.prototype.hasOwnProperty.call(CHAPTER_BY_SCREEN, first) ? CHAPTER_BY_SCREEN[first] : null;
}

/** The Handbook page to open for a router URL: its chapter's landing page, else the Handbook's home. */
export function helpUrlFor(url: string): string {
    const chapter = helpChapterFor(url);
    return chapter ? `${HELP_HOME}${chapter}.html` : HELP_HOME;
}
