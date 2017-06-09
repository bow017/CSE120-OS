package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		vpnInSwap = new HashMap<Integer, Integer>();
		int tableSize = Machine.processor().getTLBSize();
		stateTable = new TranslationEntry[tableSize];
		for(int i = 0; i < stateTable.length; i++) {
			stateTable[i] = new TranslationEntry(i, -1, false, false, false, false);
		}
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		//super.saveState();
		for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
			stateTable[i] = tlbEntry;
			//pageTable[tlbEntry.vpn] = tlbEntry;
			
			if(tlbEntry.valid) {
				//pageTable[tlbEntry.vpn].used = tlbEntry.used | pageTable[tlbEntry.vpn].used;
				pageTable[tlbEntry.vpn].dirty = tlbEntry.dirty | pageTable[tlbEntry.vpn].dirty;
			}
			
			TranslationEntry tmp = new TranslationEntry();//default tmp.valid = false;
			
			Machine.processor().writeTLBEntry(i, tmp);
		}
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		//super.restoreState();

//		for(int i = 0; i < stateTable.length; i++) {
//			TranslationEntry tlbEntry = new TranslationEntry(stateTable[i]);
//
//			Machine.processor().writeTLBEntry(i, tlbEntry);
//		}
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		//return super.loadSections();
		VMKernel.pageLock.acquire();
		pageTable = new TranslationEntry[numPages];
		for (int i = 0; i < numPages; i++) {
			pageTable[i] = new TranslationEntry(i, -1, false, false, false, false);
			
		}
		VMKernel.pageLock.release();
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		//super.unloadSections();
		VMKernel.pageLock.acquire();
		for(int i = 0; i < pageTable.length; i++) {
			if(pageTable[i].valid) {
				pageTable[i].valid = false;
				VMKernel.freePages.add(pageTable[i].ppn);
				//System.out.println("add ppn:"+pageTable[i].ppn);
			}
		}
		VMKernel.pageLock.release();
	}
	
	public void syncTLB(){
		for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
			TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
			if(tlbEntry.valid) {
				//tlbEntry.used = pageTable[tlbEntry.vpn].used | tlbEntry.used;
				tlbEntry.dirty = pageTable[tlbEntry.vpn].dirty | tlbEntry.dirty;
				tlbEntry.valid = pageTable[tlbEntry.vpn].valid; 
			}
			
			Machine.processor().writeTLBEntry(i, tlbEntry);
		}
	}
	public void addToTLB(int vpn) {
		//allocate a new TLB entry
		int invalidEntry = -1;
		for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
			if(!Machine.processor().readTLBEntry(i).valid) {
				invalidEntry = i;
				break;
			}
		}
		if(invalidEntry != -1) {//one invalid TLB entry is found
			
			Machine.processor().writeTLBEntry(invalidEntry, pageTable[vpn]);
		}
		else {//evict and write
			int evictEntry = new Double(Lib.random() * Machine.processor().getTLBSize()).intValue();
			TranslationEntry te = Machine.processor().readTLBEntry(evictEntry);
			Lib.assertTrue(te.valid);
			//pageTable[te.vpn].used = te.used | pageTable[te.vpn].used;
			pageTable[te.vpn].dirty = te.dirty | pageTable[te.vpn].dirty;
			Machine.processor().writeTLBEntry(evictEntry, pageTable[vpn]);
		}
	}
	
	public void handlePageFault(int vpn) {
		byte[] memory = Machine.processor().getMemory();
		
		//first swap out and get victim ppn
		int ppn;
		if(VMKernel.freePages.isEmpty()) {
			ppn = clock();
		}
		else {
			ppn = VMKernel.freePages.removeFirst();
		}
		
		int paddr = ppn * pageSize;
		
		VMKernel.pinTable.add(ppn);
		
		//second find source 
		//used to check if in coff, put here to avoid searching twice
		CoffSection targetSection = null;
		for(int i = 0; i < coff.getNumSections(); i++) {
			CoffSection section = coff.getSection(i);
			if(vpn >= section.getFirstVPN() && vpn < section.getFirstVPN() + section.getLength()) {
				targetSection = section;
				break;
			}
		}
		
		pageTable[vpn].readOnly = false;
		
		//check if is in the swap file
		if(vpnInSwap.containsKey(vpn)) {
			//System.out.println("swap:vpn:"+vpn);
			byte[] buffer = new byte[pageSize];
			int spn = vpnInSwap.get(vpn);
			int byteRead = VMKernel.swapFile.read(spn * pageSize, buffer, 0, pageSize);
			
			if(byteRead < pageSize) {
				System.out.println("fail to read from swap file");
				Lib.debug(dbgVM, "handle page fault: fail to read from swap file");
				return;
			}
			System.arraycopy(buffer, 0, memory, paddr, pageSize);
			deallocateSwap(spn);
			vpnInSwap.remove(vpn);
			
			pageTable[vpn].dirty = true;
			if(targetSection != null && targetSection.isReadOnly()) {
				pageTable[vpn].readOnly = true;
			}
		}
		else {
			if(targetSection != null) {//in the coff
				int spn = vpn - targetSection.getFirstVPN();
				if(targetSection.isReadOnly()) {
					pageTable[vpn].readOnly = true;
				}
				
				targetSection.loadPage(spn, ppn);//load into memory
			}
			else {//stack
				byte[] buffer = new byte[pageSize];//default to initialize to 0
				System.arraycopy(buffer, 0, memory, paddr, pageSize);//load into memory
			}
		}
		
		//update inverted page table
		TreeMap<Integer, VMProcess> innerMap = new TreeMap<Integer, VMProcess>();
		innerMap.put(vpn, this);
		VMKernel.InvertedPageTable.put(ppn, innerMap);
				
		//update page table
		pageTable[vpn].ppn = ppn;
		pageTable[vpn].valid = true;
		
		//VMKernel.sleepNoPageLock.release();
		
		if(this == VMKernel.currentProcess()) {
			addToTLB(vpn);
			//syncTLB();
		}
						
		VMKernel.pinTable.remove(pageTable[vpn].ppn);
		//VMKernel.sleepNoPageLock.acquire();
		VMKernel.sleepNoPage.wake();
		//VMKernel.sleepNoPageLock.release();
		
	}
	
	public void handleTLBMiss(int regBadVAddr) {
		int vpn = Processor.pageFromAddress(regBadVAddr);
		Lib.assertTrue(vpn >= 0 && vpn < pageTable.length, "vpn out of range:"+vpn+"  size:"+pageTable.length);
		//critical section: managing page table
		VMKernel.pageLock.acquire();
		if(pageTable[vpn].valid) {//page is in the memory
			if(this == VMKernel.currentProcess()) {
				addToTLB(vpn);
			}
		}
		else {
			handlePageFault(vpn);
		}
//		if(this == VMKernel.currentProcess()) {
//			syncTLB();
//		}
		VMKernel.pageLock.release();
		debugTlb = new TranslationEntry[Machine.processor().getTLBSize()];
		for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
			debugTlb[i] = Machine.processor().readTLBEntry(i);
		}
	}
	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();
		
		switch (cause) {
		case Processor.exceptionTLBMiss:
			handleTLBMiss(processor.readRegister(Processor.regBadVAddr));
			break;
		default:
			super.handleException(cause);
			break;
		}
	}
	public int clock() {
		//choose victim ppn	
		int victim = -1;
		boolean found = false;
		
		//sync used
		if(this == VMKernel.currentProcess()) {
			for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
				TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
				if(tlbEntry.valid) {
					pageTable[tlbEntry.vpn].used = pageTable[tlbEntry.vpn].used | tlbEntry.used;
					pageTable[tlbEntry.vpn].dirty = pageTable[tlbEntry.vpn].dirty | tlbEntry.dirty; 
				}
			}
		}
		
		//but since this is within the critical section actually two processes can't access at the same time
		while(VMKernel.pinTable.size() == Machine.processor().getNumPhysPages()) {//all pages are pinned
			System.out.println("sleep");
			VMKernel.sleepNoPage.sleep();
		}
		
		int ppn = VMKernel.ppnSelector;
		while(!found) {
			while(ppn < Machine.processor().getNumPhysPages()) {
				ppn = ppn % Machine.processor().getNumPhysPages();
				int vpn  = VMKernel.InvertedPageTable.get(ppn).firstKey();
				VMProcess process = VMKernel.InvertedPageTable.get(ppn).firstEntry().getValue();
				if(VMKernel.pinTable.contains(ppn)) {
					continue;
				}
				if(process.pageTable[vpn].used) {
					process.pageTable[vpn].used = false;
				}
				else {
					found = true;
					victim = ppn;
					VMKernel.ppnSelector = ppn + 1;
					
					if(process.pageTable[vpn].dirty) {//need to swap out
						byte[] buffer = new byte[pageSize];

						byte[] memory = Machine.processor().getMemory();
						int paddr = ppn * pageSize;
						System.arraycopy(memory, paddr, buffer, 0, pageSize);
						//find spn
						int spn = allocateSwap();
						if(spn == -1) {//no internel spn
							spn = VMKernel.swapFileSize++;
						}
						VMKernel.swapFile.write(spn * pageSize, buffer, 0, pageSize);
						process.vpnInSwap.put(vpn, spn);
					}
					process.pageTable[vpn] = new TranslationEntry(vpn, -1, false, false, false, false);
					if(process == VMKernel.currentProcess()) {
						
						syncTLB();
					}
					
					VMKernel.InvertedPageTable.remove(victim);
					break;
				}
				ppn++;
			}
			ppn = 0;
		}
		return victim;
	}
	
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);
		
		byte[] memory = Machine.processor().getMemory();

		int amount = 0;
		while(amount < length && offset < data.length) {
			int vpn = Processor.pageFromAddress(vaddr);
			if(vpn < 0 || vpn >= pageTable.length) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(vpn out of range)");
				break;
			}
			
			//critical section as long as it may change other processes' page table or acquire free pages
			VMKernel.pageLock.acquire();		
			if(!pageTable[vpn].valid) {
				handlePageFault(vpn);//allocate ppn
			}
			VMKernel.pinTable.add(pageTable[vpn].ppn);//"I am using this ppn!"
			pageTable[vpn].used = true;
//			if(this == VMKernel.currentProcess()) {
//				syncTLB();
//			}
			
			int addrOffset = Processor.offsetFromAddress(vaddr);
			int paddr = pageTable[vpn].ppn * pageSize + addrOffset;

			int transferAmount = Math.min(Math.min(length, pageSize - addrOffset), data.length - offset);
			
			System.arraycopy(memory, paddr, data, offset, transferAmount);
			
			VMKernel.pinTable.remove(pageTable[vpn].ppn);
			
			VMKernel.sleepNoPage.wake();
			//finished pinning a ppn which means other processes could evict one now
			VMKernel.pageLock.release();
			
			vaddr += transferAmount;
			offset += transferAmount;
			amount += transferAmount;
		}
		return amount;
	}

	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);
		
		byte[] memory = Machine.processor().getMemory();
	
		int amount = 0;
		while(amount < length && offset < data.length) {
			int vpn = Processor.pageFromAddress(vaddr);
	
			if(vpn < 0 || vpn >= pageTable.length) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(vpn out of range)");
				break;
			}
			if(pageTable[vpn].readOnly) {
				Lib.debug(dbgProcess, "readVirtualMemory: invalid vaddr(read only!)");
				return amount;
			}
			
			//critical section as long as it may change other processes' page table or acquire free pages
			VMKernel.pageLock.acquire();		
			if(!pageTable[vpn].valid) {
				handlePageFault(vpn);//allocate ppn
			}
			VMKernel.pinTable.add(pageTable[vpn].ppn);//"I am using this ppn!"
			pageTable[vpn].used = true;
			pageTable[vpn].dirty = true;
			
//			if(this == VMKernel.currentProcess()) {
//				syncTLB();
//			}
			
			int addrOffset = Processor.offsetFromAddress(vaddr);
			int paddr = pageTable[vpn].ppn * pageSize + addrOffset;
			
			
			int transferAmount = Math.min(Math.min(length, pageSize - addrOffset), data.length - offset);
			System.arraycopy(data, offset, memory, paddr, transferAmount);
			
			VMKernel.pinTable.remove(pageTable[vpn].ppn);
			
			
			VMKernel.sleepNoPage.wake();
			VMKernel.pageLock.release();
			
			vaddr += transferAmount;
			offset += transferAmount;
			amount += transferAmount;
		}
		return amount;

	}
	private HashMap<Integer, Integer> vpnInSwap;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
	
	private TranslationEntry[] stateTable;

	
	public int allocateSwap() {
		VMKernel.swapPageLock.acquire();
		if(VMKernel.freeSwapPages.isEmpty()) {
			VMKernel.swapPageLock.release();
			return -1;
		}
		int spn = VMKernel.freeSwapPages.removeFirst();
		VMKernel.swapPageLock.release();
		return spn;
	}
	
	public void deallocateSwap(int spn) {
		VMKernel.swapPageLock.acquire();
		VMKernel.freeSwapPages.add(new Integer(spn));
		VMKernel.swapPageLock.release();
		return;
	}
	
	public TranslationEntry[] debugTlb;

}
