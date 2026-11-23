package fixtures

interface Speaker {
    // coverage-exempt: abstract, no code
    fun speak(): String
}

class Between {
    fun between(): Int = 1
}

class Forwarding(private val delegate: Speaker) : Speaker by delegate
