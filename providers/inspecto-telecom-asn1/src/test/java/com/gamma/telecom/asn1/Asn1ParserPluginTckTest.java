package com.gamma.telecom.asn1;

import com.gamma.parse.ParserPlugin;
import com.gamma.parse.testkit.ParserPluginContract;

/** {@link Asn1ParserPlugin} against the platform's ParserPlugin TCK (MODULE-REORG-1 P5b). */
class Asn1ParserPluginTckTest extends ParserPluginContract {
    @Override
    protected ParserPlugin plugin() {
        return new Asn1ParserPlugin();
    }
}
