package com.hamiltel.gmlc.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpsMaskingTest {

    @Test
    void keepsTheFirstFiveAndLastTwoCharacters() {
        assertEquals("25677*****67", OpsMasking.mask("256771234567"));
        assertEquals("64101********01", OpsMasking.mask("641010000000001"));
        assertEquals("35693********09", OpsMasking.mask("356938035643809"));
    }

    @Test
    void shortValuesAreHiddenCompletely() {
        assertEquals("****", OpsMasking.mask("1234"));
        assertEquals("*******", OpsMasking.mask("1234567"));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(OpsMasking.mask(null));
        assertEquals("", OpsMasking.mask(""));
    }
}
