package fixtures

data class D(val a: Int)

private class Hidden {
    fun m() {
        println("m")
    }
}

open class Base {
    protected fun p() {
        println("p")
    }

    internal fun q() {
        println("q")
    }

    private fun r() {
        println("r")
    }

    fun s() {
        r()
    }
}

class Outer {
    private class Inner {
        fun x() {
            println("x")
        }
    }

    class Nested {
        fun y() {
            println("y")
        }
    }

    fun use() = Inner().x()
}
