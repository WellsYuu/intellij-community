// FIR_COMPARISON
// FIR_IDENTICAL
fun some(e: IllegalArgumentException<caret>) {
}

// INVOCATION_COUNT: 2
// WITH_ORDER
// EXIST: { lookupString:"IllegalArgumentException", tailText: " (kotlin)", typeText:"IllegalArgumentException", icon: "org/jetbrains/kotlin/idea/icons/typeAlias.svg"}
// EXIST: { lookupString:"IllegalArgumentException", tailText:" (java.lang)", icon: "RowIcon(icons=[Class, null])"}
