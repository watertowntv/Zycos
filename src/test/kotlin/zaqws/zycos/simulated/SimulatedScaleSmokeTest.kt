package zaqws.zycos.simulated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class SimulatedScaleSmokeTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `five thousand entities publish frames without engine failure`() {
        val engine =
            SimulatedEngineBuilder(
                TestSimulatedMapFactory.create(
                    chunkCountX = 6,
                    chunkCountZ = 6
                )
            ).config(
                SimulatedConfig(
                    initialEntityCapacity = 5_000,
                    navigationWorkerCount = 1
                )
            ).build()

        try {
            var index = 0

            while (index < 5_000) {
                val gridX =
                    index % 80

                val gridZ =
                    index / 80

                engine.spawn {
                    position(
                        x = gridX * 1.1 + 0.5,
                        y = 1.0,
                        z = gridZ * 1.1 + 0.5
                    )
                }

                index++
            }

            engine.start()

            val deadline =
                System.nanoTime() +
                        TimeUnit.SECONDS.toNanos(10L)

            while (
                System.nanoTime() < deadline &&
                (
                        engine.tick < 15L ||
                                engine.timingSnapshot().measuredTicks < 15L ||
                                engine.latestFrame.size < 5_000
                        )
            ) {
                Thread.sleep(10L)
            }

            assertNull(engine.failure)
            assertEquals(5_000, engine.latestFrame.size)

            val timing =
                engine.timingSnapshot()

            assertTrue(timing.measuredTicks >= 15L)

            println(
                "SIMULATED_SCALE measuredTicks=${timing.measuredTicks} " +
                        "averageTickNanoseconds=${timing.averageTickNanoseconds} " +
                        "maximumTickNanoseconds=${timing.maximumTickNanoseconds}"
            )
        } finally {
            engine.close()
        }
    }
}
