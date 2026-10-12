package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.tracelite.issue.application.FingerprintGenerator;
import org.junit.jupiter.api.Test;

class FingerprintGeneratorTest {

    private final FingerprintGenerator generator = new FingerprintGenerator();

    @Test
    void producesSixtyFourLowercaseHexadecimalCharacters() {
        assertThat(generator.generate("java.lang.IllegalStateException")).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministic() {
        assertThat(generator.generate("java.lang.IllegalStateException"))
                .isEqualTo(generator.generate("java.lang.IllegalStateException"))
                .isEqualTo("76f39f88780c49e11378d8f3b95cbc1c8ce3d04615f04e90f29faaf6ff0cb30a");
    }

    @Test
    void differsForDifferentExceptionTypes() {
        assertThat(generator.generate("java.lang.IllegalStateException"))
                .isNotEqualTo(generator.generate("java.lang.IllegalArgumentException"));
    }
}
