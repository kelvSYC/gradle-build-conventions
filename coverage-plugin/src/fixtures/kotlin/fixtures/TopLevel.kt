package fixtures

fun f() {
    println("f")
}

internal fun g() {
    println("g")
}

fun h(a: Int = 1) {
    println(a)
}

inline fun i(block: () -> Unit) {
    block()
}

fun spans(): Int {
    val a = 1
    val b = 2
    return a + b
}

val answer: Int
    get() = System.getProperty("answer").length

inline val doubled: Int
    get() = answer * 2
