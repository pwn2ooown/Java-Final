package com.poker.discord;

import com.poker.engine.Card;
import com.poker.engine.Suit;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Renders playing cards as PNG images for Discord display.
 * Works in headless mode (no display needed).
 */
public final class CardRenderer {

    private static final int CARD_W = 75;
    private static final int CARD_H = 105;
    private static final int GAP = 6;
    private static final int PAD = 8;
    private static final int RADIUS = 10;

    private static final Color BG = new Color(0x2B2D31);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color BORDER = new Color(0xBBBBBB);
    private static final Color SHADOW = new Color(0, 0, 0, 50);
    private static final Color RED = new Color(0xCC0000);
    private static final Color BLACK = new Color(0x1A1A1A);

    private CardRenderer() {}

    public static byte[] renderCards(List<Card> cards) throws IOException {
        if (cards.isEmpty()) return new byte[0];

        int n = cards.size();
        int totalW = n * CARD_W + (n - 1) * GAP + PAD * 2;
        int totalH = CARD_H + PAD * 2;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BG);
        g.fillRoundRect(0, 0, totalW, totalH, 12, 12);

        for (int i = 0; i < n; i++) {
            drawCard(g, cards.get(i), PAD + i * (CARD_W + GAP), PAD);
        }

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static byte[] renderCardBack(int count) throws IOException {
        if (count <= 0) return new byte[0];

        int totalW = count * CARD_W + (count - 1) * GAP + PAD * 2;
        int totalH = CARD_H + PAD * 2;

        BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(BG);
        g.fillRoundRect(0, 0, totalW, totalH, 12, 12);

        for (int i = 0; i < count; i++) {
            drawCardBack(g, PAD + i * (CARD_W + GAP), PAD);
        }

        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void drawCard(Graphics2D g, Card card, int x, int y) {
        g.setColor(SHADOW);
        g.fillRoundRect(x + 2, y + 2, CARD_W, CARD_H, RADIUS, RADIUS);

        g.setColor(CARD_BG);
        g.fillRoundRect(x, y, CARD_W, CARD_H, RADIUS, RADIUS);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, CARD_W - 1, CARD_H - 1, RADIUS, RADIUS);

        boolean isRed = card.suit() == Suit.HEARTS || card.suit() == Suit.DIAMONDS;
        Color ink = isRed ? RED : BLACK;
        g.setColor(ink);

        String rankStr = card.rank().symbol();
        String suitStr = suitChar(card.suit());

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString(rankStr, x + 5, y + 20);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        g.drawString(suitStr, x + 6, y + 35);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
        FontMetrics fm = g.getFontMetrics();
        int sw = fm.stringWidth(suitStr);
        g.drawString(suitStr, x + (CARD_W - sw) / 2, y + CARD_H / 2 + 16);
    }

    private static void drawCardBack(Graphics2D g, int x, int y) {
        g.setColor(SHADOW);
        g.fillRoundRect(x + 2, y + 2, CARD_W, CARD_H, RADIUS, RADIUS);

        g.setColor(new Color(0x1A5276));
        g.fillRoundRect(x, y, CARD_W, CARD_H, RADIUS, RADIUS);

        g.setColor(new Color(0x2980B9));
        g.fillRoundRect(x + 4, y + 4, CARD_W - 8, CARD_H - 8, RADIUS - 2, RADIUS - 2);

        g.setColor(new Color(0x1A5276));
        g.setStroke(new BasicStroke(1.5f));
        for (int i = -CARD_H; i < CARD_W + CARD_H; i += 8) {
            g.drawLine(x + 4 + i, y + 4, x + 4 + i - CARD_H, y + CARD_H - 4);
        }

        g.setColor(new Color(255, 255, 255, 30));
        g.fillRoundRect(x + 10, y + 10, CARD_W - 20, CARD_H - 20, 6, 6);
    }

    private static String suitChar(Suit suit) {
        return switch (suit) {
            case SPADES -> "♠";
            case HEARTS -> "♥";
            case DIAMONDS -> "♦";
            case CLUBS -> "♣";
        };
    }
}
