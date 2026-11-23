p='pipeline-config-definition.component.ts'
s=open(p,encoding='utf-8').read()
def sub(a,b,n=1):
    global s
    assert s.count(a)==n,(a,s.count(a))
    s=s.replace(a,b)
sub("import { JoinAddedColumnsComponent } from './join-added-columns.component';\n",
"import { JoinAddedColumnsComponent } from './join-added-columns.component';\nimport { JoinLookupsEditorComponent } from './join-lookups-editor.component';\nimport { parseLookups } from './join-lookups';\n")
sub("        JoinAddedColumnsComponent,\n","        JoinAddedColumnsComponent,\n        JoinLookupsEditorComponent,\n")
sub("""        @if (isJoin()) {
            <!-- LOOKUP-1 P1d: what the keyed join adds""","""        @if (lookupsEditable()) {
            <!-- LOOKUP-1: the per-input mappings as a form (a lookups: this cannot read stays an extra setting) -->
            <inspecto-join-lookups-editor
                [initial]="node().config?.['lookups']"
                [inputColumns]="upstreamColumns()"
                [readOnly]="readOnly()"
                (changed)="onInteraction()"
            />
        }
        @if (isJoin()) {
            <!-- LOOKUP-1 P1d: what the keyed join adds""")
sub("""        @if (split().extraRows.length || allowAddExtra()) {""","""        @if (extraRows().length || allowAddExtra()) {""")
sub("""                @if (split().extraRows.length) {
                    <span class="opacity-60">({{ split().extraRows.length }})</span>""","""                @if (extraRows().length) {
                    <span class="opacity-60">({{ extraRows().length }})</span>""")
sub("""                [entries]="split().extraRows"
                [allowAdd]="allowAddExtra()\"""","""                [entries]="extraRows()"
                [allowAdd]="allowAddExtra()\"""")
sub("""    readonly split = computed(() => splitNodeConfig(this.node(), this.specs(), false));
""","""    readonly split = computed(() => splitNodeConfig(this.node(), this.specs(), false));

    /** A keyed join's `lookups:` is edited by the mappings form when it can read the stored value. */
    readonly lookupsEditable = computed(
        () => this.isJoin() && parseLookups(this.node().config?.['lookups']) !== null,
    );
    private readonly lookupsEditor = viewChild(JoinLookupsEditorComponent);
    /** The free-form rows: everything the schema and the bespoke editors do not own. */
    readonly extraRows = computed(() =>
        this.lookupsEditable() ? this.split().extraRows.filter((r) => r.key !== 'lookups') : this.split().extraRows,
    );
""")
sub("""            extras: this.extraConfig?.value() ?? {},
            name: n.name,""","""            extras: this.withLookups(this.extraConfig?.value() ?? {}),
            name: n.name,""")
sub("""    /** Apply: validate, rebuild the node, emit.""","""    /** The mappings form's `lookups`, merged into the extras; no mappings means the key is not written. */
    private withLookups(extras: Record<string, unknown>): Record<string, unknown> {
        const lookups = this.lookupsEditable() ? this.lookupsEditor()?.value() : undefined;
        return lookups === undefined ? extras : { ...extras, lookups };
    }

    /** Apply: validate, rebuild the node, emit.""")
sub("""        if (this.isSummarize() && !(this.summarizeEditor()?.validate() ?? true)) return;
        const node""","""        if (this.isSummarize() && !(this.summarizeEditor()?.validate() ?? true)) return;
        if (this.lookupsEditable() && !(this.lookupsEditor()?.validate() ?? true)) return;
        const node""")
sub("""        this.summarizeEditor()?.markPristine();
        this.emitDirty();""","""        this.summarizeEditor()?.markPristine();
        this.lookupsEditor()?.markPristine();
        this.emitDirty();""")
sub("""            (this.summarizeEditor()?.isDirty() ?? false);
        if (dirty""","""            (this.summarizeEditor()?.isDirty() ?? false) ||
            (this.lookupsEditor()?.isDirty() ?? false);
        if (dirty""")
open(p,'w',encoding='utf-8').write(s)
