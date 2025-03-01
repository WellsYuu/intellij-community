class Passenger {
    open class PassParent

    class PassChild : PassParent()

    fun provideNullable(p: Int): PassParent? {
        return if (p > 0) PassChild() else null
    }

    fun test1() {
        val pass = checkNotNull(provideNullable(1))
        accept1(pass as PassChild)
    }

    fun test2() {
        val pass = provideNullable(1)
        if (1 == 2) {
            checkNotNull(pass)
            accept2(pass as PassChild?)
        }
        accept2(pass as PassChild?)
    }

    fun accept1(p: PassChild?) {
    }

    fun accept2(p: PassChild?) {
    }
}