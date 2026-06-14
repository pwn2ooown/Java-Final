package com.poker.discord;

import com.poker.engine.Card;
import com.poker.engine.Suit;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final Color HIGHLIGHT = new Color(0xFFD700);

    private static final Font RANK_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 20);
    private static final Font SMALL_SUIT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
    private static final Font BIG_SUIT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 34);
    private static final Stroke BORDER_STROKE = new BasicStroke(1f);
    private static final Stroke HIGHLIGHT_STROKE = new BasicStroke(3f);

    private CardRenderer() {}

    public static byte[] renderCards(List<Card> cards) throws IOException {
        return renderCardsHighlighted(cards, List.of());
    }

    public static byte[] renderCardsHighlighted(List<Card> cards, List<Card> highlighted) throws IOException {
        if (cards.isEmpty()) return new byte[0];
        Set<String> hlSet = new HashSet<>();
        for (Card c : highlighted) hlSet.add(c.shortName());

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
            int x = PAD + i * (CARD_W + GAP);
            drawCard(g, cards.get(i), x, PAD);
            if (hlSet.contains(cards.get(i).shortName())) {
                g.setColor(HIGHLIGHT);
                g.setStroke(HIGHLIGHT_STROKE);
                g.drawRoundRect(x + 1, PAD + 1, CARD_W - 3, CARD_H - 3, RADIUS, RADIUS);
            }
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
        g.setStroke(BORDER_STROKE);
        g.drawRoundRect(x, y, CARD_W - 1, CARD_H - 1, RADIUS, RADIUS);

        boolean isRed = card.suit() == Suit.HEARTS || card.suit() == Suit.DIAMONDS;
        Color ink = isRed ? RED : BLACK;
        g.setColor(ink);

        String rankStr = card.rank().symbol();
        String suitStr = suitChar(card.suit());

        g.setFont(RANK_FONT);
        g.drawString(rankStr, x + 5, y + 20);

        g.setFont(SMALL_SUIT_FONT);
        g.drawString(suitStr, x + 6, y + 35);

        g.setFont(BIG_SUIT_FONT);
        FontMetrics fm = g.getFontMetrics();
        int sw = fm.stringWidth(suitStr);
        g.drawString(suitStr, x + (CARD_W - sw) / 2, y + CARD_H / 2 + 16);
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
