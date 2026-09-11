package elo.mainplugins.skyblock.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateMathTest {

    @Test
    void minCornerAndSizeAreInclusive() {
        TemplateMath.Pos a = new TemplateMath.Pos(10, 70, -5);
        TemplateMath.Pos b = new TemplateMath.Pos(4, 60, 3);
        assertEquals(new TemplateMath.Pos(4, 60, -5), TemplateMath.min(a, b));
        assertEquals(new TemplateMath.Pos(7, 11, 9), TemplateMath.size(a, b));
    }

    @Test
    void originOffsetIsMeasuredFromMinCorner() {
        TemplateMath.Pos min = new TemplateMath.Pos(4, 60, -5);
        TemplateMath.Pos origin = new TemplateMath.Pos(7, 64, 0);
        assertEquals(new TemplateMath.Pos(3, 4, 5), TemplateMath.offset(origin, min));
    }

    @Test
    void pastingPutsTheOriginOnTheIslandCenter() {
        TemplateMath.Pos offset = new TemplateMath.Pos(3, 4, 5);
        TemplateMath.Pos center = new TemplateMath.Pos(1000, 100, 2000);
        TemplateMath.Pos corner = TemplateMath.pasteCorner(center, offset);
        assertEquals(new TemplateMath.Pos(997, 96, 1995), corner);
        assertEquals(center, TemplateMath.plus(corner, offset));
    }
}
