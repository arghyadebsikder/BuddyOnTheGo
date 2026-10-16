package com.arghya.buddyonthego.domain

import kotlin.random.Random

/** Wake-up maths. Level 0 easy, 1 medium, 2 hard. */
object MathChallenge {
    data class Problem(val text: String, val answer: Int)

    val levelNames = listOf("Easy", "Medium", "Hard")
    val levelHints = listOf("Small sums, for example 7 + 5", "Two-digit sums, for example 34 - 18", "Times and plus, for example 12 x 7 + 15")

    fun generate(level: Int, rnd: Random = Random.Default): Problem = when (level.coerceIn(0, 2)) {
        0 -> {
            val a = rnd.nextInt(3, 10)
            val b = rnd.nextInt(2, 10)
            if (rnd.nextBoolean()) Problem("$a + $b", a + b) else Problem("${a + b} - $b", a)
        }
        1 -> {
            val a = rnd.nextInt(11, 60)
            val b = rnd.nextInt(11, 40)
            if (rnd.nextBoolean()) Problem("$a + $b", a + b) else Problem("${a + b} - $b", a)
        }
        else -> {
            val a = rnd.nextInt(6, 16)
            val b = rnd.nextInt(3, 10)
            val c = rnd.nextInt(5, 50)
            Problem("$a x $b + $c", a * b + c)
        }
    }
}
