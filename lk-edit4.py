p='join-lookups.ts'
s=open(p,encoding='utf-8').read()
a="""        const outs = outputNames(r).map((x) => x.toLowerCase());
        for (const d of r.defaults)
            if (!outs.includes"""
assert a in s
s=s.replace(a,"""        // with no column listed the mapping returns every Reference column, whose names are not known here
        const known = r.columns.some((c) => c.ref.trim()) || r.emptyColumns || r.details || !!r.detailsRaw;
        const outs = outputNames(r).map((x) => x.toLowerCase());
        for (const d of known ? r.defaults : [])
            if (!outs.includes""")
open(p,'w',encoding='utf-8').write(s)
