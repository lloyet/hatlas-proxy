package org.minecraft.hatlas.proxy.link;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkCodeGeneratorTest {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    @Test
    void codesHaveTheConfiguredLengthAndOnlyAlphabetCharacters() {
        LinkCodeGenerator generator = new LinkCodeGenerator(ALPHABET, 6);
        for (int i = 0; i < 1000; i++) {
            String code = generator.next();
            assertEquals(6, code.length());
            assertTrue(code.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0), code);
        }
    }

    @Test
    void codesVaryAndUseTheWholeAlphabet() {
        LinkCodeGenerator generator = new LinkCodeGenerator(ALPHABET, 6, new SplittableRandom(42));
        Set<String> codes = new HashSet<>();
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = generator.next();
            codes.add(code);
            code.chars().forEach(c -> seen.add((char) c));
        }
        assertTrue(codes.size() > 1990, "almost no repeats among 31^6 codes");
        assertEquals(ALPHABET.length(), seen.size());
    }

    @Test
    void refusesADegenerateSetup() {
        assertThrows(IllegalArgumentException.class, () -> new LinkCodeGenerator("A", 6));
        assertThrows(IllegalArgumentException.class, () -> new LinkCodeGenerator(ALPHABET, 0));
    }
}
