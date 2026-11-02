package mlabeler.app.toolkit

/** Native shutdown can wait briefly; browser builds do not start a toolkit. */
expect fun finishToolkit(block: suspend () -> Unit)
