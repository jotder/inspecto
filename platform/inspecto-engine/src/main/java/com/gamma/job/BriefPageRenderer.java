package com.gamma.job;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Dashboard Brief as a report page (BI-INSIGHTS-1 P4 BR-4): wrapped plain text, the band as a WORD beside each
 * line (never colour alone), and a footer that says whether a model wrote the paragraph, so a reader of a printed page
 * knows what they hold. JDK-native Java2D like {@link WidgetChartRenderer}; text only, never interpreted.
 */
final class BriefPageRenderer {

    static final int W = 960, MARGIN = 28, LINE = 20, BAND_W = 86;
    static final String FOOTER_TEMPLATED = "Templated from the stored Insights";
    static final String FOOTER_MODEL = "Model-written, checked against the Insights";

    private static final Color TEXT = new Color(0x33, 0x33, 0x33);
    private static final Color MUTED = new Color(0x77, 0x77, 0x77);

    private BriefPageRenderer() {}

    static BufferedImage render(BriefPageProvider.BriefPage p) {
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        Font body = new Font(Font.SANS_SERIF, Font.PLAIN, 14), bold = new Font(Font.SANS_SERIF, Font.BOLD, 14);
        probe.setFont(body);
        FontMetrics fm = probe.getFontMetrics();
        int textW = W - 2 * MARGIN;
        List<String[]> rows = new ArrayList<>();   // {kind, text}: kind = h, m, p, b (band line), s
        rows.add(new String[] {"h", p.title()});
        rows.add(new String[] {"m", p.header()});
        if (p.note() != null) for (String l : wrap(p.note(), fm, textW)) rows.add(new String[] {"m", l});
        if (!p.paragraph().isEmpty()) {
            rows.add(new String[] {"g", ""});
            for (String s : p.paragraph()) for (String l : wrap(s, fm, textW)) rows.add(new String[] {"p", l});
        }
        rows.add(new String[] {"g", ""});
        if (p.lines().isEmpty())
            for (String l : wrap(p.summary() != null ? p.summary() : "Nothing unusual in the latest run.", fm, textW))
                rows.add(new String[] {"p", l});
        for (BriefPageProvider.Line l : p.lines()) {
            List<String> wrapped = wrap(l.text(), fm, textW - BAND_W);
            for (int i = 0; i < wrapped.size(); i++) rows.add(new String[] {i == 0 ? "b:" + l.band() : "c", wrapped.get(i)});
        }
        probe.dispose();

        int height = MARGIN * 2 + rows.size() * LINE + 2 * LINE;
        BufferedImage img = new BufferedImage(W, Math.max(height, 240), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            int y = MARGIN + 12;
            for (String[] r : rows) {
                switch (r[0].charAt(0)) {
                    case 'h' -> { g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18)); g.setColor(TEXT); g.drawString(r[1], MARGIN, y); }
                    case 'm' -> { g.setFont(body); g.setColor(MUTED); g.drawString(r[1], MARGIN, y); }
                    case 'p' -> { g.setFont(body); g.setColor(TEXT); g.drawString(r[1], MARGIN, y); }
                    case 'c' -> { g.setFont(body); g.setColor(TEXT); g.drawString(r[1], MARGIN + BAND_W, y); }
                    case 'b' -> {
                        g.setFont(bold);
                        g.setColor(TEXT);
                        g.drawString(bandWord(r[0].substring(2)), MARGIN, y);
                        g.setFont(body);
                        g.drawString(r[1], MARGIN + BAND_W, y);
                    }
                    default -> { }
                }
                y += LINE;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
            g.setColor(MUTED);
            g.drawString(p.modelWritten() ? FOOTER_MODEL : FOOTER_TEMPLATED, MARGIN, img.getHeight() - MARGIN / 2);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static String bandWord(String band) {
        return "high".equals(band) ? "High" : "elevated".equals(band) ? "Elevated" : "Normal";
    }

    /** Word-wrap to {@code max} pixels; a word wider than the line is cut at the line (a long slice value never overflows). */
    private static List<String> wrap(String text, FontMetrics fm, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).replaceAll("\\p{Cntrl}+", " ").split(" ")) {
            while (fm.stringWidth(word) > max && word.length() > 1) {
                int cut = word.length();
                while (cut > 1 && fm.stringWidth(word.substring(0, cut)) > max) cut--;
                if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                out.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String next = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(next) > max && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty() || out.isEmpty()) out.add(line.toString());
        return out;
    }
}
