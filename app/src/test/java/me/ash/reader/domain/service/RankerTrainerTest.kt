package me.ash.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RankerTrainerTest {

    @Test
    fun `logistic regression separates linearly separable classes`() {
        val random = Random(42)
        val features = ArrayList<FloatArray>()
        val labels = ArrayList<Float>()
        repeat(200) {
            // Two separable blobs around (-1,-1) and (+1,+1) plus bias.
            val positive = it % 2 == 0
            val x1 = (if (positive) 1f else -1f) + random.nextDouble().toFloat() * 0.8f - 0.4f
            val x2 = (if (positive) 1f else -1f) + random.nextDouble().toFloat() * 0.8f - 0.4f
            features.add(floatArrayOf(1f, x1, x2))
            labels.add(if (positive) 1f else 0f)
        }

        val weights = RankerTrainer.fitLogistic(features, labels)

        var correct = 0
        for (i in features.indices) {
            var z = 0f
            for (j in weights.indices) z += weights[j] * features[i][j]
            val predicted = if (z > 0f) 1f else 0f
            if (predicted == labels[i]) correct++
        }
        assertTrue("accuracy=${correct / features.size.toFloat()}", correct >= 190)
    }

    @Test
    fun `class weighting lifts rare positives`() {
        // 200 negatives at the origin, 10 positives at +3 — unweighted fitting
        // would predict negative almost everywhere.
        val features = ArrayList<FloatArray>()
        val labels = ArrayList<Float>()
        repeat(200) { features.add(floatArrayOf(1f, 0f)); labels.add(0f) }
        repeat(10) { features.add(floatArrayOf(1f, 3f)); labels.add(1f) }

        val weights =
            RankerTrainer.fitLogistic(
                features = features,
                labels = labels,
                sampleWeights = labels.map { if (it == 1f) 10f else 1f },
                iterations = 2000,
            )

        // The positive sample is x = [1, 3]: z = bias*1 + slope*3.
        val zAtPositive = weights[0] + weights[1] * 3f
        assertTrue("positive sample should classify positive: z=$zAtPositive", zAtPositive > 0f)
    }

    @Test
    fun `ridge recovers known linear weights`() {
        val random = Random(7)
        val features = ArrayList<FloatArray>()
        val targets = ArrayList<Float>()
        repeat(500) {
            val x1 = random.nextDouble().toFloat() * 4f
            val x2 = random.nextDouble().toFloat() * 4f
            features.add(floatArrayOf(1f, x1, x2))
            targets.add(0.5f + 2f * x1 - 1f * x2 + (random.nextDouble().toFloat() - 0.5f) * 0.01f)
        }

        val weights = RankerTrainer.fitRidge(features, targets)

        assertEquals(0.5f, weights[0], 0.05f)
        assertEquals(2f, weights[1], 0.05f)
        assertEquals(-1f, weights[2], 0.05f)
    }

    @Test
    fun `linear system solver handles pivoting`() {
        // Row 0 starts with a zero diagonal — needs partial pivoting.
        val solution =
            RankerTrainer.solveLinearSystem(
                arrayOf(
                    floatArrayOf(0f, 1f),
                    floatArrayOf(1f, 0f),
                ),
                floatArrayOf(2f, 3f),
            )
        assertEquals(3f, solution[0], 1e-4f)
        assertEquals(2f, solution[1], 1e-4f)
    }
}
