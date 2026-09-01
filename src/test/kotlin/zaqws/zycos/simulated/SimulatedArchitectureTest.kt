package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SimulatedArchitectureTest {
    @Test
    fun `core has no Paper Bukkit or outer Zycos dependency`() {
        val sourceRoot =
            Path.of(
                "src/main/kotlin/zaqws/zycos/simulated"
            )

        val violations =
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.toString().endsWith(".kt") }
                    .filter {
                        !it.normalize()
                            .startsWith(
                                sourceRoot.resolve("paper")
                            )
                    }
                    .flatMap { source ->
                        forbiddenLines(source).stream()
                    }
                    .toList()
            }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                prefix = "Forbidden simulated core dependencies:\n",
                separator = "\n"
            )
        )
    }

    private fun forbiddenLines(
        source: Path
    ): List<String> {
        val relativeSource =
            Path.of("")
                .toAbsolutePath()
                .normalize()
                .relativize(
                    source.toAbsolutePath()
                        .normalize()
                )

        return Files.readAllLines(source)
            .mapIndexedNotNull { index, line ->
                if (
                    !line.trimStart()
                        .startsWith("package ") &&
                    forbiddenDependency.containsMatchIn(line)
                ) {
                    "$relativeSource:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
    }

    companion object {
        private val forbiddenDependency =
            Regex(
                "org\\.bukkit|" +
                        "io\\.papermc|" +
                        "net\\.minecraft|" +
                        "com\\.destroystokyo|" +
                        "zaqws\\.zycos\\.simulated\\.paper|" +
                        "zaqws\\.zycos\\.(?!simulated\\.)"
            )
    }
}
