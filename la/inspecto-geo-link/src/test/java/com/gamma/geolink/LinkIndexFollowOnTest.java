package com.gamma.geolink;

import com.gamma.onboard.ImportFollowOn;
import com.gamma.spi.OptionalSpi;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BASIC-ONBOARD-1 P1: Link Analysis registers its link index as the import's follow-on. */
class LinkIndexFollowOnTest {

    @Test
    void linkAnalysisRegistersItsLinkIndexAsAFollowOn() {
        assertTrue(OptionalSpi.all(ImportFollowOn.class).stream().anyMatch(f -> f instanceof LinkIndexFollowOn),
                "META-INF/services lists LinkIndexFollowOn");
        LinkIndexFollowOn f = new LinkIndexFollowOn();
        assertEquals("index", f.key(), "the run summary key the UI and the arrivals read");
        assertEquals(Set.of("defaultLinkMapping"), f.needs());
        assertEquals("DEFERRED", f.deferred().get("result"));
    }

    @Test
    void aDatasetWithoutALinkMappingIsSkipped() {
        Map<String, Object> out = new LinkIndexFollowOn().run(new ImportFollowOn.Context(Path.of("unused"), Path.of("unused"),
                "people", Map.of("origin", "import", "kind", "reference"), "tester"));
        assertEquals("SKIPPED", out.get("result"));
        assertEquals("the Dataset has no link mapping", out.get("message"));
    }
}
