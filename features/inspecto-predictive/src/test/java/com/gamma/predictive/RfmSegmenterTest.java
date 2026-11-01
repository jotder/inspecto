package com.gamma.predictive;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Binning and labelling by hand: quantile edges, the score direction, ties, and recompute from a stored row. */
class RfmSegmenterTest {

    private static SegmentationModel model(int bins, List<Map<String, Object>> segments) {
        Map<String, Object> c = new LinkedHashMap<>(RfmCorpus.model());
        c.put("bins", bins);
        c.put("segments", segments);
        return SegmentationModel.fromMap("rfm", c);
    }

    private static final List<Map<String, Object>> ANY = List.of(
            Map.of("label", "Best", "recency", "5", "frequency", "5", "monetary", "5"));

    @Test
    void tenDistinctValuesFallTwoPerQuintileAndRecencyScoresFewerDaysHigher() {
        // frequency = monetary = i, recency = 11 - i days: entity i = 10 is the most recent, most frequent, biggest.
        List<RfmSegmenter.Rfm> pop = new ArrayList<>();
        for (int i = 1; i <= 10; i++) pop.add(new RfmSegmenter.Rfm("e" + i, 11 - i, i, i));
        List<RfmSegmenter.Assignment> out = RfmSegmenter.assign(model(5, ANY), pop);
        // quantile_cont edges of 1..10 at 0, .2, .4, .6, .8, 1: 1, 2.8, 4.6, 6.4, 8.2, 10
        assertArrayEquals(new double[]{1, 2.8, 4.6, 6.4, 8.2, 10}, out.get(0).frequency().edges(), 1e-12);
        assertArrayEquals(new double[]{-10, -8.2, -6.4, -4.6, -2.8, -1}, out.get(0).recency().edges(), 1e-12);
        int[] expected = {1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        for (int i = 0; i < 10; i++) {
            RfmSegmenter.Assignment a = out.get(i);
            assertEquals(expected[i], a.frequency().score(), "F of " + a.input().entityKey());
            assertEquals(expected[i], a.monetary().score(), "M of " + a.input().entityKey());
            assertEquals(expected[i], a.recency().score(), "R of " + a.input().entityKey() + ": fewer days, higher score");
            assertEquals(i >= 8 ? "Best" : "Other", a.segment());
        }
    }

    @Test
    void tiesShareABinAndAValueOnAnEdgeGoesToTheHigherBin() {
        // 1, 1, 1, 1, 9: quantile_cont edges at 0, .5, 1 = 1, 1, 9 - every 1 sits ON the interior edge.
        List<RfmSegmenter.Rfm> pop = new ArrayList<>();
        for (int i = 0; i < 4; i++) pop.add(new RfmSegmenter.Rfm("t" + i, 3, 1, 1));
        pop.add(new RfmSegmenter.Rfm("big", 3, 9, 9));
        List<RfmSegmenter.Assignment> out = RfmSegmenter.assign(model(2, List.of(
                Map.of("label", "Top", "recency", "*", "frequency", "2", "monetary", "2"))), pop);
        for (RfmSegmenter.Assignment a : out) {
            assertEquals(2, a.frequency().score(), a.input().entityKey() + ": a tie is never split (no ntile)");
            assertEquals(2, a.recency().score(), "one recency value for all: every entity on the top edge");
            assertEquals("Top", a.segment());
        }
    }

    @Test
    void theCorpusFiguresByHand() {
        List<RfmSegmenter.Rfm> pop = List.of(new RfmSegmenter.Rfm("champ", 1, 4, 400), new RfmSegmenter.Rfm("future", 71, 1, 20),
                new RfmSegmenter.Rfm("lapsed", 81, 2, 600), new RfmSegmenter.Rfm("loyal", 5, 3, 150),
                new RfmSegmenter.Rfm("newbie", 2, 1, 10), new RfmSegmenter.Rfm("nullamt", 15, 1, 0));
        List<RfmSegmenter.Assignment> out = RfmSegmenter.assign(SegmentationModel.fromMap("rfm", RfmCorpus.model()), pop);
        assertArrayEquals(new double[]{-81, -10, -1}, out.get(0).recency().edges(), 1e-12);
        assertArrayEquals(new double[]{1, 1.5, 4}, out.get(0).frequency().edges(), 1e-12);
        assertArrayEquals(new double[]{0, 85, 600}, out.get(0).monetary().edges(), 1e-12);
        for (RfmSegmenter.Assignment a : out)
            assertEquals(RfmCorpus.EXPECTED.get(a.input().entityKey()), a.segment(), a.input().entityKey());
    }

    @Test
    void anEmptyPopulationAssignsNothing() {
        assertEquals(List.of(), RfmSegmenter.assign(SegmentationModel.fromMap("rfm", RfmCorpus.model()), List.of()));
    }

    @Test
    void recomputeReDerivesTheRowFromItsExplanationAndCatchesATamperedOne() {
        SegmentationModel m = model(5, ANY);
        List<RfmSegmenter.Rfm> pop = new ArrayList<>();
        for (int i = 1; i <= 10; i++) pop.add(new RfmSegmenter.Rfm("e" + i, 11 - i, i, i * 10.0));
        for (RfmSegmenter.Assignment a : RfmSegmenter.assign(m, pop))
            assertEquals(List.of(a.segment(), a.recency().score(), a.frequency().score(), a.monetary().score()),
                    RfmSegmenter.recompute(m, a.explanation()), a.input().entityKey());
        RfmSegmenter.Assignment top = RfmSegmenter.assign(m, pop).get(9);
        Map<String, Object> tampered = new LinkedHashMap<>(top.explanation());
        Map<String, Object> f = new LinkedHashMap<>((Map<String, Object>) tampered.get("frequency"));
        f.put("value", 1.0);
        tampered.put("frequency", f);
        assertEquals(List.of("Other", 5, 1, 5), RfmSegmenter.recompute(m, tampered), "a changed value changes the row");
    }
}
