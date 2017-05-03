package nachos.threads;

import nachos.machine.*;

/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 * 
 * <p>
 * You must implement this.
 * 
 * @see nachos.threads.Condition
 */
public class Condition2 {
	/**
	 * Allocate a new condition variable.
	 * 
	 * @param conditionLock the lock associated with this condition variable.
	 * The current thread must hold this lock whenever it uses <tt>sleep()</tt>,
	 * <tt>wake()</tt>, or <tt>wakeAll()</tt>.
	 */
	public Condition2(Lock conditionLock) {
		this.conditionLock = conditionLock;
	}

	/**
	 * Atomically release the associated lock and go to sleep on this condition
	 * variable until another thread wakes it using <tt>wake()</tt>. The current
	 * thread must hold the associated lock. The thread will automatically
	 * reacquire the lock before <tt>sleep()</tt> returns.
	 */
	public void sleep() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());
		
		
		boolean intStatus = Machine.interrupt().disable();
		
		waitQueue.waitForAccess(KThread.currentThread());//waitForAccess will check if interrupt is disabled
		
		conditionLock.release();//(if without interrupt disable)lock release should after adding to waitQueue because otherwise could switch to another thread
		//and mix up the order in waitQueue
		
		KThread.sleep();
		
		Machine.interrupt().restore(intStatus);
		
		conditionLock.acquire();
		
		
	}

	/**
	 * Wake up at most one thread sleeping on this condition variable. The
	 * current thread must hold the associated lock.
	 */
	public void wake() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());
		
		boolean intStatus = Machine.interrupt().disable();
		
		KThread thread = waitQueue.nextThread();
		if (thread != null) {
			thread.ready();
		}
		
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up all threads sleeping on this condition variable. The current
	 * thread must hold the associated lock.
	 */
	public void wakeAll() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());
		
		boolean intStatus = Machine.interrupt().disable();
		
		KThread thread;
		while((thread = waitQueue.nextThread()) != null) {
			thread.ready();
			
		}
		
		Machine.interrupt().restore(intStatus);
	}
	private static class PingTest implements Runnable {
		PingTest() {
			
		}
		
		public void run() {
			for (int i = 0; i < 20; i++) {
				cc.speak(i);
				KThread.yield();
			}
		}

	}

	/**
	 * Tests whether this module is working.
	 */
	public static void selfTest() {

		new KThread(new PingTest()).setName("speaker1").fork();
		new KThread(new PingTest()).setName("speaker2").fork();
		for(int i = 0; i < 20; i++) {
			cc.listen();
			
		}
		for(int i = 0; i < 20; i++) {
			cc.listen();
		}
		
	}
	private Lock conditionLock;
	private ThreadQueue waitQueue = ThreadedKernel.scheduler
			.newThreadQueue(false);
	private static Communicator cc = new Communicator();
}
