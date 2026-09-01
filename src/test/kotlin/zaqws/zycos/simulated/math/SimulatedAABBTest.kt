package zaqws.zycos.simulated.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SimulatedAABBTest {
    private val box =
        SimulatedAABB(
            1.0,
            1.0,
            1.0,
            2.0,
            2.0,
            2.0
        )

    @Test
    fun `segment intersection returns first fraction`() {
        assertEquals(
            0.25,
            box.segmentIntersectionFraction(
                SimulatedVector3(
                    0.0,
                    1.5,
                    1.5
                ),
                SimulatedVector3(
                    4.0,
                    1.5,
                    1.5
                )
            )
        )

        assertEquals(
            0.0,
            box.segmentIntersectionFraction(
                SimulatedVector3(
                    1.5,
                    1.5,
                    1.5
                ),
                SimulatedVector3(
                    3.0,
                    1.5,
                    1.5
                )
            )
        )

        assertNull(
            box.segmentIntersectionFraction(
                SimulatedVector3(
                    0.0,
                    3.0,
                    1.5
                ),
                SimulatedVector3(
                    4.0,
                    3.0,
                    1.5
                )
            )
        )
    }
}
