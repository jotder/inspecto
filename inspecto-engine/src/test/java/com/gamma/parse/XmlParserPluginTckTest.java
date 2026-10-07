package com.gamma.parse;

import com.gamma.parse.testkit.ParserPluginContract;

/** {@link XmlParserPlugin} against the platform's ParserPlugin TCK (MODULE-REORG-1 P5b). */
class XmlParserPluginTckTest extends ParserPluginContract {
    @Override
    protected ParserPlugin plugin() {
        return new XmlParserPlugin();
    }
}
