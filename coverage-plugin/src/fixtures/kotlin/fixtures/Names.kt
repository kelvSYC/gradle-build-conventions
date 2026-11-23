package fixtures

fun <T : Comparable<T>> largest(
    a: T,
    b: T,
): T {
    return if (a > b) a else b
}

fun Map<String, Int>.total(): Int {
    return values.sum()
}

fun <K, V> Map<K, V>.firstKey(): K {
    return keys.first()
}

fun (() -> Unit).runTwice() {
    this()
    this()
}

val <K, V> Map<K, V>.doubleSize: Int
    get() = size * 2

@JvmName("renamed")
fun original(): Int {
    return 1
}

@get:JvmName("isReady")
val ready: Boolean
    get() = System.nanoTime() > 0

// coverage-exempt: generic helper
fun <T : Comparable<T>> bounded(a: T, b: T): T =
    if (a > b) a else b
