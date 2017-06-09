package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
		
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		swapFile = ThreadedKernel.fileSystem.open("swapfile", true);
		//sleepNoPageLock = new Lock();
		sleepNoPage = new Condition(pageLock);
		freeSwapPages = new LinkedList<Integer>();
		swapPageLock = new Lock();
		//memoryLock = new Lock();
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		swapFile.close();
		ThreadedKernel.fileSystem.remove("swapFile");
		super.terminate();
	}
	
	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';
	
	public static OpenFile swapFile;
	
	public static HashSet<Integer> pinTable = new HashSet<Integer>();
	
	//public static Lock sleepNoPageLock;
	
	public static Condition sleepNoPage;
	
	//(ppn, (VMProcess, vpn))
	public static HashMap<Integer, TreeMap<Integer, VMProcess>> InvertedPageTable = new HashMap<Integer, TreeMap<Integer, VMProcess>>();
	
	//maintain free swap pages
	public static LinkedList<Integer> freeSwapPages;
	
	public static int swapFileSize = 0;
	
	protected static Lock swapPageLock;	
	
	//public static Lock memoryLock;
	
	public static int ppnSelector = 0;
	
}
