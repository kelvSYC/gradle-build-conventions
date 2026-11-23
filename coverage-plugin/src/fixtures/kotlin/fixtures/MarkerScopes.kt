package fixtures

class Primary(val x: Int) {
    // coverage-exempt: legacy entry point
    constructor() : this(0)

    fun branching(flag: Boolean): Int {
        return if (flag) x else -x
    }

    val y: Int = x * 2
}

class NoPrimary {
    val first: Int = 1

    // coverage-exempt: legacy entry point
    constructor(a: Int) {
        check(a >= 0)
    }

    fun between(flag: Boolean): Int {
        return if (flag) first else second
    }

    constructor(a: String) {
        check(a.isNotEmpty())
    }

    val second: Int = 2
}

class Factories {
    // coverage-exempt: factory function
    fun Holder(x: Int): Int {
        val task = object : Runnable {
            override fun run() = Unit
        }
        task.run()
        return x
    }

    class Holder {
        companion object {
            fun make(): Int = 2
        }
    }
}

class LocalOwner {
    fun build(): Int {
        // coverage-exempt: local helper
        class Helper {
            fun help(): Int = 1
        }
        return Helper().help()
    }

    class Helper {
        fun other(): Int = 2
    }
}

class Unmarked(val x: Int) {
    fun sign(flag: Boolean): Int {
        return if (flag) x else -x
    }

    val doubled: Int = x * 2
}
