package minty.emanata.testing

/**
 * True while another thread waits in [Thread.join] for [thread]: a join waits on the
 * thread's own monitor. Lets held work go on exactly when something waits for it. Reads
 * the JVM's thread bean by reflection: tests compile against Android's classes, which
 * lack `java.lang.management`.
 */
fun joined(thread: Thread): Boolean {
    val id = System.identityHashCode(thread)
    val infos = threadInfo.invoke(bean, allThreadIds.invoke(bean), 0) as Array<*>
    return infos.any { info ->
        if (info == null || threadState.invoke(info) == Thread.State.BLOCKED) return@any false
        val lock = lockInfo.invoke(info) ?: return@any false
        identityHashCode.invoke(lock) == id && className.invoke(lock) == thread.javaClass.name
    }
}

private val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
private val beanType = Class.forName("java.lang.management.ThreadMXBean")
private val allThreadIds = beanType.getMethod("getAllThreadIds")
private val threadInfo = beanType.getMethod("getThreadInfo", LongArray::class.java, Int::class.javaPrimitiveType)
private val infoType = Class.forName("java.lang.management.ThreadInfo")
private val threadState = infoType.getMethod("getThreadState")
private val lockInfo = infoType.getMethod("getLockInfo")
private val lockType = Class.forName("java.lang.management.LockInfo")
private val identityHashCode = lockType.getMethod("getIdentityHashCode")
private val className = lockType.getMethod("getClassName")
