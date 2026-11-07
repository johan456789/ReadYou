package me.ash.reader.domain.service

import me.ash.reader.domain.model.insights.RankFeatures

/**
 * Pure-Kotlin fitters for the two tiny ranker stages. Sample counts are in
 * the low thousands and dimensions in the single digits, so plain full-batch
 * gradient descent (logistic) and closed-form normal equations (ridge) are
 * exact enough, deterministic, and dependency-free — no ML runtime needed.
 */
object RankerTrainer {

    /**
     * L2-regularized logistic regression via full-batch gradient descent.
     * The bias term (feature index 0) is not regularized. [sampleWeights]
     * rebalances classes (e.g. rare positives) and must align with [labels].
     */
    fun fitLogistic(
        features: List<FloatArray>,
        labels: List<Float>,
        sampleWeights: List<Float>? = null,
        l2: Float = 0.01f,
        iterations: Int = 1500,
        lr: Float = 0.5f,
    ): FloatArray {
        require(features.isNotEmpty()) { "No training samples" }
        require(features.size == labels.size) { "Feature/label size mismatch" }
        val d = features[0].size
        require(features.all { it.size == d }) { "Ragged feature rows" }
        val weights = FloatArray(d)
        val totalWeight = sampleWeights?.sum() ?: labels.size.toFloat()

        repeat(iterations) {
            val grad = FloatArray(d)
            for (i in features.indices) {
                val x = features[i]
                val y = labels[i]
                val w = sampleWeights?.get(i) ?: 1f
                var z = 0f
                for (j in 0 until d) z += weights[j] * x[j]
                val err = RankFeatures.sigmoid(z) - y
                for (j in 0 until d) grad[j] += w * err * x[j]
            }
            for (j in 0 until d) {
                val reg = if (j == 0) 0f else l2 * weights[j]
                weights[j] -= lr * (grad[j] / totalWeight + reg)
            }
        }
        return weights
    }

    /**
     * Ridge regression via the normal equations (XᵀX + λI)w = Xᵀy, solved with
     * Gaussian elimination and partial pivoting. The bias term (feature index
     * 0) is not regularized.
     */
    fun fitRidge(
        features: List<FloatArray>,
        targets: List<Float>,
        l2: Float = 1.0f,
    ): FloatArray {
        require(features.isNotEmpty()) { "No training samples" }
        require(features.size == targets.size) { "Feature/target size mismatch" }
        val d = features[0].size
        require(features.all { it.size == d }) { "Ragged feature rows" }

        val a = Array(d) { FloatArray(d) }
        val b = FloatArray(d)
        for (i in features.indices) {
            val x = features[i]
            val y = targets[i]
            for (j in 0 until d) {
                b[j] += x[j] * y
                for (k in 0 until d) a[j][k] += x[j] * x[k]
            }
        }
        for (j in 1 until d) a[j][j] += l2

        return solveLinearSystem(a, b)
    }

    /** Gaussian elimination with partial pivoting; returns the solution vector. */
    internal fun solveLinearSystem(a: Array<FloatArray>, b: FloatArray): FloatArray {
        val n = b.size
        require(a.size == n && a.all { it.size == n }) { "Matrix must be square" }
        val m = Array(n) { FloatArray(n + 1) }
        for (i in 0 until n) {
            System.arraycopy(a[i], 0, m[i], 0, n)
            m[i][n] = b[i]
        }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) pivot = row
            }
            require(Math.abs(m[pivot][col]) > 1e-6f) { "Singular system" }
            if (pivot != col) {
                val tmp = m[pivot]; m[pivot] = m[col]; m[col] = tmp
            }
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                if (factor == 0f) continue
                for (k in col..n) m[row][k] -= factor * m[col][k]
            }
        }
        val x = FloatArray(n)
        for (row in n - 1 downTo 0) {
            var sum = m[row][n]
            for (k in row + 1 until n) sum -= m[row][k] * x[k]
            x[row] = sum / m[row][row]
        }
        return x
    }
}
