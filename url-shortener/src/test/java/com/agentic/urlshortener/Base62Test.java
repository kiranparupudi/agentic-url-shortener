package com.agentic.urlshortener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Base62Test {

    @Test
    void encodesZero() {
        assertEquals("0", Base62.encode(0));
    }

    @Test
    void encodingIsDeterministicAndDistinct() {
        assertEquals(Base62.encode(123456), Base62.encode(123456));
        assertNotEquals(Base62.encode(123456), Base62.encode(123457));
    }
}
