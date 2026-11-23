package fixtures

inline fun twice(block: () -> Unit) {
    block()
    block()
}

inline fun sum(values: List<Int>): Int {
    var total = 0
    values.forEach { total += it }
    return total
}

fun callsInline(): Int {
    var total = 0
    twice { total += 1 }
    return total + sum(listOf(1))
}

val answer: Int
    get() = System.getProperty("answer").length

inline val doubled: Int
    get() = answer * 2
