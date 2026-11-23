package fixtures

class Extents(val seed: Int) {
    fun commented(): Int {
        val a = seed
        // a comment inside the body
        return a + 1
    }

    fun continued(): Int {
        val text = """
            first {
            second
        """.trimIndent()
        return text.length + seed
    }

    fun withLambda(): Int {
        val square = { x: Int ->
            x * x
        }
        return square(seed)
    }

    fun withObject(): Runnable {
        val task = object : Runnable {
            override fun run() {
                println(seed)
            }
        }
        return task
    }

    fun multiLineSignature(
        first: Int,
        second: Int,
    ): Long {
        return first.toLong() + second
    }

    fun returnType(): Int {
        return seed
    }

    fun defaulted(
        value: Int = 42,
    ): Int {
        return value
    }

    /**
     * Documented.
     */
    @Deprecated("annotated")
    fun documented(): Int {
        return seed
    }

    fun overloaded(x: Int): Int {
        return x
    }

    fun overloaded(x: String): String {
        return x
    }

    val tripled: Int = seed * 3
}

class Header(
    first: Int,
    second: Int,
) {
    val sum: Int = first + second
}

class MarkedFactories {
    // coverage-exempt: factory
    fun make(): Runnable = object : Runnable {
        override fun run() {
            println(if (System.nanoTime() > 0) "a" else "b")
        }
    }

    // coverage-exempt: builder
    fun build(): Int {
        class Local {
            fun value(): Int = if (System.nanoTime() > 0) 1 else 2
        }
        return Local().value()
    }
}
