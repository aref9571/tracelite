package com.tracelite.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tracelite.issue.application.FingerprintGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FingerprintGeneratorTest {

    private static final String TYPE = "java.lang.IllegalStateException";
    private static final String TRACE = """
            java.lang.IllegalStateException: Cart 42 is empty
            \tat com.example.cart.Cart.checkout(Cart.java:42)
            \tat com.example.cart.CartController.checkout(CartController.java:18)
            \tat java.base/java.lang.Thread.run(Thread.java:1583)
            """;

    private final FingerprintGenerator generator = new FingerprintGenerator();

    @Test
    void producesSixtyFourLowercaseHexadecimalCharacters() {
        assertThat(generator.generate(TYPE, TRACE)).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministicAcrossReleases() {
        assertThat(generator.generate(TYPE, TRACE))
                .isEqualTo(generator.generate(TYPE, TRACE))
                .isEqualTo("02e602e2d13a3cd7f7c8133aa8968cb9302d567b6dbcaaa63525a909bf307659");
    }

    @Test
    void ignoresTheMessageLineNumbersAndFileNames() {
        String changed = """
                java.lang.IllegalStateException: Cart 97 is empty
                \tat com.example.cart.Cart.checkout(Cart.kt:57)
                \tat com.example.cart.CartController.checkout(Unknown Source)
                \tat java.base/java.lang.Thread.run(Thread.java:1595)
                """;

        assertThat(generator.generate(TYPE, changed)).isEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void ignoresModuleAndClassLoaderPrefixes() {
        String withoutPrefixes = TRACE.replace("java.base/", "");
        String withClassLoader = TRACE.replace("at com.example", "at app//com.example");

        assertThat(generator.generate(TYPE, withoutPrefixes)).isEqualTo(generator.generate(TYPE, TRACE));
        assertThat(generator.generate(TYPE, withClassLoader)).isEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void ignoresWhitespaceAndLineEndings() {
        assertThat(generator.generate("  " + TYPE + "\n", TRACE.replace("\n", "\r\n").replace("\t", "    ")))
                .isEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void ignoresNumbersInGeneratedClassAndMethodNames() {
        String first = """
                \tat com.example.cart.Cart.lambda$checkout$0(Cart.java:42)
                \tat com.example.cart.Cart$$Lambda/0x000001f0010a2b48.accept(Unknown Source)
                \tat com.example.cart.Cart$$Lambda$14/0x0000000800066840.run(Unknown Source)
                \tat jdk.proxy2/jdk.proxy2.$Proxy45.checkout(Unknown Source)
                \tat jdk.internal.reflect.GeneratedMethodAccessor12.invoke(Unknown Source)
                \tat com.example.cart.CartService$$SpringCGLIB$$0.checkout(<generated>)
                \tat com.example.cart.CartService$$EnhancerBySpringCGLIB$$a1b2c3.checkout(<generated>)
                \tat com.example.cart.Order$HibernateProxy$Xy12Ab.getTotal(Unknown Source)
                """;
        String second = """
                \tat com.example.cart.Cart.lambda$checkout$3(Cart.java:44)
                \tat com.example.cart.Cart$$Lambda/0x000001f0010c9d10.accept(Unknown Source)
                \tat com.example.cart.Cart$$Lambda$27/0x0000000800091c40.run(Unknown Source)
                \tat jdk.proxy3/jdk.proxy3.$Proxy112.checkout(Unknown Source)
                \tat jdk.internal.reflect.GeneratedMethodAccessor87.invoke(Unknown Source)
                \tat com.example.cart.CartService$$SpringCGLIB$$1.checkout(<generated>)
                \tat com.example.cart.CartService$$EnhancerBySpringCGLIB$$ff99ee.checkout(<generated>)
                \tat com.example.cart.Order$HibernateProxy$Qw98Zx.getTotal(Unknown Source)
                """;

        assertThat(generator.generate(TYPE, second)).isEqualTo(generator.generate(TYPE, first));
    }

    @Test
    void collapsesRepeatedFramesFromRecursion() {
        String shallow = frames("com.example.Tree.walk", 2) + frames("com.example.Main.main", 1);
        String deep = frames("com.example.Tree.walk", 40) + frames("com.example.Main.main", 1);

        assertThat(generator.generate(TYPE, deep)).isEqualTo(generator.generate(TYPE, shallow));
    }

    @Test
    void ignoresFramesBeyondTheFrameLimit() {
        String top = distinctFrames(FingerprintGenerator.MAX_FRAMES);
        String longer = top + "\tat com.example.Caller.call(Caller.java:1)\n";

        assertThat(generator.generate(TYPE, longer)).isEqualTo(generator.generate(TYPE, top));
    }

    @Test
    void usesEveryFrameUpToTheFrameLimit() {
        String belowLimit = distinctFrames(FingerprintGenerator.MAX_FRAMES - 1);
        String atLimit = distinctFrames(FingerprintGenerator.MAX_FRAMES);

        assertThat(generator.generate(TYPE, atLimit)).isNotEqualTo(generator.generate(TYPE, belowLimit));
    }

    @Test
    void ignoresCausesAndSuppressedExceptions() {
        String withCause = TRACE + """
                Caused by: java.io.IOException: disk full
                \tat com.example.storage.Disk.write(Disk.java:10)
                \t... 3 more
                """;
        String withSuppressed = TRACE + """
                \tSuppressed: java.io.IOException: close failed
                \t\tat com.example.storage.Disk.close(Disk.java:20)
                """;

        assertThat(generator.generate(TYPE, withCause)).isEqualTo(generator.generate(TYPE, TRACE));
        assertThat(generator.generate(TYPE, withSuppressed)).isEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void ignoresAnIncompleteLastFrame() {
        String truncated = TRACE + "\tat com.example.cart.Ap";

        assertThat(generator.generate(TYPE, truncated)).isEqualTo(generator.generate(TYPE, TRACE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Traceback (most recent call last):\n  File \"cart.py\", line 3, in checkout",
            "java.lang.IllegalStateException: Cart is empty"})
    void fallsBackToTheExceptionTypeWithoutParseableFrames(String stackTrace) {
        assertThat(generator.generate(TYPE, stackTrace)).isEqualTo(generator.generate(TYPE, null));
        assertThat(generator.generate(TYPE, stackTrace)).isNotEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void differsForDifferentExceptionTypesWithTheSameFrames() {
        assertThat(generator.generate(TYPE, TRACE))
                .isNotEqualTo(generator.generate("java.lang.IllegalArgumentException", TRACE));
        assertThat(generator.generate(TYPE, null))
                .isNotEqualTo(generator.generate("java.lang.IllegalArgumentException", null));
    }

    @Test
    void differsWhenTheThrowingMethodDiffers() {
        String otherMethod = TRACE.replace("Cart.checkout(Cart.java:42)", "Cart.addItem(Cart.java:42)");
        String otherClass = TRACE.replace("cart.Cart.checkout", "cart.Basket.checkout");

        assertThat(generator.generate(TYPE, otherMethod)).isNotEqualTo(generator.generate(TYPE, TRACE));
        assertThat(generator.generate(TYPE, otherClass)).isNotEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void differsWhenTheCallPathDiffers() {
        String otherCaller = TRACE.replace("CartController.checkout", "CheckoutJob.run");
        String reordered = """
                \tat com.example.cart.CartController.checkout(CartController.java:18)
                \tat com.example.cart.Cart.checkout(Cart.java:42)
                \tat java.base/java.lang.Thread.run(Thread.java:1583)
                """;

        assertThat(generator.generate(TYPE, otherCaller)).isNotEqualTo(generator.generate(TYPE, TRACE));
        assertThat(generator.generate(TYPE, reordered)).isNotEqualTo(generator.generate(TYPE, TRACE));
    }

    @Test
    void separatesTheExceptionTypeFromTheFrames() {
        assertThat(generator.generate("a.B", "\tat c.D.e(D.java:1)\n"))
                .isNotEqualTo(generator.generate("a.B\nc.D.e", null));
    }

    @Test
    void requiresAnExceptionType() {
        assertThatNullPointerException().isThrownBy(() -> generator.generate(null, TRACE));
    }

    private static String frames(String method, int count) {
        return ("\tat " + method + "(Source.java:1)\n").repeat(count);
    }

    private static String distinctFrames(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> "\tat com.example.Step" + index + ".run(Step.java:1)\n")
                .collect(Collectors.joining());
    }
}
