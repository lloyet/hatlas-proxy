package org.minecraft.hatlas.proxy.link;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** Random {@code /link} codes from the configured alphabet ({@code link.code-alphabet}). */
public final class LinkCodeGenerator {

    private final String alphabet;
    private final int length;
    private final RandomGenerator random;

    public LinkCodeGenerator(String alphabet, int length) {
        this(alphabet, length, new SecureRandom());
    }

    LinkCodeGenerator(String alphabet, int length, RandomGenerator random) {
        if (alphabet.length() < 2 || length < 1) {
            throw new IllegalArgumentException("alphabet needs 2+ characters and length 1+");
        }
        this.alphabet = alphabet;
        this.length = length;
        this.random = random;
    }

    public String next() {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }
}
