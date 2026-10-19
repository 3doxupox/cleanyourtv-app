package tv.cleanyour.core.flows

// What the wizard screens can ask for: the frozen intents plus the RFC-W8-1 additions. The
// SetupMachine implements it; screenshot tests pass NoSetupActions.
interface SetupActions {
    fun intent(intent: SetupIntent)
    fun back(): Boolean
    fun stopAction(action: StopAction)
    fun rejectIdentity()
    fun dismissCard()
}

object NoSetupActions : SetupActions {
    override fun intent(intent: SetupIntent) {}
    override fun back(): Boolean = false
    override fun stopAction(action: StopAction) {}
    override fun rejectIdentity() {}
    override fun dismissCard() {}
}
