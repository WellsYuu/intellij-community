fun foo() {
    val a = mutableListOf("A", "B").also { it.add("C") }<caret>
    val b = a
}

// EXISTS: mutableListOf(T)
// EXISTS: also: block.invoke()
