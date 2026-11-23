p='pipeline-config-definition.component.spec.ts'
s=open(p,encoding='utf-8').read()
tail="""            expect(fixture.componentInstance.formSpecs()).toEqual(fixture.componentInstance.specs());
        });
    });
});
"""
assert s.endswith(tail)
new="""            expect(fixture.componentInstance.formSpecs()).toEqual(fixture.componentInstance.specs());
        });
    });

    // ── transform.join lookups: (LOOKUP-1) ──────────────────────────────────────────────────────────
    describe('transform.join lookups', () => {
        const LOOKUPS = [{ on: 'SERVED_MSISDN', columns: { provider_desc: 'OPER' } }];
        const JOIN: AuthoredNode = {
            id: 'j',
            type: 'transform.join',
            config: { reference: 'reference/dial', key: 'dial_digit_key', lookups: LOOKUPS },
        };

        it('shows the mappings form INSTEAD of a JSON row for lookups', async () => {
            const fixture = await create({ node: JOIN });
            expect(fixture.debugElement.query(By.css('inspecto-join-lookups-editor'))).not.toBeNull();
            expect(fixture.componentInstance.extraRows().map((r) => r.key)).not.toContain('lookups');
        });

        it('Apply carries lookups through untouched, and writes an edited one', async () => {
            const fixture = await create({ node: JOIN });
            const applied: AuthoredNode[] = [];
            fixture.componentInstance.applied.subscribe((n: AuthoredNode) => applied.push(n));
            fixture.componentInstance.submit();
            expect(applied[0].config?.['lookups']).toEqual(LOOKUPS);

            const editor = fixture.debugElement.query(By.css('inspecto-join-lookups-editor')).componentInstance as {
                addRow: () => void;
                patch: (i: number, c: { on: string }) => void;
            };
            editor.addRow();
            editor.patch(1, { on: 'OTHER_MSISDN' });
            fixture.componentInstance.submit();
            expect((applied[1].config?.['lookups'] as unknown[]).length).toBe(2);
        });

        it('drops lookups when the last mapping is removed', async () => {
            const fixture = await create({ node: JOIN });
            const applied: AuthoredNode[] = [];
            fixture.componentInstance.applied.subscribe((n: AuthoredNode) => applied.push(n));
            (
                fixture.debugElement.query(By.css('inspecto-join-lookups-editor')).componentInstance as {
                    removeRow: (i: number) => void;
                }
            ).removeRow(0);
            fixture.componentInstance.submit();
            expect(applied[0].config).not.toHaveProperty('lookups');
        });

        it('leaves a lookups it cannot read as an extra setting', async () => {
            const bad = { ...JOIN, config: { ...JOIN.config, lookups: 'not a list' } };
            const fixture = await create({ node: bad });
            expect(fixture.debugElement.query(By.css('inspecto-join-lookups-editor'))).toBeNull();
            expect(fixture.componentInstance.extraRows().map((r) => r.key)).toContain('lookups');
        });
    });
});
"""
s=s[:-len(tail)]+new
open(p,'w',encoding='utf-8').write(s)
