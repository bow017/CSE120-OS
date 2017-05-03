package nachos.threads;

import nachos.machine.*;

/**
 * A <i>communicator</i> allows threads to synchronously exchange 32-bit
 * messages. Multiple threads can be waiting to <i>speak</i>, and multiple
 * threads can be waiting to <i>listen</i>. But there should never be a time
 * when both a speaker and a listener are waiting, because the two threads can
 * be paired off at this point.
 */
public class Communicator {
	/**
	 * Allocate a new communicator.
	 */
	public Communicator() {
	}

	/**
	 * Wait for a thread to listen through this communicator, and then transfer
	 * <i>word</i> to the listener.
	 * 
	 * <p>
	 * Does not return until this thread is paired up with a listening thread.
	 * Exactly one listener should receive <i>word</i>.
	 * 
	 * @param word the integer to transfer.
	 */
	public void speak(int word) {
		
		conditionLock.acquire();
		while(someoneIsSpeaking) {
			condNoSpeaker.sleep();
		}
		someoneIsSpeaking = true;
		this.word = word;
		System.out.println(KThread.currentThread().getName()+"speaks the word: "+word);
		
		//wake up a listener if they are waiting for someone to speak
		condHasSpeaker.wake();
		
		//wait until a listener has acknowledgement they have heard my word.
		condAck.sleep();
		
		
		conditionLock.release();
		
		
	}

	/**
	 * Wait for a thread to speak through this communicator, and then return the
	 * <i>word</i> that thread passed to <tt>speak()</tt>.
	 * 
	 * @return the integer transferred.
	 */
	public int listen() {
		
		conditionLock.acquire();
		while (!someoneIsSpeaking) {
			//wake up someone who is waiting to speak
			condNoSpeaker.wake();
			
			//wait until someone is speaking
			condHasSpeaker.sleep();
		}

		int heardWord = this.word;
		System.out.println("word is listened: "+word);
		someoneIsSpeaking = false;//must put here, in case of multiple listeners
		
		//acknowledge to the speaker that you've heard them
		condAck.wake();
		
		conditionLock.release();
		
		return heardWord;
	}
	
	private static Lock conditionLock = new Lock();
	private static Condition2 condNoSpeaker = new Condition2(conditionLock);
	private static Condition2 condHasSpeaker = new Condition2(conditionLock);
	private static Condition2 condAck = new Condition2(conditionLock);
	private static boolean someoneIsSpeaking = false;
	private int word;
}
