package fixtures

inline fun twice(block: () -> Unit) {
    block()
    block()
}

fun callsInline(): Int {
    var total = 0
    listOf(1, 2).forEach { total += it }
    twice { total += 1 }
    return total.let { it + 1 }
}

enum class Color { RED, GREEN }

interface Greeter {
    fun greet(): String
}

class Delegating(private val greeter: Greeter) : Greeter by greeter
