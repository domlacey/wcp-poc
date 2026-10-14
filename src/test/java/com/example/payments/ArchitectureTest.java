package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guards the dependency rule: only the adapter (and its wiring) may know about WeChat Pay. */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/payments");

    @Test
    void coreApiAndPersistenceDoNotDependOnProviderAdapters() throws IOException {
        for (String layer : List.of("core", "api", "persistence", "config")) {
            assertThat(sourcesReferencing(MAIN.resolve(layer), "com.example.payments.adapter"))
                    .as("%s must not reference provider adapters", layer)
                    .isEmpty();
        }
    }

    @Test
    void coreIsFrameworkFree() throws IOException {
        assertThat(sourcesReferencing(MAIN.resolve("core"), "org.springframework")).isEmpty();
    }

    private static List<Path> sourcesReferencing(Path dir, String packagePrefix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(packagePrefix);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }
}
