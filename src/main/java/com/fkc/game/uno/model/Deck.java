package com.fkc.game.uno.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Standard 108-card UNO deck: 4 colors × (one 0, two each of 1-9, two Skip,
 * two Reverse, two Draw Two) = 4×25 = 100, plus 4 Wild + 4 Wild Draw Four = 108.
 */
public class Deck {

    private final Deque<Card> drawPile = new ArrayDeque<>();
    private final List<Card> discardPile = new ArrayList<>();

    public Deck() {
        List<Card> all = new ArrayList<>(108);
        for (Card.Color color : new Card.Color[]{Card.Color.RED, Card.Color.YELLOW, Card.Color.GREEN, Card.Color.BLUE}) {
            all.add(Card.number(color, 0));
            for (int n = 1; n <= 9; n++) {
                all.add(Card.number(color, n));
                all.add(Card.number(color, n));
            }
            for (int i = 0; i < 2; i++) {
                all.add(Card.action(color, Card.Type.SKIP));
                all.add(Card.action(color, Card.Type.REVERSE));
                all.add(Card.action(color, Card.Type.DRAW_TWO));
            }
        }
        for (int i = 0; i < 4; i++) {
            all.add(Card.wild(Card.Type.WILD));
            all.add(Card.wild(Card.Type.WILD_DRAW_FOUR));
        }
        Collections.shuffle(all);
        drawPile.addAll(all);
    }

    public int drawPileSize() {
        return drawPile.size();
    }

    /** Draws one card, reshuffling the discard pile back into the draw pile if needed. */
    public Card draw() {
        if (drawPile.isEmpty()) {
            reshuffleDiscardIntoDraw();
        }
        return drawPile.pollFirst();
    }

    private void reshuffleDiscardIntoDraw() {
        if (discardPile.size() <= 1) return; // nothing to reshuffle (keep the current top card)
        Card top = discardPile.remove(discardPile.size() - 1);
        List<Card> toShuffle = new ArrayList<>(discardPile);
        discardPile.clear();
        discardPile.add(top);
        Collections.shuffle(toShuffle);
        drawPile.addAll(toShuffle);
    }

    public void discard(Card card) {
        discardPile.add(card);
    }

    public Card topOfDiscard() {
        return discardPile.isEmpty() ? null : discardPile.get(discardPile.size() - 1);
    }
}
